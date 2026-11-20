package com.bigmoe.onedge.sync

import com.bigmoe.onedge.data.local.dao.ArtifactDao
import com.bigmoe.onedge.data.local.dao.AttachmentDao
import com.bigmoe.onedge.data.local.dao.ChatMessageDao
import com.bigmoe.onedge.data.local.dao.ChatSessionDao
import com.bigmoe.onedge.data.local.dao.MessageFingerprint
import com.bigmoe.onedge.data.local.entity.ChatSessionEntity
import org.json.JSONObject
import java.io.File

/**
 * The chats as files: <workspace>/chats/<title>.json ([ChatFile]) are the source, the Room database is the fast index.
 *
 * [reconcile] compares both sides with what it saw last time (per chat: file name, file size+mtime, DB fingerprint):
 *  - changed in the app → the file is (re)written; a new title renames the file;
 *  - changed as a file (PC, OneDrive) → it is read into the database; the file name is the chat's title;
 *  - both → the newer change wins; deleted on one side (unchanged on the other) → deleted on the other too.
 * Titles are unique by file name: a second "Foo" becomes "Foo (2)" in the folder and in the app.
 * On the first run (no state) every chat of the database is written out, nothing is deleted.
 */
class ChatFolder(
    private val dirProvider: () -> File,
    private val stateFile: File,
    private val sessionDao: ChatSessionDao,
    private val messageDao: ChatMessageDao,
    private val attachmentDao: AttachmentDao,
    private val artifactDao: ArtifactDao,
    /** The chats' own switches (they travel in the chat file); null = not part of the file. */
    private val composer: com.bigmoe.onedge.data.local.datastore.ChatComposerDataStore? = null
) {
    val dir: File get() = File(dirProvider(), FOLDER)

    /** Returns true when the database changed (a file was read in or a chat deleted). */
    suspend fun reconcile(): Boolean {
        val dir = dir.apply { mkdirs() }
        var state = runCatching { JSONObject(stateFile.readText()) }.getOrElse { JSONObject() }
        // Another folder (workspace moved or switched) or an empty one: start over like the first run, so chats
        // missing there are written out instead of being taken as deleted.
        val rootKey = dir.canonicalPath
        val hasFiles = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.isNotEmpty() == true
        if (state.optString(ROOT_KEY) != rootKey || !hasFiles) state = JSONObject()
        state.remove(ROOT_KEY)
        // Files: id -> (file, sig). Unknown or changed files are parsed to learn their id.
        val nameToId = HashMap<String, String>()
        state.keys().forEach { id -> state.optJSONObject(id)?.optString("name")?.let { nameToId[it] = id } }
        data class F(val file: File, val sig: String, var parsed: ChatFile.Parsed? = null)
        val files = HashMap<String, F>()
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.forEach { f ->
            val sig = sig(f)
            val known = nameToId[f.name]?.takeIf { state.optJSONObject(it)?.optString("sig") == sig }
            val parsed = if (known == null) ChatFile.decode(runCatching { f.readText() }.getOrDefault("")) else null
            val id = known ?: parsed?.session?.id ?: return@forEach
            val other = files[id]
            if (other == null || f.lastModified() > other.file.lastModified()) files[id] = F(f, sig, parsed)
        }

        val sessions = sessionDao.getAllSessions().associateBy { it.id }
        if (sessions.values.any { it.generationState == "RUNNING" }) return false
        val fps = messageDao.fingerprints().associateBy { it.sessionId }
        val taken = HashSet<String>().apply { files.values.forEach { add(it.file.name.lowercase()) } }
        var dbChanged = false

        val ids = LinkedHashSet<String>().apply { addAll(sessions.keys); addAll(files.keys); state.keys().forEach { add(it) } }
        for (id in ids) {
            val db = sessions[id]
            val f = files[id]
            val s = state.optJSONObject(id)
            val fp = db?.let { fingerprint(it, fps[id], composer?.getTogglesRaw(id)) }
            val dbCh = db != null && (s == null || s.optString("fp") != fp)
            val fileCh = f != null && (s == null || s.optString("sig") != f.sig || s.optString("name") != f.file.name)
            when {
                db != null && f == null -> {
                    if (s != null && !dbCh) {
                        sessionDao.deleteSessionById(id); dbChanged = true
                        state.remove(id)
                    } else {
                        write(db, null, taken, state)
                    }
                }
                db == null && f != null -> {
                    if (s != null && !fileCh) {
                        f.file.delete(); taken.remove(f.file.name.lowercase())
                        state.remove(id)
                    } else if (read(f.file, f.parsed, state)) dbChanged = true
                }
                db != null && f != null -> {
                    if (!dbCh && !fileCh) continue
                    val fileWins = when {
                        fileCh && !dbCh -> true
                        dbCh && !fileCh -> false
                        else -> {
                            val p = f.parsed ?: ChatFile.decode(f.file.readText())
                            f.parsed = p
                            p != null && p.changedAt > ChatFile.changedAt(db, messageDao.getMessagesForSession(id))
                        }
                    }
                    if (fileWins) {
                        if (read(f.file, f.parsed, state)) dbChanged = true
                    } else {
                        write(db, f.file, taken, state)
                    }
                }
                else -> state.remove(id)
            }
        }
        state.put(ROOT_KEY, rootKey)
        val tmp = File(stateFile.parentFile, stateFile.name + ".tmp")
        tmp.writeText(state.toString())
        tmp.renameTo(stateFile)
        return dbChanged
    }

    /** Writes the chat to its file (renamed when the title changed); a numbered title goes back into the DB. */
    private suspend fun write(db: ChatSessionEntity, current: File?, taken: MutableSet<String>, state: JSONObject) {
        current?.let { taken.remove(it.name.lowercase()) }
        val base = fileStem(db.title)
        var name = "$base.json"
        var n = 2
        while (name.lowercase() in taken) name = "$base ($n).json".also { n++ }
        taken.add(name.lowercase())
        val title = name.removeSuffix(".json")
        var session = db
        if (title != db.title) {
            sessionDao.updateSessionTitle(db.id, title)
            session = db.copy(title = title)
        }
        val messages = messageDao.getMessagesForSession(db.id)
        val toggles = composer?.getTogglesRaw(db.id)
        val text = ChatFile.encode(session, messages, attachmentDao.getForSession(db.id), artifactDao.getForSession(db.id), toggles)
        val target = File(dir, name)
        val tmp = File(dir, ".$name.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
        if (current != null && current.canonicalPath != target.canonicalPath) current.delete()
        val fps = messageDao.fingerprints().firstOrNull { it.sessionId == db.id }
        state.put(db.id, JSONObject().put("name", name).put("sig", sig(target)).put("fp", fingerprint(session, fps, toggles)))
    }

    /** Reads a chat file into the database; its file name is the title. */
    private suspend fun read(file: File, parsedOrNull: ChatFile.Parsed?, state: JSONObject): Boolean {
        val parsed = parsedOrNull ?: ChatFile.decode(runCatching { file.readText() }.getOrDefault("")) ?: return false
        val id = parsed.session.id
        val existing = sessionDao.getSessionById(id)
        val session = parsed.session.copy(
            title = file.name.removeSuffix(".json"),
            contextUsed = existing?.contextUsed ?: 0,
            contextTotal = existing?.contextTotal ?: 0
        )
        sessionDao.upsertSession(session)
        messageDao.upsertMessages(parsed.messages)
        val keep = parsed.messages.mapTo(HashSet()) { it.id }
        messageDao.getMessagesForSession(id).map { it.id }.filterNot { it in keep }.chunked(500).forEach {
            messageDao.deleteMessagesByIds(id, it)
        }
        parsed.attachments.forEach { attachmentDao.insert(it) }
        parsed.artifacts.forEach { artifactDao.insert(it) }
        if (parsed.toggles != null) composer?.saveTogglesRaw(id, parsed.toggles)
        val fps = messageDao.fingerprints().firstOrNull { it.sessionId == id }
        val stored = sessionDao.getSessionById(id) ?: session
        state.put(id, JSONObject().put("name", file.name).put("sig", sig(file)).put("fp", fingerprint(stored, fps, composer?.getTogglesRaw(id))))
        return true
    }

    companion object {
        const val FOLDER = "chats"
        private const val ROOT_KEY = "_root"

        fun sig(f: File): String = "${f.length()}|${f.lastModified()}"

        fun fingerprint(s: ChatSessionEntity, m: MessageFingerprint?, toggles: String? = null): String =
            "${toggles?.hashCode() ?: 0}|${s.updatedAt}|${s.title.hashCode()}|${s.modelNamesJson.hashCode()}|${m?.cnt ?: 0}|${m?.len ?: 0}|${m?.maxTs ?: 0}|${m?.varLen ?: 0}"

        /** A title as a file name that Windows and Android accept. */
        fun fileStem(title: String): String {
            val clean = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trimEnd('.', ' ')
            return clean.take(100).trimEnd('.', ' ').ifBlank { "Chat" }
        }
    }
}
