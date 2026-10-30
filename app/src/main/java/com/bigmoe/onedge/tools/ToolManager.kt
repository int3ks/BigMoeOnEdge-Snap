package com.bigmoe.onedge.tools

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

class ToolManager(
    private val toolTimeoutMs: Long = 20_000L,
    maxResultChars: Int = 3_500,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    /**
     * Longest result handed back to the model. Every character becomes prompt tokens the phone has to process before
     * the model can continue, so this is the biggest lever on how long a tool round takes. Set per run from the settings.
     */
    @Volatile
    var maxResultChars: Int = maxResultChars

    private val registeredTools = LinkedHashMap<String, Tool>()

    /** Shows the approval dialog. Without one, tools that need approval are refused (never run silently). */
    @Volatile
    var confirmationHandler: ConfirmationHandler? = null

    /**
     * Settings → Agent → Approval "Allow everything": calls that would ask (edits, deletes, Termux commands) run
     * without the dialog. Set per run from the settings; off by default.
     */
    @Volatile
    var autoApprove: Boolean = false

    /** Tool executions in flight (more than one when parallel-safe calls run side by side). */
    private val running = ConcurrentHashMap.newKeySet<Deferred<String>>()
    private val cancelledByUser = ConcurrentHashMap.newKeySet<Deferred<String>>()

    /**
     * The chat's cancel button (and Stop): ends every tool that is running right now. Each one returns
     * "Cancelled by the user" to the model; the tool cleans up after itself (run_shell stops the Termux command).
     * Returns false when nothing was running.
     */
    fun cancelRunningTools(): Boolean {
        val now = running.toList()
        now.forEach { cancelledByUser.add(it); it.cancel() }
        return now.isNotEmpty()
    }

    fun hasRunningTool(): Boolean = running.isNotEmpty()

    fun registerTool(tool: Tool) {
        registeredTools[tool.name] = tool
    }

    fun getTool(name: String): Tool? = registeredTools[name]

    fun hasTools(): Boolean = registeredTools.isNotEmpty()

    fun isParallelSafe(name: String): Boolean = registeredTools[name]?.parallelSafe == true

    fun toolNames(): Set<String> = registeredTools.keys.toSet()

    /** Compact one-line signature: `read_file(path, start_line?, end_line?)`. */
    fun signature(tool: Tool): String {
        val props = tool.parametersSchema.optJSONObject("properties") ?: JSONObject()
        val required = HashSet<String>()
        tool.parametersSchema.optJSONArray("required")?.let { arr -> for (i in 0 until arr.length()) required.add(arr.optString(i)) }
        val names = props.keys().asSequence().toList()
        return tool.name + "(" + names.joinToString(", ") { if (it in required) it else "$it?" } + ")"
    }

    /**
     * Text appended to the system prompt: the tools in [names] (all when null), the call format and a few
     * rules. Empty when no tool is selected.
     */
    fun getToolsDescriptionPrompt(names: Set<String>? = null): String {
        val selected = registeredTools.values.filter { names == null || it.name in names }
        if (selected.isEmpty()) return ""
        val b = StringBuilder("You can call tools. Available tools:\n")
        selected.forEach { tool ->
            b.append("- ${signature(tool)}: ${tool.description}\n")
            val props = tool.parametersSchema.optJSONObject("properties")
            if (props != null) {
                // Compact: this text is read again whenever the conversation has to be processed from the start,
                // so every character counts. No line for a parameter whose name says it all; the type only when it
                // is not the obvious "string".
                props.keys().forEach { k ->
                    val p = props.optJSONObject(k)
                    val desc = p?.optString("description").orEmpty()
                    if (desc.isNotBlank()) {
                        val type = p?.optString("type").orEmpty()
                        b.append("    ").append(k)
                        if (type.isNotEmpty() && type != "string") b.append(" (").append(type).append(')')
                        b.append(": ").append(desc).append('\n')
                    }
                }
            }
        }
        b.append(
            "\nTo use a tool, reply with ONLY this and nothing else:\n" +
                "<tool_call>{\"name\": \"tool_name\", \"arguments\": {...}}</tool_call>\n" +
                "The result comes back in a <tool_response> message; then continue. Rules: " +
                "do not invent tool results; do not call a tool when you can answer directly; " +
                "never say a file was created, changed or deleted unless the tool result confirms it; " +
                "read a file before editing it; file paths are relative to the user's workspace."
        )
        if (selected.any { it.name == "create_file" }) {
            b.append(
                " When the user asks you to write a document, program, page or config, create it with create_file " +
                    "instead of pasting it into the chat, then tell them briefly what you made."
            )
        }
        if (selected.any { it.category == ToolCategory.UTILITIES }) {
            b.append(
                " Use calculate for any arithmetic beyond the trivial instead of working it out in your head, " +
                    "get_datetime whenever the answer depends on today's date or the time, and convert_units for unit conversions."
            )
        }
        selected.firstOrNull { it.name == "run_shell" }?.let { shell ->
            b.append(agentShellHint(runCatching { shell.promptNote() }.getOrNull()))
        }
        return b.toString()
    }

    companion object {
        /** In the tool result when the user pressed Cancel; the model then decides how to go on. */
        const val CANCELLED_BY_USER = "Cancelled by the user"

        /**
         * Appended when run_shell is enabled: where the model runs and how to work as an agent there. The environment
         * comes first and is spelled out, because models otherwise assume what they know from training (Windows and
         * PowerShell, a Linux server with sudo, a built-in Python sandbox). [environment] is what the app found in
         * Termux on this device (RunShellTool.promptNote), or null when it has not looked yet.
         */
        fun agentShellHint(environment: String?): String =
            "\n\nENVIRONMENT (read this first): you run on an Android tablet. It is NOT Windows, macOS or a Linux server: " +
                "there is no PowerShell or cmd, no sudo, no /root, /home/user or /mnt/data, and no built-in Python " +
                "interpreter or code sandbox. Everything you run goes through ONE tool, run_shell, which runs a bash " +
                "command in the Termux app (a Linux userland for Android with its own packages). Python, pip and every " +
                "other program exist only there, so to run Python you call run_shell, for example:\n" +
                "<tool_call>{\"name\": \"run_shell\", \"arguments\": {\"command\": \"python3 make_pdf.py\"}}</tool_call>\n" +
                (if (environment != null) "Checked in Termux on this device: $environment. Do not install what is listed " +
                    "here as present.\n"
                else "If unsure whether Python is there, check with python3 --version; only if it is missing: " +
                    "pkg install -y python.\n") +
                "Every command starts in the user's workspace folder: files you make with create_file are there, files a " +
                "command writes show up in the workspace. Use relative paths.\n\n" +
                "HOW TO WORK: use run_shell on your own, without asking first, whenever a task needs more than text: " +
                "running code, creating or converting files (audio, images, documents, archives), installing and using " +
                "programs, downloading, checking a result. Never tell the user to run commands and never just show code " +
                "they would have to run. Work in steps: one call, read its result, then the next, until the task is really " +
                "done. Before installing anything, check whether it is already there and install only what is missing, in " +
                "the same command, e.g. command -v ffmpeg >/dev/null || pkg install -y ffmpeg ; " +
                "python3 -c 'import gtts' 2>/dev/null || pip install -q gtts . Programs come from pkg (Termux's apt); " +
                "Python libraries with C code (Pillow, numpy, ...) too, e.g. pkg install -y python-pillow, because pip " +
                "would compile them for a long time; other Python libraries from pip. If pkg fails, run pkg update -y " +
                "first. Never start interactive programs (input is closed, so a prompt fails at once). " +
                "Keep output small, it costs context: quiet options (pip install -q, pkg install -y), no whole files or " +
                "long listings, end with a short check (e.g. && echo OK). You only get the last " +
                "${RunShellTool.OUTPUT_BUDGET} characters of a command's output; for more, write it to a file and look at " +
                "it with tail or grep. After a tool call, stop and wait: the app sends the result; never write " +
                "<tool_response> yourself and never claim a result before the call that produces it. Report success only " +
                "when the exit code and the output show it, and name the file(s) by their workspace path (e.g. story.pdf)."
    }

    /** Never throws (except coroutine cancellation): failures come back as "Error: ..." text for the model. */
    suspend fun executeToolCall(
        toolName: String,
        argumentsJson: String,
        ctx: ToolContext = ToolContext.NONE
    ): String {
        val tool = registeredTools[toolName] ?: return "Error: Tool '$toolName' not found. " +
            "Available tools: ${registeredTools.keys.joinToString(", ")}."
        var timeoutMs = toolTimeoutMs
        var startedNs = System.nanoTime()
        return try {
            val args = if (argumentsJson.isBlank()) JSONObject() else JSONObject(argumentsJson)

            // 1. approval (outside the timeout: the user may take as long as they like)
            val request = if (autoApprove) null else withContext(ioDispatcher) { tool.confirmation(args) }
            if (request != null) {
                val handler = confirmationHandler
                    ?: return "Error: this action needs the user's approval, but no approval screen is available."
                if (!handler.confirm(request)) return "The user declined this action. Nothing was changed."
            }

            // 2. run, as its own child job so the cancel button can end just this tool and not the whole turn
            timeoutMs = tool.timeoutMs(args) ?: toolTimeoutMs
            startedNs = System.nanoTime()
            val result = coroutineScope {
                val job = async(ioDispatcher) { withTimeout(timeoutMs) { tool.execute(args, ctx) } }
                running.add(job)
                try {
                    job.await()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    if (e !is TimeoutCancellationException && cancelledByUser.contains(job) && currentCoroutineContext().isActive) {
                        val s = (System.nanoTime() - startedNs) / 1_000_000_000L
                        return@coroutineScope "Error: $CANCELLED_BY_USER after ${s}s because it seemed stuck. This is NOT a " +
                            "refusal: the user still wants the task done. The command did NOT complete, so whatever it was " +
                            "installing or writing is missing or incomplete; do not report success. Find out from the output " +
                            "below where it hung (a prompt waiting for input, a slow download or compile, a server that never " +
                            "exits) and fix that cause, then go on with the same task. Ask the user only if the cause is unclear." +
                            (tool.takeCancelDetails()?.let { "\n$it" } ?: "")
                    }
                    throw e
                } finally {
                    running.remove(job)
                    cancelledByUser.remove(job)
                }
            }
            clip(result, maxResultChars)
        } catch (e: TimeoutCancellationException) {
            "Error: tool '$toolName' timed out after ${timeoutMs / 1000}s."
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: ToolInputException) {
            "Error: ${e.message}"
        } catch (e: Exception) {
            "Error executing tool '$toolName': ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * Cuts [text] to about [limit] characters at the last line break that keeps at least 80% of it, never in the middle
     * of an emoji, and says how much was left out so the model can ask for it (a line range, a narrower query) instead of
     * the app reading everything up front.
     */
    internal fun clip(text: String, limit: Int): String {
        if (text.length <= limit) return text
        var cut = limit
        val nl = text.lastIndexOf('\n', limit)
        if (nl >= limit * 8 / 10) cut = nl
        if (cut > 0 && Character.isHighSurrogate(text[cut - 1])) cut--
        return text.substring(0, cut).trimEnd() + "\n[truncated: ${text.length - cut} more characters not shown]"
    }

    /** UI label for a call, e.g. "Reading notes.md". Never throws. */
    fun describeCall(toolName: String, argumentsJson: String): String {
        val tool = registeredTools[toolName] ?: return toolName
        return try { tool.describe(if (argumentsJson.isBlank()) JSONObject() else JSONObject(argumentsJson)) } catch (_: Exception) { toolName }
    }

    /** The time limit the chat shows next to a running call, in seconds, or null. */
    fun limitSecondsOf(toolName: String, argumentsJson: String): Int? {
        val tool = registeredTools[toolName] ?: return null
        return try { tool.limitSeconds(if (argumentsJson.isBlank()) JSONObject() else JSONObject(argumentsJson)) } catch (_: Exception) { null }
    }

    /** End of an agent run: every tool may release what it held (run_shell: Termux's wake lock). Never throws. */
    suspend fun finishRun() {
        for (tool in registeredTools.values) {
            try { tool.onRunFinished() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
        }
    }
}
