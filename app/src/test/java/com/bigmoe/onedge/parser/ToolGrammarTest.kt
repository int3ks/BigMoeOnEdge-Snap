package com.bigmoe.onedge.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolGrammarTest {
    @Test
    fun buildsSortedNameAlternativesAndCallLimit() {
        val g = ToolGrammar.build(listOf("run_shell", "read_file", "run_shell"), maxCalls = 4)
        assertTrue(g.startsWith("root ::= call (ws call){0,3} ws\n"))
        assertTrue(g.contains("name ::= \"\\\"read_file\\\"\" | \"\\\"run_shell\\\"\"\n"))
        assertTrue(g.contains("\"<tool_call>\""))
        assertTrue(g.contains("\"</tool_call>\""))
        assertTrue(g.contains("ws ::= [ \\t\\n\\r]{0,20}"))
    }

    @Test
    fun skipsUnsafeNamesAndIsEmptyWithoutTools() {
        val g = ToolGrammar.build(listOf("ok_tool", "bad name", "quote\"", ""), maxCalls = 1)
        assertTrue(g.contains("name ::= \"\\\"ok_tool\\\"\"\n"))
        assertFalse(g.contains("bad name"))
        assertTrue(g.startsWith("root ::= call (ws call){0,0} ws"))
        assertEquals("", ToolGrammar.build(emptyList(), 4))
        assertEquals("", ToolGrammar.build(listOf("bad name"), 4))
    }
}
