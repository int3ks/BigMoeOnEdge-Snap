package com.bigmoe.onedge.tools

import org.json.JSONObject

enum class ToolCategory { FILES, CODE, WEB, UTILITIES, TERMUX }

/**
 * Which chat message a tool call belongs to (so created files can be linked to it). [onProgress] takes a short
 * live status line while a long-running tool works (e.g. the last line a Termux command printed); [onOutput] the
 * running command's whole output so far (its end), which the chat shows folded. Both thread-safe.
 * [onProgress] stays the last parameter, so `ToolContext(session, message) { line -> ... }` sets it.
 */
data class ToolContext(
    val sessionId: String,
    val messageId: String,
    val onOutput: (String) -> Unit = {},
    val onProgress: (String) -> Unit = {}
) {
    companion object {
        val NONE = ToolContext("", "")
    }
}

/** Something the user must approve before a tool runs. [diff] is unified-diff text for edits. */
data class ConfirmationRequest(
    val title: String,
    val message: String,
    val diff: String? = null,
    val destructive: Boolean = false
)

fun interface ConfirmationHandler {
    /** Suspends until the user decides. true = approved. */
    suspend fun confirm(request: ConfirmationRequest): Boolean
}

/** A problem with the model's arguments or the target; the message is given back to the model verbatim. */
class ToolInputException(message: String) : Exception(message)

/**
 * One capability the model can call. To add a tool: implement this, register it in AppContainer
 * (ToolManager.registerTool). Nothing else needs to change: the system prompt, the call parsing, the
 * approval dialog and the "tool is running" indicator are all driven by these members.
 */
interface Tool {
    val name: String
    val description: String
    val parametersSchema: JSONObject
    val category: ToolCategory get() = ToolCategory.FILES

    /**
     * True for tools that only read from the network and touch nothing local, so several calls in one round can run
     * at the same time. Anything that writes, or depends on the order of calls, keeps the default (false).
     */
    val parallelSafe: Boolean get() = false

    /** Short text for the UI while the tool runs, e.g. "Reading notes.md". */
    fun describe(arguments: JSONObject): String = name

    /**
     * How long [execute] may take for these [arguments], or null for the ToolManager's default (20 s). Tools that
     * legitimately run for minutes (a package install in Termux) say so here.
     */
    fun timeoutMs(arguments: JSONObject): Long? = null

    /** The time limit to show next to the elapsed time while the tool runs, in seconds; null = none shown. */
    fun limitSeconds(arguments: JSONObject): Int? = null

    /** Called once when an agent run ends (finished, stopped or failed), e.g. to release what the tool held. */
    suspend fun onRunFinished() {}

    /** Non-null = the user must approve first. May read files (called off the main thread). */
    fun confirmation(arguments: JSONObject): ConfirmationRequest? = null

    suspend fun execute(arguments: JSONObject): String

    /** Variant that knows the chat; tools that create or change files override this. */
    suspend fun execute(arguments: JSONObject, ctx: ToolContext): String = execute(arguments)
}

/** Schema helper: `schema("path" to "string", ...)` with required names. */
fun toolSchema(properties: List<Triple<String, String, String>>, required: List<String>): JSONObject {
    val props = JSONObject()
    for ((name, type, desc) in properties) {
        props.put(name, JSONObject().put("type", type).put("description", desc))
    }
    return JSONObject().put("type", "object").put("properties", props)
        .put("required", org.json.JSONArray(required))
}
