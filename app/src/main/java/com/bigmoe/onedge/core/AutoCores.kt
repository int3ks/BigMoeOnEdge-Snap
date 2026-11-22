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
 * START value (a model opens with them, so it does not begin slowly while the device is still cool); from there the
 * set moves both ways. While the skin temperature is low, Honor's own throttle level is calm and the last answer used
 * (almost) all its threads ("cores_busy" of the engine's run summary), one more core, the fastest one not in the set,
 * is added. Hot (or throttling) or a core that did not work (cores_busy well under the thread count) takes the
 * slowest core of the set away, never below [MIN_CORES]. Thread count and mask change between two answers
 * (EngineController.tuneLive).
 *
 * Temperature as in SamTweak: PowerManager.getThermalHeadroom(0) is linear, 0 = SEVERE - 30 C, 1 = SEVERE
 * (43 C skin on Honor); Honor's actual throttle step is Settings.System "ThermalTempLevel" (3 and up = throttling).
 */
object AutoCores {
    private const val SEVERE_C = 43f
    private const val POLL_MS = 2000L
    private const val HONOR_THROTTLING = 3
    private const val MIN_CORES = 2
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
    /** The decode cores in use now (0 = not set yet) and the settings' mask they started from. */
    private var current = 0L
    private var lastBase = 0L
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
            current = 0L; model = null
            _reading.value = _reading.value.copy(cores = null, busy = null)
            return@withLock
        }
        val baseMask = CpuTopology.effective(s.cpuMask)
        val prefillMask = CpuTopology.effective(s.cpuMaskPrefill)
        if (model !== info || baseMask != lastBase) { // a new load, or other cores in Settings: start from the settings
            model = info; lastBase = baseMask; current = baseMask; lastChange = 0L; blockAddUntil = 0L
        }
        if (!s.autoCores) {
            if (current != baseMask) {
                val ok = engine.tuneLive(java.lang.Long.bitCount(baseMask), java.lang.Long.bitCount(prefillMask), null, decodeMask = baseMask, prefillMask = prefillMask)
                if (ok) current = baseMask // busy: the next answer tries again
            }
            _reading.value = _reading.value.copy(cores = null, busy = null)
            return@withLock
        }
        val (skin, level) = readThermal()
        val busy = engine.lastRunJson()
            ?.let { runCatching { JSONObject(it).optDouble("cores_busy", Double.NaN) }.getOrDefault(Double.NaN) }
            ?.takeIf { !it.isNaN() && it > 0.0 }
        val threads = java.lang.Long.bitCount(current)
        val now = SystemClock.elapsedRealtime()
        val hot = s.autoCoresHotTenths / 10f
        val cool = s.autoCoresCoolTenths / 10f
        val freq = CpuTopology.maxFreqKhz()
        fun slowestFirst(mask: Long) = (0 until 64).filter { (mask shr it) and 1L == 1L }
            .sortedWith(compareBy<Int> { freq.getOrElse(it) { 0L } }.thenByDescending { it })
        var next = current
        when {
            level >= HONOR_THROTTLING && threads > MIN_CORES -> {
                // Honor is throttling: shed two cores at once
                repeat(minOf(2, threads - MIN_CORES)) { next = next and (1L shl slowestFirst(next).first()).inv() }
                blockAddUntil = now + BLOCK_AFTER_HEAT_MS
            }
            skin != null && skin >= hot && threads > MIN_CORES -> {
                next = next and (1L shl slowestFirst(next).first()).inv()
                blockAddUntil = now + BLOCK_AFTER_HEAT_MS
            }
            busy != null && busy < threads - 1.5 && threads > MIN_CORES -> {
                next = next and (1L shl slowestFirst(next).first()).inv()
                blockAddUntil = now + BLOCK_AFTER_WASTE_MS
            }
            skin != null && skin < cool && level < 2 && now >= blockAddUntil && now - lastChange >= ADD_PAUSE_MS &&
                (busy == null || busy >= threads - 0.7) -> {
                val add = CpuTopology.fastestFirst(engine.allowedCpus()).firstOrNull { (current shr it) and 1L == 0L }
                if (add != null) next = next or (1L shl add)
            }
        }
        if (next != current) {
            val ok = engine.tuneLive(java.lang.Long.bitCount(next), java.lang.Long.bitCount(prefillMask), null, decodeMask = next, prefillMask = prefillMask)
            if (ok) { current = next; lastChange = now } // busy: nothing changed, the next answer tries again
        }
        _reading.value = Reading(skin, level, java.lang.Long.bitCount(current), busy)
    }
}
