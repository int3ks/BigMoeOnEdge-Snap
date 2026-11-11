package com.bigmoe.onedge

import android.app.Application
import com.bigmoe.onedge.di.AppContainer
import com.bigmoe.onedge.core.HexagonRuntimeSetup

class OnEdgeApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Configure FastRPC search paths before EngineNativeBridge loads the native library.
        // The ordinary CPU build contains no DSP skeleton assets; setup then safely no-ops.
        HexagonRuntimeSetup.prepare(this)
        // Keep llama.cpp's and the engine's log for the Log page (Settings → Diagnostics) from the start. Off the main
        // thread: it loads the native library, which must not be able to crash or delay app start.
        Thread {
            runCatching { com.bigmoe.onedge.core.EngineNativeBridge().nativeLogStart() }
        }.start()
        container = AppContainer(this)
        container.followCpuCoreSetting()
        container.trackModelUse()
        container.autoLoadLastModelIfEnabled()
        container.startApiServerIfEnabled()
    }
}
