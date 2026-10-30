package com.bigmoe.onedge.data.local.datastore

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bigmoe.onedge.attachments.PendingAttachment
import com.bigmoe.onedge.data.local.entity.AttachmentKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

private val Context.chatComposerStore by preferencesDataStore(name = "chat_composer")

/**
 * The switches under the chat input, kept per chat: an agent chat keeps its tools, a story chat keeps them off.
 * A chat without saved switches uses the app-wide settings (which are also where a new chat starts).
 */
data class ChatToggles(
    val thinking: Boolean,
    val webSearch: Boolean,
    val files: Boolean,
    val code: Boolean,
    val utilities: Boolean,
    val termux: Boolean
) {
    fun applyTo(s: EngineSettings): EngineSettings = s.copy(
        thinkingEnabled = thinking, isWebSearchEnabled = webSearch, toolsFilesEnabled = files,
        toolsCodeEnabled = code, toolsUtilitiesEnabled = utilities, toolsTermuxEnabled = termux
    )

    fun encode(): String = JSONObject()
        .put("thinking", thinking).put("webSearch", webSearch).put("files", files)
        .put("code", code).put("utilities", utilities).put("termux", termux).toString()

    companion object {
        fun from(s: EngineSettings) = ChatToggles(
            s.thinkingEnabled, s.isWebSearchEnabled, s.toolsFilesEnabled, s.toolsCodeEnabled, s.toolsUtilitiesEnabled, s.toolsTermuxEnabled
        )

        /** null for anything unreadable; a switch missing in an older entry takes [fallback]'s value. */
        fun decode(raw: String?, fallback: EngineSettings): ChatToggles? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val o = JSONObject(raw)
                val d = from(fallback)
                ChatToggles(
                    o.optBoolean("thinking", d.thinking), o.optBoolean("webSearch", d.webSearch),
                    o.optBoolean("files", d.files), o.optBoolean("code", d.code),
                    o.optBoolean("utilities", d.utilities), o.optBoolean("termux", d.termux)
                )
            }.getOrNull()
        }
    }
}

/**
 * Persistent unsent composer state. This is UI state rather than chat history, so DataStore avoids a Room
 * schema migration and does not use the database's destructive fallback.
 */
class ChatComposerDataStore(private val context: Context) {
    private val mutex = Mutex()

    private fun safeKey(prefix: String, sessionKey: String): androidx.datastore.preferences.core.Preferences.Key<String> =
        stringPreferencesKey("$prefix:${sessionKey.replace("%", "%25").replace(":", "%3A")}")

    suspend fun getDraft(sessionKey: String): String = mutex.withLock {
        context.chatComposerStore.data.first()[safeKey("draft", sessionKey)].orEmpty()
    }

    suspend fun saveDraft(sessionKey: String, text: String) = mutex.withLock {
        context.chatComposerStore.edit { prefs ->
            val key = safeKey("draft", sessionKey)
            if (text.isEmpty()) prefs.remove(key) else prefs[key] = text
        }
    }

    suspend fun getPending(sessionKey: String): List<PendingAttachment> = mutex.withLock {
        decodePending(context.chatComposerStore.data.first()[safeKey("pending", sessionKey)].orEmpty())
    }

    suspend fun savePending(sessionKey: String, items: List<PendingAttachment>) = mutex.withLock {
        context.chatComposerStore.edit { prefs ->
            val key = safeKey("pending", sessionKey)
            if (items.isEmpty()) prefs.remove(key) else prefs[key] = encodePending(items)
        }
    }

    /** The chat's own switches, or null when it has none (it then follows the app-wide settings). */
    suspend fun getToggles(sessionKey: String, fallback: EngineSettings): ChatToggles? = mutex.withLock {
        ChatToggles.decode(context.chatComposerStore.data.first()[safeKey("toggles", sessionKey)], fallback)
    }

    suspend fun saveToggles(sessionKey: String, toggles: ChatToggles?) = mutex.withLock {
        context.chatComposerStore.edit { prefs ->
            val key = safeKey("toggles", sessionKey)
            if (toggles == null) prefs.remove(key) else prefs[key] = toggles.encode()
        }
    }

    /** A new chat got its id with the first message: its switches move from the "new chat" slot to it. */
    suspend fun moveToggles(fromKey: String, toKey: String) = mutex.withLock {
        context.chatComposerStore.edit { prefs ->
            val from = safeKey("toggles", fromKey)
            prefs[from]?.let { prefs[safeKey("toggles", toKey)] = it }
            prefs.remove(from)
        }
    }

    /** A deleted chat: draft, attachments and switches. */
    suspend fun forgetChat(sessionKey: String) = mutex.withLock {
        context.chatComposerStore.edit { prefs ->
            prefs.remove(safeKey("draft", sessionKey))
            prefs.remove(safeKey("pending", sessionKey))
            prefs.remove(safeKey("toggles", sessionKey))
        }
    }

    /** After a message was sent: the unsent text and attachments go, the chat's switches stay. */
    suspend fun clearSession(sessionKey: String) = mutex.withLock {
        context.chatComposerStore.edit { prefs ->
            prefs.remove(safeKey("draft", sessionKey))
            prefs.remove(safeKey("pending", sessionKey))
        }
    }

    suspend fun clearAll() = mutex.withLock {
        context.chatComposerStore.edit { prefs ->
            prefs.asMap().keys.filter { it.name.startsWith("draft:") || it.name.startsWith("pending:") || it.name.startsWith("toggles:") }
                .forEach { prefs.remove(it) }
        }
    }

    private fun encodePending(items: List<PendingAttachment>): String {
        val array = JSONArray()
        items.forEach { a ->
            array.put(JSONObject().apply {
                put("id", a.id)
                put("kind", a.kind.name)
                put("name", a.name)
                put("path", a.path)
                put("mime", a.mime)
                put("sizeBytes", a.sizeBytes)
            })
        }
        return array.toString()
    }

    private fun decodePending(raw: String): List<PendingAttachment> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            buildList(a.length()) {
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    add(
                        PendingAttachment(
                            id = o.getString("id"),
                            kind = AttachmentKind.valueOf(o.getString("kind")),
                            name = o.getString("name"),
                            path = o.getString("path"),
                            mime = o.optString("mime", "application/octet-stream"),
                            sizeBytes = o.optLong("sizeBytes", 0L)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
