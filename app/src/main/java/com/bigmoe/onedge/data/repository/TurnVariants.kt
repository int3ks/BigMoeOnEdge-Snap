package com.bigmoe.onedge.data.repository

import com.bigmoe.onedge.data.local.entity.ChatMessageEntity
import com.bigmoe.onedge.data.local.entity.MessageRole
import org.json.JSONArray
import org.json.JSONObject

/** One earlier answer to a user message: the messages of that turn as they were stored, in order. */
data class TurnVariant(val slot: Int, val messages: List<ChatMessageEntity>)

/**
 * The answers Regenerate kept for one user message, stored as JSON on that message (`variants_json`).
 * Slots number the answers in the order they were made (0 = first). The live answer (the messages in the chat)
 * has slot [active]; [archived] holds all others, so there are always `archived.size + 1` answers and the slots
 * are 0 until that count.
 */
data class TurnVariants(val active: Int, val archived: List<TurnVariant>) {

    val total: Int get() = archived.size + 1

    /** Regenerate: the live answer [turn] becomes a variant; the coming answer gets the next slot. */
    fun archiveActive(turn: List<ChatMessageEntity>): TurnVariants =
        TurnVariants(active = total, archived = archived + TurnVariant(active, turn))

    /** Switch: the live answer [turn] becomes a variant and [chosen] becomes the live one. */
    fun swap(turn: List<ChatMessageEntity>, chosen: TurnVariant): TurnVariants =
        TurnVariants(
            active = chosen.slot,
            archived = (archived.filter { it.slot != chosen.slot } + TurnVariant(active, turn)).sortedBy { it.slot }
        )

    fun encode(): String = JSONObject().apply {
        put("active", active)
        put("archived", JSONArray().apply {
            archived.forEach { v ->
                put(JSONObject().apply {
                    put("slot", v.slot)
                    put("messages", JSONArray().apply { v.messages.forEach { put(it.toJson()) } })
                })
            }
        })
    }.toString()

    companion object {
        /** Null for no variants or unreadable JSON. */
        fun decode(json: String?): TurnVariants? {
            if (json.isNullOrBlank()) return null
            return runCatching {
                val o = JSONObject(json)
                val arr = o.getJSONArray("archived")
                val list = (0 until arr.length()).map { i ->
                    val v = arr.getJSONObject(i)
                    val msgs = v.getJSONArray("messages")
                    TurnVariant(v.getInt("slot"), (0 until msgs.length()).map { msgFromJson(msgs.getJSONObject(it)) })
                }
                TurnVariants(o.getInt("active"), list)
            }.getOrNull()?.takeIf { it.archived.isNotEmpty() }
        }

        private fun ChatMessageEntity.toJson() = JSONObject().apply {
            put("id", id); put("role", role.name); put("content", content); put("timestamp", timestamp)
            reasoning?.let { put("reasoning", it) }
            tokensPerSecond?.let { put("tps", it) }
            prefillMs?.let { put("prefillMs", it) }
            generatedTokens?.let { put("generated", it) }
            cacheHitPct?.let { put("cacheHit", it) }
            generationElapsedMs?.let { put("elapsedMs", it) }
            contextUsed?.let { put("ctxUsed", it) }
            contextTotal?.let { put("ctxTotal", it) }
            note?.let { put("note", it) }
            prefillTokens?.let { put("prefillTokens", it) }
            thinkingMs?.let { put("thinkingMs", it) }
            toolMs?.let { put("toolMs", it) }
            runInfo?.let { put("runInfo", it) }
        }

        private fun msgFromJson(o: JSONObject): ChatMessageEntity {
            fun str(k: String) = if (o.has(k)) o.getString(k) else null
            fun lng(k: String) = if (o.has(k)) o.getLong(k) else null
            fun int(k: String) = if (o.has(k)) o.getInt(k) else null
            fun dbl(k: String) = if (o.has(k)) o.getDouble(k) else null
            return ChatMessageEntity(
                id = o.getString("id"),
                sessionId = "",
                role = MessageRole.valueOf(o.getString("role")),
                content = o.getString("content"),
                timestamp = o.getLong("timestamp"),
                reasoning = str("reasoning"),
                tokensPerSecond = dbl("tps"),
                prefillMs = lng("prefillMs"),
                generatedTokens = int("generated"),
                cacheHitPct = dbl("cacheHit"),
                generationElapsedMs = lng("elapsedMs"),
                contextUsed = int("ctxUsed"),
                contextTotal = int("ctxTotal"),
                note = str("note"),
                prefillTokens = int("prefillTokens"),
                thinkingMs = lng("thinkingMs"),
                toolMs = lng("toolMs"),
                runInfo = str("runInfo")
            )
        }
    }
}
