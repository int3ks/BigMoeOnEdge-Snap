package com.bigmoe.onedge.tools

import com.bigmoe.onedge.workspace.Workspace
import com.bigmoe.onedge.workspace.WorkspaceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicInteger

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

    /**
     * Runs `bash -c [script] [args...]` (so args[0] is $0, args[1] is $1, ...). null = no result within [timeoutMs]
     * (the command may still be running).
     */
    suspend fun run(script: String, workdir: String, timeoutMs: Long, args: List<String> = emptyList()): TermuxResult?

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
            "Returns exit code, stdout and stderr. Use it on your own for anything that needs running code: Python " +
            "scripts, converting or creating files (audio, images, archives), installing what is missing, checking results. " +
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

    override suspend fun execute(arguments: JSONObject): String = execute(arguments, ToolContext.NONE)

    override suspend fun execute(arguments: JSONObject, ctx: ToolContext): String {
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
        // Housekeeping next to the files: the command's pid (to stop it) and a copy of its output (shown live).
        val runDir = File(root, Workspace.AGENT_DIR).apply { mkdirs() }
        val id = "run-" + nextRun.incrementAndGet() + "-" + (System.currentTimeMillis() % 100_000)
        val pidFile = File(runDir, "$id.pid")
        val logFile = File(runDir, "$id.log")
        val label = describe(arguments)
        try {
            val result = coroutineScope {
                val poll = launch {
                    var shown = ""
                    while (true) {
                        delay(PROGRESS_INTERVAL_MS)
                        val last = lastLine(logFile)
                        if (last.isNotEmpty() && last != shown) {
                            shown = last
                            ctx.onProgress("$label…\n› $last") // the chat shows the elapsed time itself
                        }
                    }
                }
                try {
                    backend.run(WRAPPER, dir.path, seconds * 1000L, listOf("bmoe-run", command, runDir.path, id))
                } finally {
                    poll.cancel()
                }
            }
            if (result == null) {
                val stopped = stop(pidFile, dir)
                return "No result after ${seconds}s, so the command was " + (if (stopped) "stopped" else "abandoned (it could not be confirmed stopped)") +
                    ". Output so far:\n" + tail(logFile, 1500).ifBlank { "(none)" } +
                    "\nFor long jobs, raise timeout_seconds (up to $MAX_TIMEOUT_S) or run it in steps."
            }
            return format(result)
        } catch (e: ShellBackend.Unavailable) {
            throw ToolInputException(e.message ?: "Termux is not reachable")
        } catch (e: CancellationException) {
            // The cancel button or Stop: end the command in Termux too, so nothing keeps running unseen.
            withContext(NonCancellable) { stop(pidFile, dir) }
            throw e
        } finally {
            pidFile.delete()
            logFile.delete()
            runDir.delete() // only when empty
        }
    }

    /** Ends the wrapper and every process below it. True when Termux confirmed the stop script ran. */
    private suspend fun stop(pidFile: File, dir: File): Boolean {
        if (!pidFile.isFile) return false
        return try {
            backend.run(KILL_TREE, dir.path, STOP_TIMEOUT_MS, listOf("bmoe-stop", pidFile.path)) != null
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_S = 120
        const val MAX_TIMEOUT_S = 1800
        private const val GRACE_MS = 15_000L
        private const val STOP_TIMEOUT_MS = 8_000L
        private const val PROGRESS_INTERVAL_MS = 1_000L
        private val nextRun = AtomicInteger(0)

        /**
         * Runs the user's command ($1) in its own bash, records the wrapper's pid in $2/$3.pid so it can be stopped,
         * and copies stdout and stderr into $2/$3.log as they come (for the live status line) while Termux still gets
         * both streams separately. The command's exit code is the wrapper's.
         */
        internal const val WRAPPER =
            "echo \$\$ > \"\$2/\$3.pid\"\n" +
                "bash -c \"\$1\" > >(tee -a \"\$2/\$3.log\") 2> >(tee -a \"\$2/\$3.log\" >&2)\n" +
                "rc=\$?\n" +
                "rm -f \"\$2/\$3.pid\"\n" +
                "exit \$rc\n"

        /** Stops the process tree whose root pid is in the file $1: TERM, then KILL a second later. */
        internal const val KILL_TREE =
            "p=\$(cat \"\$1\" 2>/dev/null) || exit 0\n" +
                "kids() { ps -A -o pid,ppid 2>/dev/null | while read a b; do [ \"\$b\" = \"\$1\" ] && echo \"\$a\"; done; }\n" +
                "tree() { local c; for c in \$(kids \"\$1\"); do tree \"\$c\"; done; echo \"\$1\"; }\n" +
                "all=\$(tree \"\$p\")\n" +
                "kill -TERM \$all 2>/dev/null; sleep 1; kill -KILL \$all 2>/dev/null\n" +
                "rm -f \"\$1\"; exit 0\n"

        private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

        /** The last non-empty line of [f] (progress bars rewrite a line with \r, so that counts as a break too). */
        internal fun lastLine(f: File, max: Int = 100): String {
            val t = tail(f, 2048)
            val line = t.split('\n', '\r').map { ANSI.replace(it, "").trim() }.lastOrNull { it.isNotEmpty() }.orEmpty()
            return if (line.length > max) line.take(max - 1) + "…" else line
        }

        /** The last [maxBytes] of [f] as text, "" when it does not exist. */
        internal fun tail(f: File, maxBytes: Int): String = try {
            if (!f.isFile) "" else RandomAccessFile(f, "r").use { r ->
                val len = r.length()
                val n = minOf(len, maxBytes.toLong()).toInt()
                r.seek(len - n)
                val b = ByteArray(n)
                r.readFully(b)
                String(b, Charsets.UTF_8)
            }
        } catch (_: Exception) {
            ""
        }

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
