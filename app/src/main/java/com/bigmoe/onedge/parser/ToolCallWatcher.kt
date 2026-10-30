package com.bigmoe.onedge.parser

/**
 * Notices, while the answer streams in, that the model is writing a tool call, and which one: the call is hidden from
 * the chat, so without this a long create_file looks like a frozen "Reading…". Returns a new label only when it
 * changes ("Writing a tool call…" → "Writing create_file: make_audio.py…"), else null.
 */
class ToolCallWatcher {
    private var openAt = -1
    private var scanFrom = 0
    private var last: String? = null

    fun update(raw: CharSequence): String? {
        if (openAt < 0) {
            val i = indexOf(raw, ToolProtocol.CALL_OPEN, maxOf(0, scanFrom - ToolProtocol.CALL_OPEN.length))
            scanFrom = raw.length
            if (i < 0) return null
            openAt = i
        }
        val closeAt = indexOf(raw, ToolProtocol.CALL_CLOSE, openAt)
        if (closeAt >= 0) { // finished; look for the next one after it
            openAt = -1
            last = null
            scanFrom = closeAt + ToolProtocol.CALL_CLOSE.length
            return null
        }
        val head = raw.subSequence(openAt, minOf(raw.length, openAt + 400)).toString()
        val name = NAME.find(head)?.groupValues?.get(1)
        val target = if (name == null) null else (PATH.find(head) ?: COMMAND.find(head))?.groupValues?.get(1)
        val label = when {
            name == null -> "Writing a tool call…"
            target.isNullOrBlank() -> "Writing $name…"
            else -> "Writing $name: ${target.take(60)}…"
        }
        if (label == last) return null
        last = label
        return label
    }

    private fun indexOf(s: CharSequence, what: String, from: Int): Int = s.indexOf(what, from.coerceIn(0, s.length))

    private companion object {
        val NAME = Regex("\"name\"\\s*:\\s*\"([A-Za-z0-9_.-]{1,64})\"")
        val PATH = Regex("\"path\"\\s*:\\s*\"([^\"\\n]{1,120})\"")
        val COMMAND = Regex("\"command\"\\s*:\\s*\"([^\"\\n]{1,120})\"")
    }
}
