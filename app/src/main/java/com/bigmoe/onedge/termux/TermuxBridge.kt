package com.bigmoe.onedge.termux

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import com.bigmoe.onedge.tools.ShellBackend
import com.bigmoe.onedge.tools.TermuxResult
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Termux's RUN_COMMAND intent API (termux-app: RunCommandService, TermuxConstants). The literal keys are copied from
 * TermuxConstants (termux-shared) so the app does not need the termux-shared library.
 */
object TermuxApi {
    const val PACKAGE = "com.termux"
    const val PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"
    const val SERVICE = "com.termux.app.RunCommandService"
    const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    const val EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
    const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

    /** Bundle extra of the result intent and its keys. */
    const val EXTRA_RESULT_BUNDLE = "result"
    const val RESULT_STDOUT = "stdout"
    const val RESULT_STDOUT_ORIGINAL_LENGTH = "stdout_original_length"
    const val RESULT_STDERR = "stderr"
    const val RESULT_STDERR_ORIGINAL_LENGTH = "stderr_original_length"
    const val RESULT_EXIT_CODE = "exitCode"
    const val RESULT_ERR = "err"
    const val RESULT_ERRMSG = "errmsg"
    const val ERR_OK = -1

    /** bash of Termux's own prefix; the background app shell already carries Termux's PATH and environment. */
    const val BASH = "/data/data/com.termux/files/usr/bin/bash"

    /** For the Settings hint: one line that enables the API in Termux. */
    const val ALLOW_EXTERNAL_APPS_COMMAND =
        "mkdir -p ~/.termux && echo 'allow-external-apps = true' >> ~/.termux/termux.properties && termux-reload-settings"
}

/**
 * Runs shell commands in the separately installed Termux app (F-Droid / GitHub build) through its RUN_COMMAND
 * intent and waits for the result, which Termux sends back through a PendingIntent to [TermuxResultReceiver].
 *
 * Needs: Termux installed, the runtime permission [TermuxApi.PERMISSION_RUN_COMMAND] granted to this app, and
 * `allow-external-apps = true` in Termux's termux.properties (the app cannot check that up front; Termux answers
 * with an error message that [run] passes on). Package visibility for com.termux is declared in the manifest.
 */
class TermuxBridge(context: Context) : ShellBackend {
    private val appContext = context.applicationContext

    @Suppress("DEPRECATION")
    override fun isInstalled(): Boolean = try {
        appContext.packageManager.getPackageInfo(TermuxApi.PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, TermuxApi.PERMISSION_RUN_COMMAND) == PackageManager.PERMISSION_GRANTED

    /**
     * Runs `bash -c [script] [args...]` in the background in [workdir] (an absolute path Termux can reach). Returns null when
     * no result arrived within [timeoutMs]; the command then keeps running inside Termux, which cannot be stopped from here.
     * Throws [ShellBackend.Unavailable] when the intent could not be delivered.
     */
    override suspend fun run(script: String, workdir: String, timeoutMs: Long, args: List<String>): TermuxResult? {
        val label = "BigMoeOnEdge² agent"
        val id = nextId.incrementAndGet()
        val waiter = CompletableDeferred<Bundle>()
        pending[id] = waiter
        try {
            val resultIntent = Intent(appContext, TermuxResultReceiver::class.java)
                .setPackage(appContext.packageName)
                .putExtra(EXTRA_EXEC_ID, id)
            // Mutable: Termux fills in the result bundle. Explicit component, as Android 14+ requires for mutable ones.
            val flags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pendingIntent = PendingIntent.getBroadcast(appContext, id, resultIntent, flags)

            val intent = Intent(TermuxApi.ACTION_RUN_COMMAND)
                .setClassName(TermuxApi.PACKAGE, TermuxApi.SERVICE)
                .putExtra(TermuxApi.EXTRA_COMMAND_PATH, TermuxApi.BASH)
                .putExtra(TermuxApi.EXTRA_ARGUMENTS, (listOf("-c", script) + args).toTypedArray())
                .putExtra(TermuxApi.EXTRA_WORKDIR, workdir)
                .putExtra(TermuxApi.EXTRA_BACKGROUND, true)
                .putExtra(TermuxApi.EXTRA_COMMAND_LABEL, label)
                .putExtra(TermuxApi.EXTRA_PENDING_INTENT, pendingIntent)
            try {
                // RunCommandService calls startForeground() in onCreate/onStartCommand, so this is allowed even
                // when this app is not in the foreground at that moment.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) appContext.startForegroundService(intent)
                else appContext.startService(intent)
            } catch (e: SecurityException) {
                throw ShellBackend.Unavailable("Termux refused the command: ${e.message ?: "permission denied"}. Grant BigMoeOnEdge² the permission \"Run commands in Termux environment\".")
            } catch (e: Exception) {
                throw ShellBackend.Unavailable("Could not reach Termux: ${e.message ?: e.javaClass.simpleName}")
            }

            val bundle = withTimeoutOrNull(timeoutMs) { waiter.await() } ?: return null
            return toResult(bundle)
        } finally {
            pending.remove(id)
        }
    }

    companion object {
        const val EXTRA_EXEC_ID = "com.bigmoe.onedge.termux.EXEC_ID"

        /** Request codes of the result PendingIntents; seeded from the clock so a restarted app does not reuse one. */
        private val nextId = AtomicInteger((System.currentTimeMillis() / 1000 % 1_000_000).toInt())
        private val pending = ConcurrentHashMap<Int, CompletableDeferred<Bundle>>()

        /** An answer without a result bundle still ends the wait (as "exit code unknown"), it is not a timeout. */
        internal fun deliver(id: Int, result: Bundle?) {
            pending[id]?.complete(result ?: Bundle())
        }

        internal fun toResult(b: Bundle): TermuxResult = TermuxResult(
            stdout = b.getString(TermuxApi.RESULT_STDOUT).orEmpty(),
            stderr = b.getString(TermuxApi.RESULT_STDERR).orEmpty(),
            exitCode = if (b.containsKey(TermuxApi.RESULT_EXIT_CODE)) b.getInt(TermuxApi.RESULT_EXIT_CODE) else null,
            err = if (b.containsKey(TermuxApi.RESULT_ERR)) b.getInt(TermuxApi.RESULT_ERR) else TermuxApi.ERR_OK,
            errmsg = b.getString(TermuxApi.RESULT_ERRMSG),
            // Termux puts the original lengths in as strings.
            stdoutOriginalLength = lengthOf(b, TermuxApi.RESULT_STDOUT_ORIGINAL_LENGTH),
            stderrOriginalLength = lengthOf(b, TermuxApi.RESULT_STDERR_ORIGINAL_LENGTH)
        )

        @Suppress("DEPRECATION")
        private fun lengthOf(b: Bundle, key: String): Int? = b.get(key)?.toString()?.trim()?.toIntOrNull()
    }
}

/** Receives Termux's answer to a [TermuxBridge.run] call (declared in the manifest, not exported). */
class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(TermuxBridge.EXTRA_EXEC_ID, Int.MIN_VALUE)
        if (id == Int.MIN_VALUE) return
        TermuxBridge.deliver(id, intent.getBundleExtra(TermuxApi.EXTRA_RESULT_BUNDLE))
    }
}
