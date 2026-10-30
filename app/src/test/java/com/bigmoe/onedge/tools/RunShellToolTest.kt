package com.bigmoe.onedge.tools

import com.bigmoe.onedge.workspace.Workspace
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RunShellToolTest {
    private class FakeBackend(
        var installed: Boolean = true,
        var permitted: Boolean = true,
        var result: TermuxResult? = TermuxResult("hello\n", "", 0, -1, null)
    ) : ShellBackend {
        /** (the user's command, workdir, timeout) of each run_shell call; stop-script calls are in [stops]. */
        val calls = ArrayList<Triple<String, String, Long>>()
        val stops = ArrayList<String>()
        val wakeCalls = ArrayList<String>()
        override fun isInstalled() = installed
        override fun hasPermission() = permitted
        override suspend fun run(script: String, workdir: String, timeoutMs: Long, args: List<String>): TermuxResult? {
            if (script == RunShellTool.KILL_TREE) { stops.add(args[1]); return TermuxResult("", "", 0, -1, null) }
            if (script.startsWith("termux-wake")) { wakeCalls.add(script); return TermuxResult("", "", 0, -1, null) }
            assertEquals(RunShellTool.WRAPPER, script)
            calls.add(Triple(args[1], workdir, timeoutMs))
            return result
        }
    }

    private val fast = RunShellTool.Timing(pollMs = 50, startGraceMs = 1_500, resultGraceMs = 500)

    private fun setup(shared: Boolean = true): Triple<RunShellTool, FakeBackend, Workspace> {
        val root = File.createTempFile("bmoe-shell", "").apply { delete(); mkdirs(); deleteOnExit() }
        val ws = Workspace(root)
        val backend = FakeBackend()
        // The fake answers at once and never writes a pid file, which the start check must not mind.
        return Triple(RunShellTool(backend, ws, { if (shared) ws.rootDir else null }, fast), backend, ws)
    }

    private fun args(command: String, cwd: String? = null, timeout: Int? = null) = JSONObject().put("command", command).also {
        if (cwd != null) it.put("cwd", cwd)
        if (timeout != null) it.put("timeout_seconds", timeout)
    }

    private fun expectInputError(block: suspend () -> Unit): String = runBlocking {
        try { block(); throw AssertionError("no error") } catch (e: ToolInputException) { e.message.orEmpty() }
    }

    @Test
    fun runsInTheWorkspaceAndFormatsTheResult() = runBlocking {
        val (tool, backend, ws) = setup()
        ws.mkdir("proj")
        val out = tool.execute(args("python3 a.py", cwd = "proj", timeout = 300))
        assertEquals("exit code: 0\n--- stdout ---\nhello", out)
        val (cmd, dir, timeout) = backend.calls.single()
        assertEquals("python3 a.py", cmd)
        assertEquals(File(ws.rootDir, "proj").path, dir)
        assertEquals(300_000L, timeout)
        assertEquals(315_000L, tool.timeoutMs(args("x", timeout = 300)))
    }

    @Test
    fun wakeLockIsTakenOncePerRunAndReleasedAtTheEnd() = runBlocking {
        val (tool, backend, _) = setup()
        tool.execute(args("echo a"))
        tool.execute(args("echo b"))
        assertEquals(listOf("termux-wake-lock"), backend.wakeCalls)
        tool.onRunFinished()
        tool.onRunFinished()
        assertEquals(listOf("termux-wake-lock", "termux-wake-unlock"), backend.wakeCalls)
    }

    @Test
    fun refusesWhenTermuxCannotWork() {
        val (privateTool, _, _) = setup(shared = false)
        assertTrue(expectInputError { privateTool.execute(args("ls")) }.contains("Shared workspace folder"))
        val (tool, backend, _) = setup()
        backend.permitted = false
        assertTrue(expectInputError { tool.execute(args("ls")) }.contains("grant"))
        backend.installed = false
        assertTrue(expectInputError { tool.execute(args("ls")) }.contains("not installed"))
        backend.installed = true; backend.permitted = true
        assertTrue(expectInputError { tool.execute(args("ls", cwd = "../outside")) }.contains("escapes"))
        assertTrue(expectInputError { tool.execute(args("ls", cwd = "missing")) }.contains("no such folder"))
        assertTrue(expectInputError { tool.execute(args("  ")) }.contains("empty"))
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun timeoutIsClampedAndReported() = runBlocking {
        assertEquals(RunShellTool.DEFAULT_TIMEOUT_S, RunShellTool.waitSeconds(JSONObject()))
        assertEquals(5, RunShellTool.waitSeconds(JSONObject().put("timeout_seconds", 1)))
        assertEquals(RunShellTool.MAX_TIMEOUT_S, RunShellTool.waitSeconds(JSONObject().put("timeout_seconds", 99999)))
        val (tool, backend, _) = setup()
        backend.result = null
        assertTrue(tool.execute(args("sleep 999", timeout = 10)).startsWith("No result after 10s"))
        assertTrue(backend.stops.isEmpty()) // the fake never wrote a pid file, so there was nothing to stop
    }

    @Test
    fun formatExplainsTermuxErrorsAndTruncation() {
        val notAllowed = RunShellTool.format(
            TermuxResult("", "", null, 1, "RUN_COMMAND requires allow-external-apps property to be set to true")
        )
        assertTrue(notAllowed.startsWith("Error: Termux could not run the command"))
        assertTrue(notAllowed.contains("termux-reload-settings"))

        val failed = RunShellTool.format(TermuxResult("", "No module named gtts\n", 1, -1, null))
        assertEquals("exit code: 1\n--- stderr ---\nNo module named gtts", failed)

        val cut = RunShellTool.format(TermuxResult("tail", "", 0, -1, null, stdoutOriginalLength = 250_000))
        assertTrue(cut.contains("--- stdout (last 4 of 250000 characters) ---"))

        assertEquals("exit code: 0\n(no output)", RunShellTool.format(TermuxResult("", "", 0, -1, null)))
    }

    @Test
    fun confirmationShowsTheCommand() {
        val (tool, _, _) = setup()
        val c = tool.confirmation(args("rm -rf build", cwd = "proj"))
        assertTrue(c.message.startsWith("$ rm -rf build"))
        assertTrue(c.message.contains("Folder: proj"))
    }

    /** Runs the real wrapper and stop scripts with the host's bash, the way Termux does (`bash -c script args`). */
    /** [dropAnswer]: runs the command but never answers (Termux's result lost); [neverStart]: runs nothing at all. */
    private class LocalBash(val dropAnswer: Boolean = false, val neverStart: Boolean = false) : ShellBackend {
        override fun isInstalled() = true
        override fun hasPermission() = true
        override suspend fun run(script: String, workdir: String, timeoutMs: Long, args: List<String>): TermuxResult? {
            if (script == RunShellTool.WRAPPER && neverStart) kotlinx.coroutines.awaitCancellation()
            val r = runBash(script, workdir, timeoutMs, args)
            if (script == RunShellTool.WRAPPER && dropAnswer) kotlinx.coroutines.awaitCancellation()
            return r
        }
        private suspend fun runBash(script: String, workdir: String, timeoutMs: Long, args: List<String>): TermuxResult? =
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val p = ProcessBuilder(listOf("bash", "-c", script) + args).directory(File(workdir)).start()
                val out = StringBuilder()
                val err = StringBuilder()
                val t1 = Thread { out.append(p.inputStream.bufferedReader().readText()) }.apply { start() }
                val t2 = Thread { err.append(p.errorStream.bufferedReader().readText()) }.apply { start() }
                try {
                    var waited = 0L
                    while (p.isAlive && waited < timeoutMs) { kotlinx.coroutines.delay(50); waited += 50 }
                    if (p.isAlive) return@withContext null
                    t1.join(); t2.join()
                    TermuxResult(out.toString(), err.toString(), p.exitValue(), -1, null)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                }
            }
    }

    private fun hasBash(): Boolean = try { ProcessBuilder("bash", "-c", "command -v ps").start().waitFor() == 0 } catch (_: Exception) { false }

    @Test
    fun realBashWrapperKeepsStreamsExitCodeAndCleansUp() = runBlocking {
        if (!hasBash()) return@runBlocking
        val root = File.createTempFile("bmoe-real", "").apply { delete(); mkdirs() }
        try {
            val ws = Workspace(root)
            val tool = RunShellTool(LocalBash(), ws, { ws.rootDir }, fast)
            val out = tool.execute(args("echo hello; echo oops >&2; touch made.txt; exit 3", timeout = 20))
            assertEquals("exit code: 3\n--- stdout ---\nhello\n--- stderr ---\noops", out)
            assertTrue(File(root, "made.txt").isFile)
            assertTrue("housekeeping removed", !File(root, Workspace.AGENT_DIR).exists())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun realBashTimeoutStopsTheWholeProcessTree() = runBlocking {
        if (!hasBash()) return@runBlocking
        val root = File.createTempFile("bmoe-real", "").apply { delete(); mkdirs() }
        try {
            val ws = Workspace(root)
            val progress = ArrayList<String>()
            val tool = RunShellTool(LocalBash(), ws, { ws.rootDir }, fast)
            // A child process that would write a file after 8 s; the 5 s limit must stop it before that.
            val out = tool.execute(
                args("echo started; (sleep 8; touch late.txt) & wait", timeout = 5),
                ToolContext("s", "m") { synchronized(progress) { progress.add(it) } }
            )
            assertTrue(out, out.startsWith("No result after 5s, so the command was stopped"))
            assertTrue(out, out.contains("started"))
            synchronized(progress) { assertTrue(progress.toString(), progress.any { it.contains("› started") }) }
            Thread.sleep(4500)
            assertTrue("child process was not stopped", !File(root, "late.txt").exists())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun cancellingStopsTheCommand() = runBlocking {
        if (!hasBash()) return@runBlocking
        val root = File.createTempFile("bmoe-real", "").apply { delete(); mkdirs() }
        try {
            val ws = Workspace(root)
            val manager = ToolManager().apply { autoApprove = true; registerTool(RunShellTool(LocalBash(), ws, { ws.rootDir }, fast)) }
            val job = kotlinx.coroutines.GlobalScope.async {
                manager.executeToolCall("run_shell", """{"command":"(sleep 6; touch late.txt) & wait","timeout_seconds":60}""")
            }
            var waited = 0
            while (!manager.hasRunningTool() && waited++ < 250) kotlinx.coroutines.delay(20)
            assertTrue("tool never started", manager.hasRunningTool())
            kotlinx.coroutines.delay(1500)
            assertTrue(manager.cancelRunningTools())
            val result = job.await()
            assertTrue(result, result.startsWith("Cancelled by the user"))
            Thread.sleep(6000)
            assertTrue("command kept running after cancel", !File(root, "late.txt").exists())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun realBashClosesInputAndUnbuffersPython() = runBlocking {
        if (!hasBash()) return@runBlocking
        val root = File.createTempFile("bmoe-real", "").apply { delete(); mkdirs() }
        try {
            val ws = Workspace(root)
            val tool = RunShellTool(LocalBash(), ws, { ws.rootDir }, fast)
            // Without the closed input, `read` would wait for the whole time limit (the test's stdin is an open pipe).
            val started = System.currentTimeMillis()
            val out = tool.execute(args("read x; echo \"got:[\$x] py=\$PYTHONUNBUFFERED pip=\$PIP_NO_INPUT\"", timeout = 30))
            assertTrue(out, out.contains("got:[] py=1 pip=1"))
            assertTrue("took ${System.currentTimeMillis() - started} ms", System.currentTimeMillis() - started < 10_000)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun realBashLostAnswerIsReadFromTheFiles() = runBlocking {
        if (!hasBash()) return@runBlocking
        val root = File.createTempFile("bmoe-real", "").apply { delete(); mkdirs() }
        try {
            val ws = Workspace(root)
            val outputs = ArrayList<String>()
            val tool = RunShellTool(LocalBash(dropAnswer = true), ws, { ws.rootDir }, fast)
            val out = tool.execute(
                args("echo one; echo two >&2; exit 4", timeout = 60),
                ToolContext("s", "m", onOutput = { synchronized(outputs) { outputs.add(it) } })
            )
            assertTrue(out, out.startsWith("exit code: 4"))
            assertTrue(out, out.contains("one") && out.contains("two"))
            assertTrue(out, out.contains("Termux's answer did not arrive"))
            assertTrue("housekeeping removed", File(root, Workspace.AGENT_DIR).listFiles().isNullOrEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun termuxThatNeverStartsIsReportedQuicklyAndMarked() = runBlocking {
        val root = File.createTempFile("bmoe-real", "").apply { delete(); mkdirs() }
        try {
            val ws = Workspace(root)
            val tool = RunShellTool(LocalBash(neverStart = true), ws, { ws.rootDir }, fast)
            val started = System.currentTimeMillis()
            val out = tool.execute(args("echo never", timeout = 600))
            assertTrue(out, out.startsWith("Error: Termux did not start the command"))
            assertTrue(System.currentTimeMillis() - started < 10_000)
            val markers = File(root, Workspace.AGENT_DIR).listFiles { f -> f.name.endsWith(".abandon") }.orEmpty()
            assertEquals("a late start is prevented", 1, markers.size)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun wrapperExitsWithoutRunningWhenMarkedAbandoned() {
        if (!hasBash()) return
        val dir = File.createTempFile("bmoe-real", "").apply { delete(); mkdirs() }
        try {
            File(dir, "r1.abandon").createNewFile()
            val p = ProcessBuilder("bash", "-c", RunShellTool.WRAPPER, "bmoe-run", "touch ran.txt", dir.path, "r1")
                .directory(dir).start()
            assertEquals(125, p.waitFor())
            assertTrue(!File(dir, "ran.txt").exists())
            assertTrue("marker consumed", !File(dir, "r1.abandon").exists())
        } finally { dir.deleteRecursively() }
    }
}
