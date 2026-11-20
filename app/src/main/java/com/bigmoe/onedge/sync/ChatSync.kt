package com.bigmoe.onedge.sync

import android.content.Context
import com.bigmoe.onedge.core.RemoteServerStore
import com.bigmoe.onedge.data.local.dao.ArtifactDao
import com.bigmoe.onedge.data.local.dao.AttachmentDao
import com.bigmoe.onedge.data.local.dao.ChatMessageDao
import com.bigmoe.onedge.data.local.dao.ChatSessionDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Keeps <workspace>/chats (the chats as files, [ChatFolder]) in step with the database and, when signed in, the
 * chat files and the files the chats refer to (attachments, results) with the OneDrive app folder:
 *   Apps/<registration>/chats/<title>.json
 *   Apps/<registration>/workspace/<path in the workspace>   (only files a chat refers to; agent scripts stay local)
 *   Apps/<registration>/settings/servers.json               (server models incl. API keys)
 *
 * Chat files: per name the local size+mtime and the remote cTag of the last sync tell who changed; one side → it
 * wins, both → the newer change (changedAt in the file) wins; deletions follow. Workspace files are uploaded when
 * new or changed and downloaded when a chat needs them; more than [LARGE_UPLOAD] bytes wait for a confirmation.
 *
 * Runs at app start, when the app comes to the front and a few seconds after a chat changed (never during a
 * generation).
 */
class ChatSync(
    context: Context,
    private val sessionDao: ChatSessionDao,
    private val messageDao: ChatMessageDao,
    private val attachmentDao: AttachmentDao,
    private val artifactDao: ArtifactDao,
    private val remoteServers: RemoteServerStore,
    private val workspaceRoot: () -> File
) {
    data class Status(
        val running: Boolean = false,
        val lastSyncAt: Long = 0L,
        val message: String = "",
        /** Bytes of files waiting for "Upload anyway" (more than [LARGE_UPLOAD]); 0 = none. */
        val pendingUploadBytes: Long = 0L,
        /** Size of the app folder on OneDrive after the last sync; -1 = unknown. */
        val cloudBytes: Long = -1L
    )

    val auth = OneDriveAuth(File(context.filesDir, "onedrive_auth.json"))
    private val client = OneDriveClient(auth)
    private val stateFile = File(context.filesDir, "chat_sync_state.json")
    val folder = ChatFolder(workspaceRoot, File(context.filesDir, "chat_folder_state.json"), sessionDao, messageDao, attachmentDao, artifactDao)
    private val mutex = Mutex()
    @Volatile private var pending = false
    @Volatile private var foldersReady = false
    @Volatile private var allowLargeOnce = false
    private var scope: CoroutineScope? = null

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Ids of chats deleted on OneDrive (other device, PC) but kept here; writing in one uploads it again. */
    private val _remoteDeleted = MutableStateFlow<Set<String>>(emptySet())
    val remoteDeleted: StateFlow<Set<String>> = _remoteDeleted.asStateFlow()

    /** Signed in or not, for the settings page (it changes when the browser comes back). */
    private val _signedIn = MutableStateFlow(auth.signedIn)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    @OptIn(FlowPreview::class)
    fun start(appScope: CoroutineScope) {
        scope = appScope
        appScope.launch {
            delay(2000)
            syncNow()
        }
        appScope.launch {
            sessionDao.observeAllSessions()
                .map { list -> list.joinToString { it.id + it.updatedAt + it.title + it.generationState }.hashCode() }
                .distinctUntilChanged()
                .drop(1)
                .debounce(3000)
                .collect { syncNow() }
        }
        appScope.launch {
            remoteServers.servers.drop(1).debounce(3000).collect { syncNow() }
        }
    }

    /** Fire and forget (app back in front, "sync now"). */
    fun requestSync() {
        scope?.launch { syncNow() }
    }

    /** "Upload anyway": the next sync uploads even more than [LARGE_UPLOAD]. */
    fun confirmLargeUpload() {
        allowLargeOnce = true
        requestSync()
    }

    // ---- sign-in ------------------------------------------------------------------------------------------

    /** URL to open in the browser for the sign-in. */
    fun signInUrl(): String = auth.authorizeUrl()

    /** The browser came back to [AuthRedirectActivity]. */
    suspend fun completeSignIn(redirect: android.net.Uri) = withContext(Dispatchers.IO) {
        try {
            auth.completeAuthorization(redirect)
            auth.rememberAccount(client.ownerName())
            foldersReady = false
            _signedIn.value = true
            _status.value = Status(message = "Signed in")
            syncNow()
        } catch (t: Throwable) {
            _status.value = Status(message = "Sign-in failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    fun signOut() {
        auth.signOut()
        _signedIn.value = false
        stateFile.delete() // a later sign-in (maybe another account) starts clean: nothing is deleted on either side
        _status.value = Status(message = "Signed out")
    }

    // ---- the sync -----------------------------------------------------------------------------------------

    suspend fun syncNow() {
        if (!mutex.tryLock()) {
            pending = true
            return
        }
        try {
            do {
                pending = false
                runOnce()
            } while (pending)
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun runOnce() = withContext(Dispatchers.IO) {
        // 1. Database <-> chat files (always, also without OneDrive).
        try {
            folder.reconcile()
        } catch (t: Throwable) {
            _status.value = _status.value.copy(message = "Chat folder: ${t.message ?: t.javaClass.simpleName}")
            return@withContext
        }
        if (!auth.signedIn) return@withContext
        if (sessionDao.getAllSessions().any { it.generationState == "RUNNING" }) return@withContext
        _status.value = _status.value.copy(running = true)
        var up = 0
        var down = 0
        var deleted = 0
        try {
            if (!foldersReady) {
                client.rootSize() // opens (creates) the app folder on the first access
                for (f in listOf(CHATS, WORKSPACE, SETTINGS)) runCatching { client.ensureFolder(f) }
                foldersReady = true
            }
            val state = readState()
            val chatState = state.optJSONObject("chats") ?: JSONObject().also { state.put("chats", it) }
            val goneRemote = state.optJSONObject("remoteDeleted") ?: JSONObject().also { state.put("remoteDeleted", it) }

            // 2. Chat files <-> OneDrive.
            val dir = folder.dir.apply { mkdirs() }
            val local = (dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray()).associateBy { it.name }
            val remote = client.list(CHATS).filter { it.name.endsWith(".json") }.associateBy { it.name }
            var filesChanged = false
            val names = LinkedHashSet<String>().apply { addAll(local.keys); addAll(remote.keys); chatState.keys().forEach { add(it) } }
            for (name in names) {
                val l = local[name]
                val r = remote[name]
                val s = chatState.optJSONObject(name)
                val sig = l?.let { ChatFolder.sig(it) }
                val localCh = l != null && (s == null || s.optString("sig") != sig)
                val remoteCh = r != null && (s == null || s.optString("tag") != r.tag)
                val path = "$CHATS/$name"
                if (l != null && r == null) {
                    if (s != null && !localCh) {
                        // Deleted on OneDrive: kept here with a notice until it is written in (upload again) or deleted.
                        if (!goneRemote.has(name)) {
                            ChatFile.decode(l.readText())?.session?.id?.let { goneRemote.put(name, it) }
                        }
                    } else {
                        chatState.put(name, entry(sig!!, client.uploadFile(path, l))); up++
                        goneRemote.remove(name)
                    }
                } else if (l == null && r != null) {
                    if (s != null && !remoteCh) {
                        client.delete(path); deleted++
                        chatState.remove(name)
                    } else {
                        val target = File(dir, name)
                        if (client.downloadToFile(path, target)) {
                            chatState.put(name, entry(ChatFolder.sig(target), r.tag)); down++; filesChanged = true
                        }
                    }
                } else if (l != null && r != null) {
                    if (!localCh && !remoteCh) continue
                    val remoteWins = if (remoteCh && !localCh) true else if (localCh && !remoteCh) false else {
                        // Changed on both devices: the newer change wins.
                        val theirs = client.download(path)?.let { ChatFile.decode(it) }?.changedAt ?: 0L
                        val ours = ChatFile.decode(l.readText())?.changedAt ?: 0L
                        theirs > ours
                    }
                    if (remoteWins) {
                        if (client.downloadToFile(path, l)) {
                            chatState.put(name, entry(ChatFolder.sig(l), r.tag)); down++; filesChanged = true
                        }
                    } else {
                        chatState.put(name, entry(sig!!, client.uploadFile(path, l))); up++
                    }
                } else {
                    chatState.remove(name)
                }
            }
            if (filesChanged) folder.reconcile()
            // Marks of chats that are gone here as well (deleted locally) or back on OneDrive are dropped.
            val marks = ArrayList<String>().apply { goneRemote.keys().forEach { add(it) } }
            marks.forEach { n -> if (!File(dir, n).exists() || remote.containsKey(n)) goneRemote.remove(n) }
            _remoteDeleted.value = HashSet<String>().apply { goneRemote.keys().forEach { k -> add(goneRemote.optString(k)) } }

            // 3. Files the chats refer to (attachments, results).
            val (fUp, fDown, pendingBytes) = syncWorkspaceFiles(state)
            up += fUp
            down += fDown

            // 4. Server models.
            syncServers(state)
            writeState(state)
            val size = runCatching { client.rootSize() }.getOrDefault(-1L)
            val parts = listOfNotNull(
                "$up up".takeIf { up > 0 }, "$down down".takeIf { down > 0 }, "$deleted deleted".takeIf { deleted > 0 }
            )
            _status.value = Status(
                running = false,
                lastSyncAt = System.currentTimeMillis(),
                message = if (parts.isEmpty()) "Up to date" else parts.joinToString(", "),
                pendingUploadBytes = pendingBytes,
                cloudBytes = size
            )
        } catch (t: Throwable) {
            _status.value = _status.value.copy(running = false, message = "Error: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    /**
     * Uploads new/changed files of the chats and downloads missing ones.
     * Returns (uploaded, downloaded, bytes held back because they exceed [LARGE_UPLOAD] without a confirmation).
     */
    private suspend fun syncWorkspaceFiles(state: JSONObject): Triple<Int, Int, Long> {
        val fileState = state.optJSONObject("files") ?: JSONObject().also { state.put("files", it) }
        val root = workspaceRoot()
        val paths = LinkedHashSet<String>()
        for (session in sessionDao.getAllSessions()) {
            attachmentDao.getForSession(session.id).forEach { paths.add(it.path) }
            artifactDao.getForSession(session.id).forEach { paths.add(it.path) }
        }
        val toUpload = ArrayList<Pair<String, File>>()
        var down = 0
        for (rel in paths) {
            val clean = rel.trim('/').takeIf { it.isNotBlank() && !it.contains("..") } ?: continue
            val f = File(root, clean)
            if (f.isFile) {
                if (fileState.optString(clean) != ChatFolder.sig(f)) toUpload.add(clean to f)
            } else {
                // Needed by a chat but not here: fetch it (absent on OneDrive too = nothing to do).
                if (runCatching { client.downloadToFile("$WORKSPACE/$clean", f) }.getOrDefault(false)) {
                    fileState.put(clean, ChatFolder.sig(f)); down++
                }
            }
        }
        // Files no chat refers to any more (attachment or result deleted, chat deleted): to the OneDrive recycle bin.
        val known = HashSet<String>().apply { paths.forEach { p -> p.trim('/').takeIf { it.isNotBlank() }?.let { add(it) } } }
        val gone = ArrayList<String>()
        fileState.keys().forEach { if (it !in known) gone.add(it) }
        for (rel in gone) {
            runCatching { client.delete("$WORKSPACE/$rel") }.onSuccess { fileState.remove(rel) }
        }
        val total = toUpload.sumOf { it.second.length() }
        if (total > LARGE_UPLOAD && !allowLargeOnce) return Triple(0, down, total)
        allowLargeOnce = false
        var up = 0
        for ((clean, f) in toUpload) {
            client.uploadFile("$WORKSPACE/$clean", f)
            fileState.put(clean, ChatFolder.sig(f)); up++
        }
        return Triple(up, down, 0L)
    }

    private fun syncServers(state: JSONObject) {
        val s = state.optJSONObject("servers")
        val file = "$SETTINGS/servers.json"
        val r = client.list(SETTINGS).firstOrNull { it.name == "servers.json" }
        val localText = remoteServers.exportJson()
        val hash = localText.hashCode().toString()
        val localChanged = s == null || s.optString("hash") != hash
        val remoteChanged = r != null && (s == null || s.optString("tag") != r.tag)
        val neverEdited = remoteServers.updatedAt == 0L
        if (r == null) {
            if (!neverEdited) state.put("servers", JSONObject().put("hash", hash).put("tag", client.upload(file, localText)))
        } else if (!localChanged && !remoteChanged) {
            return
        } else if (localChanged && !remoteChanged && !neverEdited) {
            state.put("servers", JSONObject().put("hash", hash).put("tag", client.upload(file, localText)))
        } else {
            val text = client.download(file) ?: return
            val remoteAt = RemoteServerStore.parse(text)?.first ?: 0L
            if (remoteAt > remoteServers.updatedAt || neverEdited) {
                remoteServers.importJson(text)
                state.put("servers", JSONObject().put("hash", remoteServers.exportJson().hashCode().toString()).put("tag", r.tag))
            } else {
                state.put("servers", JSONObject().put("hash", hash).put("tag", client.upload(file, localText)))
            }
        }
    }

    private fun entry(sig: String, tag: String) = JSONObject().put("sig", sig).put("tag", tag)

    private fun readState(): JSONObject = runCatching { JSONObject(stateFile.readText()) }.getOrElse { JSONObject() }

    private fun writeState(o: JSONObject) {
        val tmp = File(stateFile.parentFile, stateFile.name + ".tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(stateFile)
    }

    companion object {
        const val CHATS = "chats"
        const val WORKSPACE = "workspace"
        const val SETTINGS = "settings"
        const val LARGE_UPLOAD = 1024L * 1024 * 1024
    }
}
