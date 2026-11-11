package com.bigmoe.onedge

import android.app.Application
import com.bigmoe.onedge.di.AppContainer
import com.bigmoe.onedge.core.HexagonRuntimeSetup
import com.bigmoe.onedge.core.LogFiles
import kotlinx.coroutines.flow.first

class OnEdgeApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Configure FastRPC search paths before EngineNativeBridge loads the native library.
        // The ordinary CPU build contains no DSP skeleton assets; setup then safely no-ops.
        HexagonRuntimeSetup.prepare(this)
        container = AppContainer(this)
        LogFiles.init(this) { container.workspace.rootDir }
        // Log page: record llama.cpp's and the engine's log from the start, but only when the user switched it on
        // (Log page, off by default). Off the main thread: it reads the setting and loads the native library.
        Thread {
            runCatching {
                val s = kotlinx.coroutines.runBlocking { container.settingsDataStore.settingsFlow.first() }
                if (s.logCapture) LogFiles.startRecording(com.bigmoe.onedge.core.EngineNativeBridge(), s.logToFile)
            }
        }.start()
        container.followCpuCoreSetting()
        container.trackModelUse()
        container.autoLoadLastModelIfEnabled()
        container.startApiServerIfEnabled()
    }
}
