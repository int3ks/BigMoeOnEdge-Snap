package com.bigmoe.onedge.core

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import com.bigmoe.onedge.data.local.datastore.EngineSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Auto CPU cores by temperature (decode only, decided before each answer). The cores chosen in Settings are the
 * minimum. While the skin temperature is low, Honor's own throttle level is calm and the last answer used (almost)
 * all its threads ("cores_busy" of the engine's run summary), one more core, the fastest one not in the set, is
 * added. Hot (or throttling) or a core that did not work (cores_busy well under the thread count) takes the last
 * added one away again. Thread count and mask change between two answers (EngineController.tuneLive).
 *
 * Temperature as in SamTweak: PowerManager.getThermalHeadroom(0) is linear, 0 = SEVERE - 30 C, 1 = SEVERE
 * (43 C skin on Honor); Honor's actual throttle step is Settings.System "ThermalTempLevel" (3 and up = throttling).
 */
object AutoCores {
    private const val SEVERE_C = 43f
    private const val POLL_MS = 2000L
    private const val HONOR_THROTTLING = 3
    private const val ADD_PAUSE_MS = 15_000L
    private const val BLOCK_AFTER_HEAT_MS = 60_000L
    private const val BLOCK_AFTER_WASTE_MS = 300_000L

    /** Skin temperature in C (null when the device gives none), Honor's throttle step, cores in use and busy (null: auto off). */
    data class Reading(val skinC: Float? = null, val honorLevel: Int = 0, val cores: Int? = null, val busy: Double? = null)

    private val _reading = MutableStateFlow(Reading())
    val reading: StateFlow<Reading> = _reading

    @Volatile private var tune = EngineSettings()
    private var appContext: Context? = null
    private val lock = Mutex()
    private var model: ModelInfo? = null
    private val extra = ArrayList<Int>()
    private var lastChange = 0L
    private var blockAddUntil = 0L

    fun start(context: Context, scope: CoroutineScope, settings: Flow<EngineSettings>) {
        appContext = context.applicationContext
        scope.launch { settings.collect { tune = it } }
        scope.launch {
            while (true) {
                val (skin, level) = readThermal()
                _reading.value = _reading.value.copy(skinC = skin, honorLevel = level)
                delay(POLL_MS)
            }
        }
    }

    private fun readThermal(): Pair<Float?, Int> {
        val ctx = appContext ?: return null to 0
        var skin: Float? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val h = runCatching { (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).getThermalHeadroom(0) }
                .getOrDefault(Float.NaN)
            if (!h.isNaN() && !h.isInfinite()) skin = SEVERE_C - 30f + 30f * h
        }
        val level = runCatching { Settings.System.getInt(ctx.contentResolver, "ThermalTempLevel", 0) }.getOrDefault(0)
        return skin to level
    }

    private fun apply(engine: EngineController, decodeMask: Long, prefillMask: Long) {
        engine.setCpuMasks(decodeMask, prefillMask)
    }

    /** Called before every answer, while no generation runs. Chooses the cores for this answer. */
    suspend fun beforeRun(engine: EngineController) = lock.withLock {
        val info = engine.loadedModel()
        val s = tune
        if (info == null || RemoteServer.isRemotePath(info.path)) {
            extra.clear(); model = null
            _reading.value = _reading.value.copy(cores = null, busy = null)
            return@withLock
        }
        if (model !== info) { // a new load starts from the cores of the settings
            model = info; extra.clear(); lastChange = 0L; blockAddUntil = 0L
        }
        val baseMask = CpuTopology.effective(s.cpuMask)
        val prefillMask = CpuTopology.effective(s.cpuMaskPrefill)
        if (!s.autoCores) {
            if (extra.isNotEmpty()) {
                val before = ArrayList(extra)
                extra.clear()
                val ok = engine.tuneLive(java.lang.Long.bitCount(baseMask), java.lang.Long.bitCount(prefillMask), null, decodeMask = baseMask, prefillMask = prefillMask)
                if (!ok) extra.addAll(before) // busy: the next answer tries again
            }
            _reading.value = _reading.value.copy(cores = null, busy = null)
            return@withLock
        }
        val (skin, level) = readThermal()
        val busy = engine.lastRunJson()
            ?.let { runCatching { JSONObject(it).optDouble("cores_busy", Double.NaN) }.getOrDefault(Double.NaN) }
            ?.takeIf { !it.isNaN() && it > 0.0 }
        val base = java.lang.Long.bitCount(baseMask)
        val threads = base + extra.size
        val now = SystemClock.elapsedRealtime()
        val hot = s.autoCoresHotTenths / 10f
        val cool = s.autoCoresCoolTenths / 10f
        val before = ArrayList(extra)
        var changed = false
        when {
            extra.isNotEmpty() && level >= HONOR_THROTTLING -> {
                extra.clear(); changed = true; blockAddUntil = now + BLOCK_AFTER_HEAT_MS
            }
            extra.isNotEmpty() && skin != null && skin >= hot -> {
                extra.removeAt(extra.size - 1); changed = true; blockAddUntil = now + BLOCK_AFTER_HEAT_MS
            }
            extra.isNotEmpty() && busy != null && busy < threads - 1.5 -> {
                extra.removeAt(extra.size - 1); changed = true; blockAddUntil = now + BLOCK_AFTER_WASTE_MS
            }
            skin != null && skin < cool && level < 2 && now >= blockAddUntil && now - lastChange >= ADD_PAUSE_MS &&
                (busy == null || busy >= threads - 0.7) -> {
                val next = CpuTopology.fastestFirst(engine.allowedCpus())
                    .firstOrNull { (baseMask shr it) and 1L == 0L && it !in extra }
                if (next != null) { extra.add(next); changed = true }
            }
        }
        var decodeMask = baseMask
        for (c in extra) decodeMask = decodeMask or (1L shl c)
        if (changed) {
            val ok = engine.tuneLive(java.lang.Long.bitCount(decodeMask), java.lang.Long.bitCount(prefillMask), null, decodeMask = decodeMask, prefillMask = prefillMask)
            if (ok) lastChange = now else { extra.clear(); extra.addAll(before) } // engine busy: nothing changed
        }
        _reading.value = Reading(skin, level, base + extra.size, busy)
    }
}
