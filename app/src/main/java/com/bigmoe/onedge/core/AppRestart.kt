package com.bigmoe.onedge.core

import android.content.Context
import android.content.Intent

/**
 * "Restart app and load again" after a failed load: a fresh process is what "Force stop" gives — NPU (FastRPC)
 * mappings and native state of the old process are gone — and the model is loaded again at start
 * ([takePendingLoad], read by AppContainer.autoLoadLastModelIfEnabled).
 */
object AppRestart {
    private const val PREFS = "app_restart"
    private const val KEY_PATH = "load_after_restart"

    fun restartAndLoad(context: Context, modelPath: String) {
        val app = context.applicationContext
        // commit(), not apply(): the process ends right after this.
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PATH, modelPath).commit()
        val launch = app.packageManager.getLaunchIntentForPackage(app.packageName) ?: return
        // A new task for the launcher activity; the system starts a new process for it once this one is gone.
        app.startActivity(Intent.makeRestartActivityTask(launch.component))
        Runtime.getRuntime().exit(0)
    }

    /** The model to load once after a restart, or null; clears it. */
    fun takePendingLoad(context: Context): String? {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val path = p.getString(KEY_PATH, null) ?: return null
        p.edit().remove(KEY_PATH).apply()
        return path
    }
}
