package com.bigmoe.onedge.tools

import com.bigmoe.onedge.workspace.Workspace
import com.bigmoe.onedge.workspace.WorkspaceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
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
     * Runs `bash -c script args...` (so the first arg is $0, the second $1, and so on). null = no result within [timeoutMs]
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
    private val sharedWorkspaceRoot: () -> File?,
    private val timing: Timing = Timing()
) : Tool {
    /**
     * [startGraceMs]: how long Termux may take to start a command (its pid file appears) before the app reports that
     * Termux is not responding. [resultGraceMs]: how long to wait for Termux's answer after the command has finished
     * (its exit-code file appears) before the app reads the result from the files itself.
     */
    data class Timing(val pollMs: Long = 1_000L, val startGraceMs: Long = 20_000L, val resultGraceMs: Long = 5_000L)

    /** termux-wake-lock is held from the first command of an agent run until [onRunFinished]. */
    private val wakeLockHeld = AtomicBoolean(false)

    /** Output of the command the user just cancelled, for [takeCancelDetails] (one run_shell runs at a time). */
    @Volatile private var cancelledOutput: String? = null

    override fun takeCancelDetails(): String? = cancelledOutput?.also { cancelledOutput = null }

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

    override fun limitSeconds(arguments: JSONObject): Int = waitSeconds(arguments)

    /** End of an agent run: lets Android put Termux to sleep again. */
    override suspend fun onRunFinished() {
        if (!wakeLockHeld.compareAndSet(true, false)) return
        val root = sharedWorkspaceRoot() ?: return
        try { backend.run("termux-wake-unlock", root.path, 10_000L) } catch (_: Exception) { }
    }

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
        // Housekeeping next to the files: the command's pid (to stop it), a copy of its output (shown live), its exit
        // code (so the result does not depend on Termux's answer arriving) and, when the app gave up on a command
        // Termux had not started yet, a marker that makes the wrapper exit instead of running it late.
        val runDir = File(root, Workspace.AGENT_DIR).apply { mkdirs() }
        cleanStaleMarkers(runDir)
        val id = "run-" + nextRun.incrementAndGet() + "-" + (System.currentTimeMillis() % 100_000)
        val pidFile = File(runDir, "$id.pid")
        val logFile = File(runDir, "$id.log")
        val rcFile = File(runDir, "$id.rc")
        val abandonFile = File(runDir, "$id.abandon")
        val label = describe(arguments)
        val startedWall = System.currentTimeMillis()
        var endNote = "app closed or crashed" // replaced on every normal way out

        // Keeps Termux awake for the rest of the agent run (released in onRunFinished). Best effort: when Termux does
        // not answer, the start check below reports it.
        if (wakeLockHeld.compareAndSet(false, true)) {
            try { backend.run("termux-wake-lock", root.path, 10_000L) } catch (_: Exception) { }
        }

        try {
            val outcome: Outcome = coroutineScope {
                val run = async { backend.run(WRAPPER, dir.path, seconds * 1000L, listOf("bmoe-run", command, runDir.path, id)) }
                val startedAt = System.currentTimeMillis()
                var started = false
                var finishedAt: Long? = null
                var shownLine = ""
                var shownOutput = ""
                while (!run.isCompleted) {
                    withTimeoutOrNull(timing.pollMs) { run.join() }
                    if (run.isCompleted) break
                    val now = System.currentTimeMillis()
                    val output = tail(logFile, LIVE_OUTPUT_BYTES)
                    if (output != shownOutput) {
                        shownOutput = output
                        ctx.onOutput(output)
                        val last = lastLine(logFile)
                        if (last.isNotEmpty() && last != shownLine) {
                            shownLine = last
                            ctx.onProgress("$label…\n› $last") // the chat shows the elapsed time itself
                        }
                    }
                    if (pidFile.isFile || rcFile.isFile) started = true
                    if (!started && now - startedAt > timing.startGraceMs) {
                        abandonFile.createNewFile() // if Termux wakes up later, the wrapper exits instead of running it
                        run.cancel()
                        return@coroutineScope Outcome.NotStarted
                    }
                    if (rcFile.isFile) {
                        val since = finishedAt ?: now.also { finishedAt = it }
                        if (now - since > timing.resultGraceMs) {
                            run.cancel()
                            return@coroutineScope Outcome.FromFiles(resultFromFiles(rcFile, logFile))
                        }
                    }
                }
                run.await()?.let { Outcome.Answered(it) } ?: Outcome.TimedOut
            }
            endNote = when (outcome) {
                is Outcome.Answered -> "exit code ${outcome.result.exitCode ?: "unknown"}" +
                    (if (outcome.result.err != -1) ", Termux err ${outcome.result.err}: ${outcome.result.errmsg.orEmpty()}" else "")
                is Outcome.FromFiles -> "exit code ${outcome.result.exitCode ?: "unknown"} (Termux's answer lost, read from the files)"
                Outcome.NotStarted -> "Termux did not start the command"
                Outcome.TimedOut -> "time limit of ${seconds}s reached, stopped"
            }
            return when (outcome) {
                is Outcome.Answered -> format(outcome.result)
                is Outcome.FromFiles -> format(outcome.result) +
                    "\n(Termux's answer did not arrive, so this was read from the command's log; stdout and stderr are mixed.)"
                Outcome.NotStarted ->
                    "Error: Termux did not start the command within ${timing.startGraceMs / 1000}s. Android has probably " +
                        "paused Termux in the background. Tell the user to open Termux once and allow it to run in the " +
                        "background (battery settings: no restrictions, allow background activity), then try again. " +
                        "The command was not run."
                Outcome.TimedOut -> {
                    val stopped = stop(pidFile, dir)
                    "No result after ${seconds}s, so the command was " + (if (stopped) "stopped" else "abandoned (it could not be confirmed stopped)") +
                        ". Output so far (its end):\n" + clean(tail(logFile, 4000)).takeLast(OUTPUT_BUDGET).ifBlank { "(none)" } +
                        "\nFor long jobs, raise timeout_seconds (up to $MAX_TIMEOUT_S) or run it in steps."
                }
            }
        } catch (e: ShellBackend.Unavailable) {
            throw ToolInputException(e.message ?: "Termux is not reachable")
        } catch (e: CancellationException) {
            // The cancel button or Stop: end the command in Termux too, so nothing keeps running unseen; a command
            // Termux has not started yet is marked so it never starts.
            withContext(NonCancellable) {
                val notStarted = !pidFile.isFile && !rcFile.isFile
                if (notStarted) runCatching { abandonFile.createNewFile() }
                stop(pidFile, dir)
                endNote = if (notStarted) "cancelled before Termux started it" else "cancelled, stopped"
                cancelledOutput = if (notStarted) "Termux had not started the command yet, so it did not run."
                else "Output before it was stopped (its end):\n" + clean(tail(logFile, 4000)).takeLast(OUTPUT_BUDGET).ifBlank { "(none)" }
            }
            throw e
        } finally {
            archive(runDir, id, logFile, command, dir, startedWall, endNote)
            pidFile.delete()
            logFile.delete()
            rcFile.delete()
            runDir.delete() // only when empty
        }
    }

    private sealed interface Outcome {
        data class Answered(val result: TermuxResult) : Outcome
        data class FromFiles(val result: TermuxResult) : Outcome
        data object NotStarted : Outcome
        data object TimedOut : Outcome
    }

    /** The command finished but Termux's answer is missing: exit code and output come from the wrapper's files. */
    private fun resultFromFiles(rcFile: File, logFile: File): TermuxResult {
        val rc = runCatching { rcFile.readText().trim().toInt() }.getOrNull()
        val len = logFile.length().toInt()
        return TermuxResult(tail(logFile, FALLBACK_OUTPUT_BYTES), "", rc, -1, null, stdoutOriginalLength = len)
    }

    /**
     * Keeps a record of every command in `.bigmoe/history/` (newest [HISTORY_KEEP]): the command, folder, start time,
     * its whole output and how it ended. That is where to look when something went wrong, e.g. from Termux:
     * `ls -t ~/storage/shared/BigMoe/workspace/.bigmoe/history | head`. Never throws.
     */
    private fun archive(runDir: File, id: String, logFile: File, command: String, dir: File, startedAt: Long, endNote: String) {
        try {
            val history = File(runDir, HISTORY_DIR).apply { mkdirs() }
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date(startedAt))
            val started = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(startedAt))
            val seconds = (System.currentTimeMillis() - startedAt) / 1000
            val output = tail(logFile, HISTORY_OUTPUT_BYTES)
            File(history, "$stamp-$id.log").writeText(
                "# $ $command\n# folder: ${workspace.relative(dir).ifEmpty { "." }}\n# started: $started\n\n" +
                    output.ifEmpty { "(no output)\n" }.let { if (it.endsWith("\n")) it else it + "\n" } +
                    "\n# end: $endNote, after ${seconds}s\n"
            )
            history.listFiles { f -> f.name.endsWith(".log") }?.sortedByDescending { it.name }?.drop(HISTORY_KEEP)?.forEach { it.delete() }
        } catch (_: Exception) {
        }
    }

    /** Markers of commands that never started are removed after a day; nothing else is left in the folder. */
    private fun cleanStaleMarkers(runDir: File) {
        val old = System.currentTimeMillis() - 24 * 3600_000L
        runDir.listFiles { f -> f.name.endsWith(".abandon") && f.lastModified() < old }?.forEach { it.delete() }
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
        /** How much of a command's output the model gets: the end of stdout and stderr together. */
        const val OUTPUT_BUDGET = 1_500
        /** How much of the running command's output the chat shows (the end of it). */
        private const val LIVE_OUTPUT_BYTES = 6_000
        const val HISTORY_DIR = "history"
        private const val HISTORY_KEEP = 50
        private const val HISTORY_OUTPUT_BYTES = 200_000
        /** Same limit Termux has for its own answer. */
        private const val FALLBACK_OUTPUT_BYTES = 100_000
        private val nextRun = AtomicInteger(0)

        /**
         * Runs the user's command ($1) in its own bash, records the wrapper's pid in $2/$3.pid so it can be stopped,
         * and copies stdout and stderr into $2/$3.log as they come (for the live status line) while Termux still gets
         * both streams separately. The command's exit code is the wrapper's.
         */
        internal const val WRAPPER =
            "[ -e \"\$2/\$3.abandon\" ] && { rm -f \"\$2/\$3.abandon\"; exit 125; }\n" +
                "echo \$\$ > \"\$2/\$3.pid\"\n" +
                // Output shows up as it is written (Python buffers it otherwise), and nothing can wait for input:
                // a prompt fails at once instead of hanging until the time limit.
                // and as little noise as possible: no progress bars, no colours.
                "export PYTHONUNBUFFERED=1 PIP_NO_INPUT=1 PIP_DISABLE_PIP_VERSION_CHECK=1 PIP_PROGRESS_BAR=off " +
                "PIP_NO_COLOR=1 NO_COLOR=1 DEBIAN_FRONTEND=noninteractive\n" +
                "bash -c \"\$1\" < /dev/null > >(tee -a \"\$2/\$3.log\") 2> >(tee -a \"\$2/\$3.log\" >&2)\n" +
                "rc=\$?\n" +
                "echo \$rc > \"\$2/\$3.rc.tmp\" && mv -f \"\$2/\$3.rc.tmp\" \"\$2/\$3.rc\"\n" +
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
            // The model only gets the END of each stream (errors and results are at the end), OUTPUT_BUDGET characters
            // for both together: a pip or pkg run prints thousands of lines nobody needs in the context.
            val out = clean(r.stdout)
            val err = clean(r.stderr)
            var errBudget = if (out.length + err.length <= OUTPUT_BUDGET) err.length
            else if (out.isBlank()) OUTPUT_BUDGET else minOf(err.length, OUTPUT_BUDGET * 2 / 3)
            val outBudget = minOf(out.length, OUTPUT_BUDGET - errBudget)
            errBudget = minOf(err.length, OUTPUT_BUDGET - outBudget)
            val b = StringBuilder()
            b.append("exit code: ").append(r.exitCode ?: "unknown").append('\n')
            appendStream(b, "stdout", out, outBudget, r.stdout.length, r.stdoutOriginalLength)
            appendStream(b, "stderr", err, errBudget, r.stderr.length, r.stderrOriginalLength)
            if (out.isBlank() && err.isBlank()) b.append("(no output)\n")
            if (r.err != -1) b.append("Termux reported err ${r.err}: ").append(r.errmsg.orEmpty().take(500)).append('\n')
            return b.toString().trimEnd()
        }

        /**
         * The last [budget] characters of [text] (cleaned), starting at a line. When that, or Termux's own limit
         * ([termuxOriginal] > [rawLength]), left something out, the header says how much there was.
         */
        private fun appendStream(b: StringBuilder, label: String, text: String, budget: Int, rawLength: Int, termuxOriginal: Int?) {
            if (text.isBlank()) return
            var shown = text
            if (text.length > budget) {
                shown = text.takeLast(budget)
                val nl = shown.indexOf('\n')
                if (nl in 0 until shown.length - 1) shown = shown.substring(nl + 1)
            }
            val original = maxOf(termuxOriginal ?: 0, rawLength)
            b.append("--- ").append(label)
            if (shown.length < text.length || (termuxOriginal ?: 0) > rawLength) b.append(" (last ${shown.length} of $original characters)")
            b.append(" ---\n").append(shown).append('\n')
        }

        /**
         * Output as a terminal would have left it: colour codes removed, a line rewritten with \r (progress bars)
         * reduced to its last state, blank lines dropped.
         */
        internal fun clean(text: String): String {
            if (text.isBlank()) return ""
            val b = StringBuilder()
            for (raw in text.split('\n')) {
                val line = ANSI.replace(raw.substringAfterLast('\r'), "").trimEnd()
                if (line.isNotEmpty()) b.append(line).append('\n')
            }
            return b.toString().trimEnd()
        }

        private const val TERMUX_ALLOW_LINE =
            "mkdir -p ~/.termux && echo 'allow-external-apps = true' >> ~/.termux/termux.properties && termux-reload-settings"
    }
}

val TERMUX_TOOL_NAMES = setOf("run_shell")
