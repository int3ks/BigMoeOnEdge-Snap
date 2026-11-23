package com.bigmoe.onedge.tools

import org.json.JSONArray
import org.json.JSONObject

/** A question the model asks the user in the middle of an answer: [options] as buttons, free text is always possible. */
data class UserQuestion(val question: String, val options: List<String>)

fun interface UserQuestionHandler {
    /** Suspends until the user answers. The answer text, or null when the user dismissed the question. */
    suspend fun ask(question: UserQuestion): String?
}

/**
 * ask_user: the model asks the user a question and waits for the answer (a chosen option or the user's own text),
 * which comes back as the tool result. [handler] is the chat's question card (GenerationManager); null = no chat
 * screen to ask on. Not offered to API server clients (the server uses the client's own tools, never these).
 */
class AskUserTool(private val handler: () -> UserQuestionHandler?) : Tool {
    override val name = "ask_user"
    override val category = ToolCategory.HELP
    override val description =
        "Ask the user a question and wait for the answer, when you cannot go on well without their decision or a fact " +
            "only they know. Offer 2 to 4 short answer options; the user may also write their own answer."
    override val parametersSchema: JSONObject = toolSchema(
        listOf(
            Triple("question", "string", "the question, short, in the user's language"),
            Triple("options", "array", "2 to 4 short answer options (strings)")
        ),
        listOf("question", "options")
    ).also { schema ->
        schema.getJSONObject("properties").getJSONObject("options")
            .put("items", JSONObject().put("type", "string")).put("minItems", 2).put("maxItems", MAX_OPTIONS)
    }

    override fun describe(arguments: JSONObject) = "Waiting for your answer"

    /** The user may take as long as they like. */
    override fun timeoutMs(arguments: JSONObject): Long = 24L * 3600L * 1000L

    override suspend fun execute(arguments: JSONObject): String {
        val question = arguments.requireString("question").trim()
        if (question.isEmpty()) throw ToolInputException("\"question\" is empty")
        val options = parseOptions(arguments.opt("options"))
        val ask = handler() ?: return "Error: there is no chat screen to ask the user on. Go on without asking."
        val answer = ask.ask(UserQuestion(question, options))
            ?: return "The user dismissed the question without answering. Go on without this information " +
                "(make a sensible choice and say which), or ask differently only if you really cannot continue."
        return "The user answered: $answer"
    }

    companion object {
        const val MAX_OPTIONS = 4

        /**
         * The options as the model gave them: a JSON array (the normal case), or a string holding a JSON array or one
         * option per line / separated by "|" or ";" (small models do that). Blank and repeated ones dropped, at most 4.
         */
        internal fun parseOptions(raw: Any?): List<String> {
            val list = when (raw) {
                null, JSONObject.NULL -> emptyList()
                is JSONArray -> (0 until raw.length()).map { raw.opt(it)?.toString().orEmpty() }
                else -> {
                    val s = raw.toString().trim()
                    runCatching { JSONArray(s) }.getOrNull()?.let { a -> (0 until a.length()).map { a.opt(it)?.toString().orEmpty() } }
                        ?: s.split('\n', '|', ';')
                }
            }
            return list.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_OPTIONS)
        }
    }
}

val ASK_USER_TOOL_NAMES = setOf("ask_user")
