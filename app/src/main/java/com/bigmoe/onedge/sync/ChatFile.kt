package com.bigmoe.onedge.sync

import com.bigmoe.onedge.data.local.entity.ChatMessageEntity
import com.bigmoe.onedge.data.local.entity.ChatSessionEntity
import com.bigmoe.onedge.data.local.entity.MessageRole
import org.json.JSONArray
import org.json.JSONObject

/**
 * The BigMoe² chat file: one JSON per chat, readable on the PC and meant to be shared with other front ends later
 * (LLamaGUI). Format "bigmoe-chat" version 1:
 *
 *  { format, version, id, title, createdAt, changedAt, modelPath, modelNames[], messages[] }
 *
 * messages[]: id, role (USER/ASSISTANT/SYSTEM/TOOL/CONTEXT), content, timestamp and, when present, reasoning,
 * runInfo (object), variants (Regenerate's earlier answers, array/object) and the turn's telemetry.
 * [changedAt] decides a conflict: the newer copy wins. Readers must keep fields they do not know.
 */
object ChatFile {
    const val FORMAT = "bigmoe-chat"
    const val VERSION = 1

    data class Parsed(val session: ChatSessionEntity, val messages: List<ChatMessageEntity>, val changedAt: Long)

    fun changedAt(session: ChatSessionEntity, messages: List<ChatMessageEntity>): Long =
        maxOf(session.updatedAt, messages.maxOfOrNull { it.timestamp } ?: 0L)

    fun encode(session: ChatSessionEntity, messages: List<ChatMessageEntity>): String {
        val arr = JSONArray()
        messages.forEach { m ->
            val o = JSONObject()
                .put("id", m.id)
                .put("role", m.role.name)
                .put("content", m.content)
                .put("timestamp", m.timestamp)
            m.reasoning?.let { o.put("reasoning", it) }
            m.tokensPerSecond?.let { o.put("tokensPerSecond", it) }
            m.prefillMs?.let { o.put("prefillMs", it) }
            m.generatedTokens?.let { o.put("generatedTokens", it) }
            m.cacheHitPct?.let { o.put("cacheHitPct", it) }
            m.generationElapsedMs?.let { o.put("generationElapsedMs", it) }
            m.contextUsed?.let { o.put("contextUsed", it) }
            m.contextTotal?.let { o.put("contextTotal", it) }
            m.note?.let { o.put("note", it) }
            m.prefillTokens?.let { o.put("prefillTokens", it) }
            m.thinkingMs?.let { o.put("thinkingMs", it) }
            m.toolMs?.let { o.put("toolMs", it) }
            m.runInfo?.let { o.put("runInfo", asJson(it)) }
            m.variantsJson?.let { o.put("variants", asJson(it)) }
            arr.put(o)
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("id", session.id)
            .put("title", session.title)
            .put("createdAt", session.createdAt)
            .put("updatedAt", session.updatedAt)
            .put("changedAt", changedAt(session, messages))
            .put("modelPath", session.modelPath ?: JSONObject.NULL)
            .put("modelNames", runCatching { JSONArray(session.modelNamesJson) }.getOrElse { JSONArray() })
            .put("messages", arr)
            .toString(1)
    }

    fun decode(text: String): Parsed? = runCatching {
        val o = JSONObject(text)
        if (o.optString("format") != FORMAT) return null
        val id = o.getString("id")
        val session = ChatSessionEntity(
            id = id,
            title = o.optString("title", "Chat"),
            createdAt = o.optLong("createdAt"),
            updatedAt = o.optLong("updatedAt"),
            modelPath = o.optString("modelPath").takeIf { it.isNotBlank() && !o.isNull("modelPath") },
            modelNamesJson = o.optJSONArray("modelNames")?.toString() ?: "[]"
        )
        val arr = o.optJSONArray("messages") ?: JSONArray()
        val messages = (0 until arr.length()).map { i ->
            val m = arr.getJSONObject(i)
            ChatMessageEntity(
                id = m.getString("id"),
                sessionId = id,
                role = runCatching { MessageRole.valueOf(m.getString("role")) }.getOrDefault(MessageRole.USER),
                content = m.optString("content"),
                timestamp = m.optLong("timestamp"),
                reasoning = m.str("reasoning"),
                tokensPerSecond = m.dbl("tokensPerSecond"),
                prefillMs = m.lng("prefillMs"),
                generatedTokens = m.int("generatedTokens"),
                cacheHitPct = m.dbl("cacheHitPct"),
                generationElapsedMs = m.lng("generationElapsedMs"),
                contextUsed = m.int("contextUsed"),
                contextTotal = m.int("contextTotal"),
                note = m.str("note"),
                prefillTokens = m.int("prefillTokens"),
                thinkingMs = m.lng("thinkingMs"),
                toolMs = m.lng("toolMs"),
                runInfo = m.raw("runInfo"),
                variantsJson = m.raw("variants")
            )
        }
        Parsed(session, messages, o.optLong("changedAt", changedAt(session, messages)))
    }.getOrNull()

    /** Stored JSON strings go into the file as real JSON (readable); anything else stays a string. */
    private fun asJson(s: String): Any = runCatching { JSONObject(s) }.recoverCatching { JSONArray(s) }.getOrDefault(s)

    private fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) optString(k) else null
    private fun JSONObject.dbl(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k) else null
    private fun JSONObject.lng(k: String): Long? = if (has(k) && !isNull(k)) optLong(k) else null
    private fun JSONObject.int(k: String): Int? = if (has(k) && !isNull(k)) optInt(k) else null
    private fun JSONObject.raw(k: String): String? = if (has(k) && !isNull(k)) get(k).toString() else null
}
