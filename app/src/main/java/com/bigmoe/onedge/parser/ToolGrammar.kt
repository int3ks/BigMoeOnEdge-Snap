package com.bigmoe.onedge.parser

/**
 * A GBNF grammar (llama.cpp's grammar format) for the app's text tool protocol, used as a LAZY grammar: the engine
 * samples freely until the model writes [TRIGGER] (`<tool_call>`), and from there on every token must continue a
 * valid call:
 *
 *   <tool_call>{"name": "<one of the enabled tools>", "arguments": {<any JSON object>}}</tool_call>
 *
 * up to [ToolProtocol]'s per-round call limit, separated by whitespace, after which only the end of the turn is
 * allowed. So a call can no longer come out as broken JSON, an unknown tool name, a missing closing tag or a call
 * followed by invented "results". String values must be valid JSON strings (a raw newline inside "content" has to be
 * written as \n), which is what [ToolProtocol.parseCall] wants anyway.
 *
 * The engine only applies it on rounds where the model is not expected to reason first (see
 * docs/tool-calling.md): a `<tool_call>` mentioned inside a reasoning span would otherwise trigger it there.
 */
object ToolGrammar {
    /** Literal text (and regex: it has no special characters) that switches the lazy grammar on. */
    const val TRIGGER = ToolProtocol.CALL_OPEN

    /** Longest run of whitespace allowed between grammar elements; unbounded whitespace could loop forever. */
    private const val MAX_WS = 20

    /** Tool names are always plain identifiers; anything else is skipped rather than escaped into the grammar. */
    private val NAME_OK = Regex("^[A-Za-z0-9_.-]{1,64}$")

    /**
     * The grammar for [toolNames] (the tools enabled this turn) and at most [maxCalls] calls per answer, or "" when
     * no usable name is left (then no grammar is applied at all).
     */
    fun build(toolNames: Collection<String>, maxCalls: Int): String {
        val names = toolNames.filter { NAME_OK.matches(it) }.distinct().sorted()
        if (names.isEmpty()) return ""
        val extra = (maxCalls - 1).coerceIn(0, 15)
        val nameAlternatives = names.joinToString(" | ") { "\"\\\"$it\\\"\"" }
        return buildString {
            append("root ::= call (ws call){0,").append(extra).append("} ws\n")
            append("call ::= \"").append(ToolProtocol.CALL_OPEN).append("\" ws \"{\" ws \"\\\"name\\\"\" ws \":\" ws name ws \",\" ws ")
            append("\"\\\"arguments\\\"\" ws \":\" ws object ws \"}\" ws \"").append(ToolProtocol.CALL_CLOSE).append("\"\n")
            append("name ::= ").append(nameAlternatives).append('\n')
            append(JSON_RULES)
            append("ws ::= [ \\t\\n\\r]{0,").append(MAX_WS).append("}\n")
        }
    }

    /** JSON value rules, after llama.cpp's grammars/json.gbnf, with bounded number lengths. */
    private const val JSON_RULES =
        "value ::= object | array | string | number | \"true\" | \"false\" | \"null\"\n" +
            "object ::= \"{\" ws ( string ws \":\" ws value ws ( \",\" ws string ws \":\" ws value ws )* )? \"}\"\n" +
            "array ::= \"[\" ws ( value ws ( \",\" ws value ws )* )? \"]\"\n" +
            "string ::= \"\\\"\" char* \"\\\"\"\n" +
            "char ::= [^\"\\\\\\x7F\\x00-\\x1F] | \"\\\\\" ( [\"\\\\/bfnrt] | \"u\" [0-9a-fA-F]{4} )\n" +
            "number ::= \"-\"? ( \"0\" | [1-9] [0-9]{0,17} ) ( \".\" [0-9]{1,17} )? ( [eE] [-+]? [0-9]{1,3} )?\n"
}
