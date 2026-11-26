package com.bigmoe.onedge.core

import com.bigmoe.onedge.data.local.entity.MessageRole
import com.bigmoe.onedge.parser.ToolProtocol

/**
 * Pure logic (no Android, no JNI) for automatic conversation compaction. Ported from BigMoeOnEdge+ 0.7.0
 * (hamodexd217), changed so the summary reuses the KV cache: see docs/auto-compaction.md.
 *
 * When a chat gets close to the context size, the oldest messages are folded into a short summary by the loaded
 * model itself. Nothing is deleted: the summary is stored as one extra message of role [MessageRole.SUMMARY] and the
 * original messages stay on screen. What changes is what the engine is shown: the summary (in the system prompt)
 * plus the messages after the ones it covers.
 *
 * Storage needs no schema change. A SUMMARY message's content is one header line naming the last message it covers,
 * then the summary text: `bmoe-summary upto=<message id>\n<text>`. It sits where the compaction happened, so a later
 * edit that removes everything after a message also removes the summaries made after it.
 */
object Compaction {

    /** Compaction needs room to work in; below this the summary would cost more than it frees. */
    const val MIN_CTX = 1536

    const val MIN_THRESHOLD_PCT = 40
    const val MAX_THRESHOLD_PCT = 70
    const val DEFAULT_THRESHOLD_PCT = 60

    /** The newest messages kept word for word: this share of the context. */
    private const val TAIL_PCT = 25

    /** Folding fewer tokens than this frees less than the summary costs. */
    const val MIN_HEAD_TOKENS = 300

    /** Longest stretch of one message handed to the summarizer (a tool result or attached file can be huge). */
    private const val CLIP_CHARS = 4000

    /** Characters of the first kept message quoted in the in-context request, so the model knows where to stop. */
    private const val ANCHOR_CHARS = 120

    private const val HEADER = "bmoe-summary upto="

    // ------------------------------------------------------------------ storage format

    data class Stored(val uptoId: String, val text: String)

    fun encode(uptoId: String, text: String): String = "$HEADER$uptoId\n${text.trim()}"

    /** Null when [content] is not a summary message (or names no covered message). */
    fun decode(content: String): Stored? {
        if (!content.startsWith(HEADER)) return null
        val newline = content.indexOf('\n')
        val id = (if (newline < 0) content.substring(HEADER.length) else content.substring(HEADER.length, newline)).trim()
        if (id.isEmpty()) return null
        val text = if (newline < 0) "" else content.substring(newline + 1).trim()
        return Stored(id, text)
    }

    /** The text of a SUMMARY message for display and export (the header is internal). */
    fun displayText(content: String): String = decode(content)?.text ?: content

    /** What a chat's stored messages mean to the engine: the summary text (empty = none) and the messages still sent. */
    data class Resolved(val summary: String, val history: List<HistoryMessage>)

    /**
     * Applies the newest usable summary in [stored] (oldest first, as saved). A summary is usable when its text is
     * not empty and the message it names exists before it; otherwise the one before it is tried, and without any
     * the whole conversation is sent. SUMMARY messages themselves are never part of the returned history.
     */
    fun resolve(stored: List<HistoryMessage>): Resolved {
        for (i in stored.indices.reversed()) {
            val message = stored[i]
            if (message.role != MessageRole.SUMMARY) continue
            val decoded = decode(message.content) ?: continue
            if (decoded.text.isEmpty()) continue
            val upto = stored.indexOfFirst { it.id == decoded.uptoId }
            if (upto < 0 || upto >= i) continue
            return Resolved(decoded.text, stored.subList(upto + 1, stored.size).filter { it.role != MessageRole.SUMMARY })
        }
        return Resolved("", stored.filter { it.role != MessageRole.SUMMARY })
    }

    // ------------------------------------------------------------------ when and what

    /** Token count the engine would have to hold for [system], [turns] and the pending [prompt] (conservative estimate). */
    fun promptTokens(system: String, turns: List<ChatTurn>, prompt: String): Int =
        ContextPlanner.estimateTokens(system) + turns.sumOf { turnTokens(it) } + ContextPlanner.estimateTokens(prompt) + 16

    fun turnTokens(turn: ChatTurn): Int = ContextPlanner.estimateTokens(turn.content) + 8

    /** Tokens of the longest summary the model is asked for. */
    fun summaryTokens(nCtx: Int): Int = (nCtx / 8).coerceIn(192, 1024)

    /** True when [usedTokens] has passed [thresholdPct] of the context and the context is big enough to compact in. */
    fun needed(usedTokens: Int, nCtx: Int, thresholdPct: Int): Boolean =
        nCtx >= MIN_CTX && usedTokens > nCtx.toLong() * thresholdPct.coerceIn(MIN_THRESHOLD_PCT, MAX_THRESHOLD_PCT) / 100

    /**
     * Where the kept tail starts: turns before the returned index are folded into the summary, the rest stay.
     *
     * Only the first [historyRoles].size turns can be folded (the later ones belong to the turn that is running now),
     * and a cut never separates a user message from the context or tool result stored right after it: the tail starts
     * at a USER message, or at the end of the stored history. The tail is the longest run of newest turns that fits
     * [TAIL_PCT] percent of [nCtx]; when even the running turn alone is bigger, all stored history is folded.
     * Returns 0 when there is nothing older to fold.
     */
    fun splitIndex(turns: List<ChatTurn>, historyRoles: List<MessageRole>, nCtx: Int): Int {
        val histCount = minOf(historyRoles.size, turns.size)
        if (histCount <= 0) return 0
        val budget = nCtx.toLong() * TAIL_PCT / 100
        // tail[i] = tokens of turns[i..]
        val tail = LongArray(turns.size + 1)
        for (i in turns.indices.reversed()) tail[i] = tail[i + 1] + turnTokens(turns[i])
        for (i in 1..histCount) {
            val allowed = i == histCount || historyRoles[i] == MessageRole.USER
            if (allowed && tail[i] <= budget) return i
        }
        return histCount
    }

    /** True when the turns before [cut] hold enough text for a summary to be worth its cost. */
    fun worthFolding(turns: List<ChatTurn>, cut: Int): Boolean =
        cut > 0 && turns.subList(0, cut).sumOf { turnTokens(it) } >= MIN_HEAD_TOKENS

    // ------------------------------------------------------------------ prompts

    /** System prompt of the separate summarizer call (used when the engine does not hold the chat). */
    const val SUMMARIZER_SYSTEM =
        "You write faithful, compact summaries of conversations for another assistant that will continue them. " +
            "Output only the summary text."

    private fun keepRules(maxWords: Int): String =
        "Keep: what the user wants and any constraints or preferences, decisions made, facts, names, numbers, " +
            "file names and code identifiers, results that still matter, and anything left unfinished. " +
            "Leave out greetings and filler. Write plain text (short paragraphs or dashes) in the language of the " +
            "conversation, at most about $maxWords words."

    private fun earlierSummary(previous: String): String =
        if (previous.isBlank()) "" else
            " A summary of even older messages already exists. Fold it into your new summary so that one summary covers everything:\n" +
                "<earlier_summary>\n" + previous.trim() + "\n</earlier_summary>\n"

    /**
     * Separate summarizer call: the old messages are sent as the history of their own short conversation, this is
     * the request after them. [previous] is the summary of even older messages, if any.
     */
    fun summaryPrompt(previous: String, maxWords: Int): String = buildString {
        append("Summarize the conversation above so it can be continued without the original messages.")
        append(earlierSummary(previous).ifEmpty { "\n" })
        append(keepRules(maxWords))
        append(" Do not answer or continue the conversation.")
    }

    /**
     * In-context call: the request is appended to the running chat, so the engine reuses everything it already holds
     * and only reads this text (no second prefill of the old messages). The model sees the whole chat, so it is told
     * where the part to summarize ends: [keptFrom] is the start of the first message that stays word for word
     * (null = everything so far is folded).
     */
    fun inContextPrompt(previous: String, maxWords: Int, keptFrom: String?): String = buildString {
        append("[Context maintenance, not a message from the user] The context is getting full. Write a summary of ")
        if (keptFrom.isNullOrBlank()) {
            append("this conversation so far")
        } else {
            append("the earlier part of this conversation: everything BEFORE the user message that begins with \"")
            append(anchor(keptFrom))
            append("\". That message and everything after it stay available word for word, so do not summarize them")
        }
        append(". The summary replaces the original messages for you later.")
        append(earlierSummary(previous).ifEmpty { "\n" })
        append(keepRules(maxWords))
        append(" Do not call tools, do not answer or continue the conversation: output only the summary.")
    }

    /** The first words of a kept message, on one line, as the in-context request quotes it. */
    fun anchor(text: String): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        if (flat.length <= ANCHOR_CHARS) return flat
        var head = flat.take(ANCHOR_CHARS)
        if (head.isNotEmpty() && Character.isHighSurrogate(head.last())) head = head.dropLast(1)
        return "$head…"
    }

    /** The summary as the model reads it: a block at the end of the system prompt. */
    fun systemBlock(summary: String): String =
        "Earlier part of this conversation, summarized (those messages were removed to free up context). " +
            "Treat it as reliable background:\n" + summary.trim()

    /** One message of the part being folded, cut to a size the summarizer can afford. */
    fun clip(text: String, max: Int = CLIP_CHARS): String {
        if (text.length <= max) return text
        var head = text.take(max * 7 / 10)
        if (head.isNotEmpty() && Character.isHighSurrogate(head.last())) head = head.dropLast(1)
        var tail = text.takeLast(max * 3 / 10)
        if (tail.isNotEmpty() && Character.isLowSurrogate(tail.first())) tail = tail.drop(1)
        return head + "\n[...]\n" + tail
    }

    private val THINK_BLOCK = Regex("(?s)<think>.*?</think>")

    /**
     * The model's answer as a summary, or null when it is unusable: empty, a reasoning block that never closed
     * (it ran out of tokens while thinking), a tool call instead of a summary, or too short to mean anything.
     * Reasoning blocks are removed, and an answer that ran past what was asked for is cut at [summaryTokens] worth
     * of text.
     */
    fun cleanSummary(raw: String, nCtx: Int): String? {
        var text = THINK_BLOCK.replace(raw, "").trim()
        if (text.contains("<think>") || text.contains("</think>")) return null
        if (text.contains(ToolProtocol.CALL_OPEN)) return null
        text = text.removePrefix("Summary:").removePrefix("SUMMARY:").trim()
        if (text.length < 20) return null
        val cap = summaryTokens(nCtx) * 5
        if (text.length > cap) {
            var cut = text.take(cap)
            if (cut.isNotEmpty() && Character.isHighSurrogate(cut.last())) cut = cut.dropLast(1)
            text = cut.trimEnd()
        }
        return text
    }
}
