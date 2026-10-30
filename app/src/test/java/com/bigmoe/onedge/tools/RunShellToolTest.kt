package com.bigmoe.onedge.tools

import com.bigmoe.onedge.workspace.Workspace
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
        val calls = ArrayList<Triple<String, String, Long>>()
        override fun isInstalled() = installed
        override fun hasPermission() = permitted
        override suspend fun run(command: String, workdir: String, timeoutMs: Long): TermuxResult? {
            calls.add(Triple(command, workdir, timeoutMs))
            return result
        }
    }

    private fun setup(shared: Boolean = true): Triple<RunShellTool, FakeBackend, Workspace> {
        val root = File.createTempFile("bmoe-shell", "").apply { delete(); mkdirs(); deleteOnExit() }
        val ws = Workspace(root)
        val backend = FakeBackend()
        return Triple(RunShellTool(backend, ws) { if (shared) ws.rootDir else null }, backend, ws)
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
}
