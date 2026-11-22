package com.bigmoe.onedge

import android.app.Application
import com.bigmoe.onedge.di.AppContainer
import com.bigmoe.onedge.core.HexagonRuntimeSetup
import com.bigmoe.onedge.core.LogFiles
import com.bigmoe.onedge.core.MemoryWatch
import kotlinx.coroutines.flow.first

class OnEdgeApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        com.bigmoe.onedge.core.I18n.apply(this)
        // Configure FastRPC search paths before EngineNativeBridge loads the native library.
        // The ordinary CPU build contains no DSP skeleton assets; setup then safely no-ops.
        HexagonRuntimeSetup.prepare(this)
        container = AppContainer(this)
        LogFiles.init(this) { container.workspace.rootDir }
        // Hugging Face downloads that finished while the app was closed go into the models folder now.
        Thread { runCatching { com.bigmoe.onedge.ui.models.HfDownloads.moveFinished(this) } }.start()
        // Our own parallel downloads that were interrupted resume.
        runCatching { com.bigmoe.onedge.ui.models.HfDl.connections = getSharedPreferences("hf_dl", MODE_PRIVATE).getInt("connections", 5); com.bigmoe.onedge.ui.models.HfDl.resume(this) }
        // Log page: record llama.cpp's and the engine's log from the start, but only when the user switched it on
        // (Log page, off by default). Off the main thread: it reads the setting and loads the native library.
        Thread {
            runCatching {
                val s = kotlinx.coroutines.runBlocking { container.settingsDataStore.settingsFlow.first() }
                if (s.logCapture) LogFiles.startRecording(com.bigmoe.onedge.core.EngineNativeBridge(), s.logToFile)
            }
        }.start()
        container.startPerfLog() // before MemoryWatch: it reports a crashed run once, at start
        MemoryWatch.start(this, container.engineController.state)
        container.followCpuCoreSetting()
        container.trackModelUse()
        container.autoLoadLastModelIfEnabled()
        container.startApiServerIfEnabled()
        container.startChatSync()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        MemoryWatch.noteTrim(level)
    }
}
