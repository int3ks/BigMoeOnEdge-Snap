package com.bigmoe.onedge.parser

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolCallWatcherTest {
    /** Feeds [text] in [chunk]-sized deltas and returns the labels in the order they were reported. */
    private fun labels(text: String, chunk: Int = 3): List<String> {
        val w = ToolCallWatcher()
        val raw = StringBuilder()
        val out = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            raw.append(text, i, minOf(text.length, i + chunk))
            w.update(raw)?.let { out.add(it) }
            i += chunk
        }
        return out
    }

    @Test
    fun reportsNameThenTargetOncePerCall() {
        val text = "Sure.\n<tool_call>{\"name\": \"create_file\", \"arguments\": {\"path\": \"story.txt\", \"content\": \"Once upon a time\"}}</tool_call>" +
            "<tool_call>{\"name\": \"run_shell\", \"arguments\": {\"command\": \"python3 tts.py\"}}</tool_call>"
        assertEquals(
            listOf(
                "Writing a tool call…", "Writing create_file…", "Writing create_file: story.txt…",
                "Writing a tool call…", "Writing run_shell…", "Writing run_shell: python3 tts.py…"
            ),
            labels(text)
        )
    }

    @Test
    fun plainTextReportsNothing() {
        assertEquals(emptyList<String>(), labels("No tools needed here, the answer is 42.", chunk = 1))
    }

    @Test
    fun sameCallTwiceIsReportedTwice() {
        val call = "<tool_call>{\"name\": \"run_shell\", \"arguments\": {\"command\": \"ls\"}}</tool_call>"
        assertEquals(2, labels(call + call, chunk = 5).count { it == "Writing run_shell: ls…" })
    }
}
