package com.bigmoe.onedge.core

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.bigmoe.onedge.data.local.dao.PerfEventDao
import com.bigmoe.onedge.data.local.entity.PerfEventEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Modifier

/** The device's heat at one moment: Android's thermal status (0 none … 6 shutdown), headroom (1.0 = throttling) and battery °C. */
data class Thermal(val status: Int, val headroom: Float?, val batteryC: Float?) {
    fun put(o: JSONObject, prefix: String) {
        o.put("${prefix}Thermal", status)
        headroom?.takeIf { !it.isNaN() }?.let { o.put("${prefix}Headroom", it.toDouble()) }
        batteryC?.let { o.put("${prefix}BatteryC", it.toDouble()) }
    }

    companion object {
        fun name(status: Int): String = when (status) {
            0 -> "none"; 1 -> "light"; 2 -> "moderate"; 3 -> "severe"; 4 -> "critical"; 5 -> "emergency"; 6 -> "shutdown"
            else -> "unknown"
        }
    }
}

/**
 * Performance log: every model load (with all load settings), failed load, answer (speed, prompt size, context, RAM,
 * heat) and every run that did not end normally goes into the `perf_events` table. [PerfAnalyzer] turns it into a
 * report for the Performance log page, for an export and for a model asked for better settings.
 * Recording costs one insert per load and per answer; the heat readings are two cheap system calls.
 */
object PerfLog {
    private var dao: PerfEventDao? = null
    private var appContext: Context? = null
    private var scope: CoroutineScope? = null

    /** Before [MemoryWatch.start], so a crash found at start is recorded. */
    fun start(context: Context, eventDao: PerfEventDao, engineState: StateFlow<EngineState>, appScope: CoroutineScope) {
        if (dao != null) return
        dao = eventDao
        appContext = context.applicationContext
        scope = appScope
        MemoryWatch.onAbortedRun = { path, config, minMb, seen ->
            record("crash", File(path).name, JSONObject().apply {
                put("path", path)
                put("config", config)
                if (minMb >= 0) put("minFreeMb", minMb)
                put("lastSeenAt", seen)
            }, at = if (seen > 0) seen else System.currentTimeMillis())
        }
        appScope.launch {
            var loadingPath: String? = null
            var freeBefore = -1L
            var heatBefore: Thermal? = null
            engineState.collect { s ->
                when (s) {
                    is EngineState.Loading -> if (loadingPath != s.path) {
                        loadingPath = s.path
                        freeBefore = freeMbNow()
                        heatBefore = thermalNow()
                    }
                    is EngineState.Ready -> if (loadingPath == s.info.path) {
                        loadingPath = null
                        record("load", s.info.name, JSONObject().apply {
                            put("path", s.info.path)
                            put("loadSeconds", s.info.loadSeconds)
                            put("npuReady", s.info.npuPrefillReady)
                            put("nCtx", s.info.nCtx)
                            put("vision", s.info.visionActive)
                            s.info.draftModelPath?.let { put("draft", File(it).name) }
                            s.info.loadWarning?.let { put("warning", it) }
                            if (freeBefore >= 0) put("freeBeforeMb", freeBefore)
                            put("freeAfterMb", freeMbNow())
                            heatBefore?.put(this, "start")
                            thermalNow().put(this, "end")
                            put("cfg", configJson(s.info.config))
                        })
                    }
                    is EngineState.Failed -> if (loadingPath != null) {
                        val path = loadingPath!!
                        loadingPath = null
                        record("load_failed", File(path).name, JSONObject().apply {
                            put("path", path)
                            put("error", s.message.take(500))
                            if (freeBefore >= 0) put("freeBeforeMb", freeBefore)
                        })
                    }
                    EngineState.Unloaded -> loadingPath = null
                }
                Unit
            }
        }
    }

    /** One answer; [data] is filled by the chat (see ChatViewModel.RunSink). */
    fun recordTurn(model: String, data: JSONObject) = record("turn", model, data)

    private fun record(kind: String, model: String, data: JSONObject, at: Long = System.currentTimeMillis()) {
        val d = dao ?: return
        scope?.launch(Dispatchers.IO) {
            runCatching { d.insert(PerfEventEntity(at = at, kind = kind, model = model, data = data.toString())) }
        }
    }

    suspend fun all(): List<PerfEventEntity> = dao?.all().orEmpty()

    suspend fun clear() {
        dao?.clear()
    }

    fun counts(kind: String) = dao?.countFlow(kind)

    /** Every load setting by its name in [LoadConfig] (the app is not minified, so the names are the source's). */
    fun configJson(c: LoadConfig): JSONObject = JSONObject().apply {
        for (f in LoadConfig::class.java.declaredFields) {
            if (Modifier.isStatic(f.modifiers) || f.isSynthetic) continue
            f.isAccessible = true
            when (val v = f.get(c)) {
                null -> {}
                is Boolean, is Int, is Long -> put(f.name, v)
                is Float -> put(f.name, v.toDouble())
                is Double -> put(f.name, v)
                is Enum<*> -> put(f.name, v.name)
                else -> put(f.name, v.toString())
            }
        }
    }

    fun freeMbNow(): Long {
        val am = appContext?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return -1
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return info.availMem / (1024L * 1024L)
    }

    fun thermalNow(): Thermal {
        val ctx = appContext ?: return Thermal(-1, null, null)
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val status = runCatching { pm?.currentThermalStatus ?: -1 }.getOrDefault(-1)
        val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { pm?.getThermalHeadroom(0) }.getOrNull()
        } else null
        val battery = runCatching {
            ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE }?.let { it / 10f }
        }.getOrNull()
        return Thermal(status, headroom, battery)
    }
}
