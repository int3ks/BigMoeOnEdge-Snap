package com.bigmoe.onedge.tools

import com.bigmoe.onedge.workspace.Workspace
import com.bigmoe.onedge.workspace.WorkspaceException
import org.json.JSONObject
import java.io.File

/** What a shell command returned. Plain data, so [RunShellTool] can be tested on the JVM. */
data class TermuxResult(
    val stdout: String,
    val stderr: String,
    /** null when the command could not be started at all (see [err] / [errmsg]). */
    val exitCode: Int?,
    /** Termux's internal error code; -1 (Activity.RESULT_OK) = no internal error. */
    val err: Int,
    val errmsg: String?,
    /** Termux keeps at most ~100 KB of each stream (the END of it); these say how long it really was. */
    val stdoutOriginalLength: Int? = null,
    val stderrOriginalLength: Int? = null
)

/** Where commands run. Implemented by com.bigmoe.onedge.termux.TermuxBridge; faked in tests. */
interface ShellBackend {
    fun isInstalled(): Boolean
    fun hasPermission(): Boolean

    /** null = no result within [timeoutMs] (the command may still be running). */
    suspend fun run(command: String, workdir: String, timeoutMs: Long): TermuxResult?

    /** The command could not be handed over at all; the message is for the model and the user. */
    class Unavailable(message: String) : Exception(message)
}

/**
 * run_shell: one bash command in Termux, in the workspace (or a folder inside it). Termux can only reach the
 * workspace when it lives in the shared folder (Settings → Workspace), so [sharedWorkspaceRoot] returns the root only
 * in that case. Every call asks for approval unless Settings → Agent → Approval is "Allow everything".
 */
class RunShellTool(
    private val backend: ShellBackend,
    private val workspace: Workspace,
    private val sharedWorkspaceRoot: () -> File?
) : Tool {
    override val name = "run_shell"
    override val category = ToolCategory.TERMUX
    override val description =
        "Run one bash command in Termux, a Linux environment on this device, with the workspace as the current folder. " +
            "Returns exit code, stdout and stderr. Use it to run Python or other scripts, install what is missing " +
            "(pkg install -y python; pip install <package>), convert files (ffmpeg) and check results. " +
            "Non-interactive only: no editors, prompts or programs that wait for input."
    override val parametersSchema: JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties", JSONObject()
                .put("command", JSONObject().put("type", "string").put("description", "bash command line, e.g. \"python3 make_audio.py\""))
                .put("cwd", JSONObject().put("type", "string").put("description", "folder inside the workspace to run in (default: the workspace root)"))
                .put(
                    "timeout_seconds", JSONObject().put("type", "integer")
                        .put("description", "how long to wait for the result, $DEFAULT_TIMEOUT_S-$MAX_TIMEOUT_S (default $DEFAULT_TIMEOUT_S); use more for installs")
                )
        )
        .put("required", org.json.JSONArray(listOf("command")))

    override fun describe(arguments: JSONObject): String =
        "Termux: " + arguments.optString("command").lineSequence().firstOrNull().orEmpty().take(60)

    override fun timeoutMs(arguments: JSONObject): Long = waitSeconds(arguments) * 1000L + GRACE_MS

    override fun confirmation(arguments: JSONObject): ConfirmationRequest {
        val cwd = arguments.optString("cwd").ifBlank { "workspace" }
        return ConfirmationRequest(
            title = "Run in Termux?",
            message = "$ ${arguments.optString("command")}\n\nFolder: $cwd\n\nThe command runs with all of Termux's rights: " +
                "it can change or delete files in Termux and in shared storage, and use the network."
        )
    }

    override suspend fun execute(arguments: JSONObject): String {
        val command = arguments.optString("command").trim()
        if (command.isEmpty()) throw ToolInputException("command is empty")
        val root = sharedWorkspaceRoot() ?: throw ToolInputException(
            "the workspace is in the app's private storage, which Termux cannot reach. Tell the user to turn on " +
                "Settings → Diagnostics & workspace → \"Shared workspace folder\"."
        )
        if (!backend.isInstalled()) throw ToolInputException(
            "Termux is not installed. Tell the user to install Termux from F-Droid or GitHub (not Google Play)."
        )
        if (!backend.hasPermission()) throw ToolInputException(
            "BigMoeOnEdge² may not run commands in Termux yet. Tell the user to grant it in Settings → Tools → Termux."
        )
        val dir = try {
            workspace.resolve(arguments.optString("cwd"))
        } catch (e: WorkspaceException) {
            throw ToolInputException(e.message ?: "bad cwd")
        }
        if (!dir.isDirectory) throw ToolInputException("no such folder in the workspace: ${workspace.relative(dir)}")
        // Resolve against the workspace as it is now; it may have been switched since sharedWorkspaceRoot() was read.
        if (!dir.path.startsWith(root.path)) throw ToolInputException("the workspace changed while the command was prepared; try again")

        val seconds = waitSeconds(arguments)
        val result = try {
            backend.run(command, dir.path, seconds * 1000L)
        } catch (e: ShellBackend.Unavailable) {
            throw ToolInputException(e.message ?: "Termux is not reachable")
        } ?: return "No result after ${seconds}s. The command may still be running in Termux; it cannot be stopped from " +
            "here. For long jobs, run them so that they write their output to a file, then check the file."
        return format(result)
    }

    companion object {
        const val DEFAULT_TIMEOUT_S = 120
        const val MAX_TIMEOUT_S = 1800
        private const val GRACE_MS = 15_000L

        fun waitSeconds(arguments: JSONObject): Int =
            arguments.optInt("timeout_seconds", DEFAULT_TIMEOUT_S).let { if (it <= 0) DEFAULT_TIMEOUT_S else it }
                .coerceIn(5, MAX_TIMEOUT_S)

        /** The model's view of a result: exit code first, then the streams; Termux's own failures explained. */
        fun format(r: TermuxResult): String {
            if (r.err != -1 && r.exitCode == null) {
                val msg = r.errmsg.orEmpty().trim()
                val hint = when {
                    msg.contains("allow-external-apps", ignoreCase = true) ->
                        " Tell the user to enable it in Termux: ${TERMUX_ALLOW_LINE}"
                    msg.contains("permission", ignoreCase = true) || msg.contains("denied", ignoreCase = true) ->
                        " If the folder is the problem, the user has to run termux-setup-storage in Termux once."
                    else -> ""
                }
                return "Error: Termux could not run the command (err ${r.err}): ${msg.take(1500).ifEmpty { "no details" }}.$hint"
            }
            val b = StringBuilder()
            b.append("exit code: ").append(r.exitCode ?: "unknown").append('\n')
            appendStream(b, "stdout", r.stdout, r.stdoutOriginalLength)
            appendStream(b, "stderr", r.stderr, r.stderrOriginalLength)
            if (r.stdout.isBlank() && r.stderr.isBlank()) b.append("(no output)\n")
            if (r.err != -1) b.append("Termux reported err ${r.err}: ").append(r.errmsg.orEmpty().take(500)).append('\n')
            return b.toString().trimEnd()
        }

        private fun appendStream(b: StringBuilder, label: String, text: String, original: Int?) {
            if (text.isBlank()) return
            b.append("--- ").append(label)
            if (original != null && original > text.length) b.append(" (last ${text.length} of $original characters)")
            b.append(" ---\n").append(text.trimEnd()).append('\n')
        }

        private const val TERMUX_ALLOW_LINE =
            "mkdir -p ~/.termux && echo 'allow-external-apps = true' >> ~/.termux/termux.properties && termux-reload-settings"
    }
}

val TERMUX_TOOL_NAMES = setOf("run_shell")
