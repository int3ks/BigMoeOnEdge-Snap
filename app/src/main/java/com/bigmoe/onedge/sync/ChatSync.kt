package com.bigmoe.onedge.sync

import android.content.Context
import com.bigmoe.onedge.core.RemoteServerStore
import com.bigmoe.onedge.data.local.dao.ChatMessageDao
import com.bigmoe.onedge.data.local.dao.ChatSessionDao
import com.bigmoe.onedge.data.local.dao.MessageFingerprint
import com.bigmoe.onedge.data.local.entity.ChatSessionEntity
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
 * Two-way sync of the chats (and the server-model list) with the user's OneDrive app folder:
 *   Apps/<registration>/chats/<chat id>.json   (format: [ChatFile])
 *   Apps/<registration>/settings/servers.json  (server models incl. API keys)
 *
 * Runs at app start, when the app comes to the front and a few seconds after a chat changed (never during a
 * generation). Per chat it remembers the local fingerprint and the remote content tag of the last sync, so it can
 * tell who changed: only one side → that side wins; both → the copy with the newer change time wins. A chat
 * deleted on one device (and unchanged on the other) is deleted on the other one too.
 */
class ChatSync(
    context: Context,
    private val sessionDao: ChatSessionDao,
    private val messageDao: ChatMessageDao,
    private val remoteServers: RemoteServerStore
) {
    data class Status(val running: Boolean = false, val lastSyncAt: Long = 0L, val message: String = "")

    val auth = OneDriveAuth(File(context.filesDir, "onedrive_auth.json"))
    private val client = OneDriveClient(auth)
    private val stateFile = File(context.filesDir, "chat_sync_state.json")
    private val mutex = Mutex()
    @Volatile private var pending = false
    @Volatile private var foldersReady = false
    private var scope: CoroutineScope? = null

    private val _status = MutableStateFlow(Status(message = if (auth.signedIn) "" else "Not set up"))
    val status: StateFlow<Status> = _status.asStateFlow()

    val configured: Boolean get() = auth.clientId.isNotBlank() && auth.signedIn

    @OptIn(FlowPreview::class)
    fun start(appScope: CoroutineScope) {
        scope = appScope
        appScope.launch {
            delay(3000)
            syncNow()
        }
        appScope.launch {
            sessionDao.observeAllSessions()
                .map { list -> list.joinToString { it.id + it.updatedAt + it.title + it.generationState }.hashCode() }
                .distinctUntilChanged()
                .drop(1)
                .debounce(5000)
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

    // ---- sign-in ------------------------------------------------------------------------------------------

    suspend fun startSignIn(clientId: String): OneDriveAuth.DeviceCode = withContext(Dispatchers.IO) {
        auth.clientId = clientId
        auth.startDeviceCode()
    }

    /** Polls until signed in (true) or the code expired; throws on a refusal. */
    suspend fun finishSignIn(code: OneDriveAuth.DeviceCode): Boolean = withContext(Dispatchers.IO) {
        while (System.currentTimeMillis() < code.expiresAtMs) {
            delay(code.intervalS * 1000L)
            if (auth.pollDeviceCode(code)) {
                auth.rememberAccount(client.ownerName())
                foldersReady = false
                _status.value = Status(message = "Signed in")
                requestSync()
                return@withContext true
            }
        }
        false
    }

    fun signOut() {
        auth.signOut()
        stateFile.delete() // a later sign-in (maybe another account) starts clean: nothing is deleted on either side
        _status.value = Status(message = "Signed out")
    }

    // ---- the sync -----------------------------------------------------------------------------------------

    suspend fun syncNow() {
        if (!configured) return
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
        val sessions = sessionDao.getAllSessions()
        if (sessions.any { it.generationState == "RUNNING" }) return@withContext // after the turn (DB change) again
        _status.value = _status.value.copy(running = true)
        var up = 0
        var down = 0
        var deleted = 0
        try {
            if (!foldersReady) {
                client.ensureFolder(CHATS)
                client.ensureFolder(SETTINGS)
                foldersReady = true
            }
            val state = readState()
            val chatState = state.optJSONObject("chats") ?: JSONObject().also { state.put("chats", it) }

            val local = sessions.associateBy { it.id }
            val fps = messageDao.fingerprints().associateBy { it.sessionId }
            val remote = client.list(CHATS).filter { it.name.endsWith(".json") }.associateBy { it.name.removeSuffix(".json") }
            val imported = ArrayList<String>()

            val ids = LinkedHashSet<String>().apply { addAll(local.keys); addAll(remote.keys); chatState.keys().forEach { add(it) } }
            for (id in ids) {
                val l = local[id]
                val r = remote[id]
                val s = chatState.optJSONObject(id)
                val fp = l?.let { fingerprint(it, fps[id]) }
                val localChanged = l != null && (s == null || s.optString("fp") != fp)
                val remoteChanged = r != null && (s == null || s.optString("tag") != r.tag)
                val file = "$CHATS/$id.json"
                when {
                    l != null && r == null -> {
                        if (s != null && !localChanged) {
                            sessionDao.deleteSessionById(id); deleted++
                            chatState.remove(id)
                        } else {
                            chatState.put(id, entry(fp!!, upload(l, file))); up++
                        }
                    }
                    l == null && r != null -> {
                        if (s != null && !remoteChanged) {
                            client.delete(file); deleted++
                            chatState.remove(id)
                        } else if (importChat(file)) {
                            chatState.put(id, entry("", r.tag)); imported.add(id); down++
                        }
                    }
                    l != null && r != null -> {
                        if (!localChanged && !remoteChanged) continue
                        if (localChanged && !remoteChanged) {
                            chatState.put(id, entry(fp!!, upload(l, file))); up++
                            continue
                        }
                        if (!localChanged) {
                            if (importChat(file)) { chatState.put(id, entry("", r.tag)); imported.add(id); down++ }
                            continue
                        }
                        // Changed on both devices: the newer change wins.
                        val text = client.download(file)
                        val parsed = text?.let { ChatFile.decode(it) }
                        val localAt = ChatFile.changedAt(l, messageDao.getMessagesForSession(id))
                        if (parsed != null && parsed.changedAt > localAt) {
                            store(parsed)
                            chatState.put(id, entry("", r.tag)); imported.add(id); down++
                        } else {
                            chatState.put(id, entry(fp!!, upload(l, file))); up++
                        }
                    }
                    else -> chatState.remove(id)
                }
            }
            // What an import left in the DB is the new "synced" local state.
            if (imported.isNotEmpty()) {
                val after = messageDao.fingerprints().associateBy { it.sessionId }
                imported.forEach { id ->
                    val sess = sessionDao.getSessionById(id) ?: return@forEach
                    chatState.optJSONObject(id)?.put("fp", fingerprint(sess, after[id]))
                }
            }

            syncServers(state)
            writeState(state)
            val parts = listOfNotNull(
                "$up up".takeIf { up > 0 }, "$down down".takeIf { down > 0 }, "$deleted deleted".takeIf { deleted > 0 }
            )
            _status.value = Status(false, System.currentTimeMillis(), if (parts.isEmpty()) "Up to date" else parts.joinToString(", "))
        } catch (t: Throwable) {
            _status.value = Status(false, _status.value.lastSyncAt, "Error: ${t.message ?: t.javaClass.simpleName}")
        }
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
        when {
            r == null -> if (!neverEdited) state.put("servers", JSONObject().put("hash", hash).put("tag", client.upload(file, localText)))
            !localChanged && !remoteChanged -> Unit
            localChanged && !remoteChanged && !neverEdited -> state.put("servers", JSONObject().put("hash", hash).put("tag", client.upload(file, localText)))
            else -> {
                val text = client.download(file) ?: return
                val remoteAt = RemoteServerStore.parse(text)?.first ?: 0L
                if (remoteAt > remoteServers.updatedAt || neverEdited) {
                    remoteServers.importJson(text)
                    state.put("servers", JSONObject().put("hash", remoteServers.exportJson().hashCode().toString()).put("tag", r?.tag.orEmpty()))
                } else {
                    state.put("servers", JSONObject().put("hash", hash).put("tag", client.upload(file, localText)))
                }
            }
        }
    }

    private suspend fun upload(session: ChatSessionEntity, file: String): String =
        client.upload(file, ChatFile.encode(session, messageDao.getMessagesForSession(session.id)))

    private suspend fun importChat(file: String): Boolean {
        val parsed = client.download(file)?.let { ChatFile.decode(it) } ?: return false
        store(parsed)
        return true
    }

    /** Writes a downloaded chat over the local one: upsert (no cascade), then drop messages it no longer has. */
    private suspend fun store(parsed: ChatFile.Parsed) {
        val existing = sessionDao.getSessionById(parsed.session.id)
        sessionDao.upsertSession(parsed.session.copy(contextUsed = existing?.contextUsed ?: 0, contextTotal = existing?.contextTotal ?: 0))
        messageDao.upsertMessages(parsed.messages)
        val keep = parsed.messages.mapTo(HashSet()) { it.id }
        val gone = messageDao.getMessagesForSession(parsed.session.id).map { it.id }.filterNot { it in keep }
        gone.chunked(500).forEach { messageDao.deleteMessagesByIds(parsed.session.id, it) }
    }

    private fun fingerprint(s: ChatSessionEntity, m: MessageFingerprint?): String =
        "${s.updatedAt}|${s.title.hashCode()}|${s.modelNamesJson.hashCode()}|${m?.cnt ?: 0}|${m?.len ?: 0}|${m?.maxTs ?: 0}|${m?.varLen ?: 0}"

    private fun entry(fp: String, tag: String) = JSONObject().put("fp", fp).put("tag", tag)

    private fun readState(): JSONObject = runCatching { JSONObject(stateFile.readText()) }.getOrElse { JSONObject() }

    private fun writeState(o: JSONObject) {
        val tmp = File(stateFile.parentFile, stateFile.name + ".tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(stateFile)
    }

    companion object {
        const val CHATS = "chats"
        const val SETTINGS = "settings"
    }
}
