package com.bigmoe.onedge.core

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The log file next to the Log page. While "Record log" and "Write to file" are on, every line llama.cpp and the
 * engine print is also appended to `<workspace>/.bigmoe/logs/bigmoe-<date>-<time>.log` by the native reader (see `nativeLogStart`), so the
 * text before a crash is still there after the app died. One file per recording start; the newest [KEEP_FILES] are kept.
 * The header says why the previous process of the app ended (Android 11+), which tells a crash from a kill for memory.
 */
object LogFiles {
    const val KEEP_FILES = 10

    private lateinit var appContext: Context
    private lateinit var workspaceRoot: () -> File

    /** Path of the file the current recording writes to, or null when no file is written. Observable by the Log page. */
    var currentPath: String? by mutableStateOf<String?>(null)
        private set

    /** True while the native recording runs (with or without a file). */
    @Volatile
    var recording: Boolean = false
        private set

    /** A line of the app's own in the log (ring and file), e.g. the free-RAM samples. Ignored while not recording. */
    fun note(line: String) {
        if (!recording) return
        runCatching { EngineNativeBridge().nativeLogNote(line) }
    }

    fun init(context: Context, workspaceRoot: () -> File) {
        appContext = context.applicationContext
        this.workspaceRoot = workspaceRoot
        installCrashHandler()
    }

    /**
     * Kotlin/Java crashes: Android keeps only "crash (Java exception)" in the exit info, and some vendors (Honor)
     * encrypt logcat's crash buffer. So the stack trace is written by the app itself before the process dies: into the
     * running log (ring and file) and into files/last-crash.txt, which the next log file's header shows once.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val trace = android.util.Log.getStackTraceString(e)
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                File(appContext.filesDir, CRASH_FILE).writeText("$stamp, thread \"${thread.name}\"\n$trace")
                if (recording) {
                    note("JAVA CRASH in thread \"${thread.name}\":")
                    trace.lineSequence().take(60).forEach { note(it) }
                }
            }
            previous?.uncaughtException(thread, e)
        }
    }

    private const val CRASH_FILE = "last-crash.txt"

    /** The stack trace of the last Java crash, once (the file is removed after it was shown). */
    private fun lastJavaCrash(): String? = runCatching {
        val f = File(appContext.filesDir, CRASH_FILE)
        if (!f.isFile) return null
        val text = f.readText()
        f.delete()
        "=== last Java crash: " + text.trimEnd() + "\n=== end of crash ===\n"
    }.getOrNull()

    /** Starts the native recording, with a new log file when [toFile]. Call off the main thread. */
    fun startRecording(bridge: EngineNativeBridge, toFile: Boolean) {
        val path = if (toFile) runCatching { createFile() }.getOrNull() else null
        currentPath = path
        bridge.nativeLogStart(path)
        recording = true
    }

    /** Ends the recording. The file stays. */
    fun stopRecording(bridge: EngineNativeBridge) {
        recording = false
        bridge.nativeLogStop()
        currentPath = null
    }

    private fun createFile(): String? {
        // The workspace (shared folder when chosen, so Termux and file managers see it); the app's private folder if
        // that cannot be written.
        val dirs = listOf(
            File(workspaceRoot(), ".bigmoe/logs"),
            File(appContext.filesDir, "logs")
        )
        for (dir in dirs) {
            val file = runCatching {
                dir.mkdirs()
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                File(dir, "bigmoe-$stamp.log").also { it.writeText(header()) }
            }.getOrNull() ?: continue
            prune(dir)
            return file.path
        }
        return null
    }

    private fun prune(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("bigmoe-") && f.name.endsWith(".log") } ?: return
        files.sortedByDescending { it.name }.drop(KEEP_FILES).forEach { runCatching { it.delete() } }
    }

    private fun header(): String {
        val version = runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName }.getOrNull()
        val sb = StringBuilder()
        sb.append("=== BigMoeOnEdge log, started ")
            .append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            .append(", app ").append(version ?: "?")
            .append(", ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(", Android ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(") ===\n")
        sb.append("=== ").append(previousExit()).append(" ===\n")
        lastJavaCrash()?.let { sb.append(it) }
        return sb.toString()
    }

    /**
     * Why the last earlier process of this app ended: "crash (native)", "low memory kill", "signaled" ... with
     * Android's own description. Only exists from Android 11; the entry is Android's, not a guess of ours.
     */
    private fun previousExit(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "previous exit: not available before Android 11"
        return runCatching {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mine = android.os.Process.myPid()
            val last = am.getHistoricalProcessExitReasons(appContext.packageName, 0, 5).firstOrNull { it.pid != mine }
                ?: return "previous exit: none recorded"
            val reason = when (last.reason) {
                android.app.ApplicationExitInfo.REASON_CRASH -> "crash (Java exception)"
                android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash (native)"
                android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "killed: low memory"
                android.app.ApplicationExitInfo.REASON_SIGNALED -> "killed by signal ${last.status}"
                android.app.ApplicationExitInfo.REASON_ANR -> "not responding (ANR)"
                android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "killed: excessive resource usage"
                android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "stopped by the user"
                android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "exited itself"
                android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency died"
                android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission change"
                android.app.ApplicationExitInfo.REASON_USER_STOPPED -> "user stopped"
                else -> "reason code ${last.reason}"
            }
            val whenText = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(last.timestamp))
            "previous exit: $reason at $whenText, status ${last.status}" +
                (last.description?.takeIf { it.isNotBlank() }?.let { ", \"$it\"" } ?: "")
        }.getOrElse { "previous exit: could not be read" }
    }
}
