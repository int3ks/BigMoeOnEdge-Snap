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
 * Auto CPU cores by Android's throttling signals (decode only, decided before each answer). The cores chosen in
 * Settings are the MINIMUM: a model opens with them, Auto adds cores on top while the device has thermal headroom and
 * takes the added ones away again when it gets close to throttling. Only real throttling (Android thermal status
 * MODERATE or worse, or Honor's own step, Settings.System "ThermalTempLevel" 3 and up) takes cores below the chosen
 * ones (never below [MIN_CORES]); they come back as soon as it stops.
 *
 * Signals, the same on every device (no temperatures: the old skin °C was a conversion calibrated on the Honor):
 * PowerManager.getThermalHeadroom(0), where 1.0 means the device reaches SEVERE throttling, and
 * PowerManager.currentThermalStatus (0 NONE, 1 LIGHT, 2 MODERATE, ...).
 *
 * A core is added (the fastest one not in use) while the headroom is below [ADD_BELOW], the status is NONE, Honor's
 * step is calm and the last answer of the current set gave a usable decode speed. From [TAKE_AWAY_AT] or status LIGHT
 * an added core goes again. The next usable answer then decides
 * whether the core stays: slower than [KEEP_RATIO] of the speed before the add, it is taken back and no core is added
 * for [BLOCK_AFTER_SLOWER_MS]. ("cores_busy" is no criterion: threads spinning at a barrier count as busy, so a busy
 * set is often a slow one.) Thread count and mask change between two answers (EngineController.tuneLive).
 */
object AutoCores {
    private const val ADD_BELOW = 0.70f
    private const val TAKE_AWAY_AT = 0.85f
    private const val STATUS_LIGHT = 1
    private const val STATUS_MODERATE = 2
    private const val POLL_MS = 2000L
    private const val HONOR_THROTTLING = 3
    private const val MIN_CORES = 2
    private const val ADD_PAUSE_MS = 15_000L
    private const val BLOCK_AFTER_HEAT_MS = 60_000L
    private const val BLOCK_AFTER_SLOWER_MS = 300_000L
    /** An answer this short says little about the decode speed (warm-up, a tool call). */
    private const val MIN_SAMPLE_TOKENS = 48
    /** An added core stays when the next answer reaches at least this share of the speed before the add. */
    private const val KEEP_RATIO = 0.9

    /** Thermal headroom (1.0 = severe throttling; null when the device gives none), Android's thermal status, Honor's
     *  throttle step, cores in use and busy (null: auto off). */
    data class Thermal(val headroom: Float? = null, val status: Int = 0, val honorLevel: Int = 0)
    data class Reading(val headroom: Float? = null, val status: Int = 0, val honorLevel: Int = 0, val cores: Int? = null, val busy: Double? = null)

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
    /** The run summary last looked at (a summary is counted once), the decode t/s of the current set (null: no usable
     *  answer yet), and the core added last with the speed before it, while its first answer is awaited. */
    private var lastJson: String? = null
    private var lastTps: Double? = null
    private var pendingAdd: Int? = null
    private var tpsBeforeAdd = 0.0

    fun start(context: Context, scope: CoroutineScope, settings: Flow<EngineSettings>) {
        appContext = context.applicationContext
        scope.launch { settings.collect { tune = it } }
        scope.launch {
            while (true) {
                val th = readThermal()
                _reading.value = _reading.value.copy(headroom = th.headroom, status = th.status, honorLevel = th.honorLevel)
                delay(POLL_MS)
            }
        }
    }

    private fun readThermal(): Thermal {
        val ctx = appContext ?: return Thermal()
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        var headroom: Float? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val h = runCatching { pm?.getThermalHeadroom(0) ?: Float.NaN }.getOrDefault(Float.NaN)
            if (!h.isNaN() && !h.isInfinite() && h >= 0f) headroom = h
        }
        val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            runCatching { pm?.currentThermalStatus ?: 0 }.getOrDefault(0) else 0
        val level = runCatching { Settings.System.getInt(ctx.contentResolver, "ThermalTempLevel", 0) }.getOrDefault(0)
        return Thermal(headroom, status, level)
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
            lastTps = null; pendingAdd = null; lastJson = engine.lastRunJson()
        }
        if (!s.autoCores) {
            if (current != baseMask) {
                val ok = engine.tuneLive(java.lang.Long.bitCount(baseMask), java.lang.Long.bitCount(prefillMask), null, decodeMask = baseMask, prefillMask = prefillMask)
                if (ok) current = baseMask // busy: the next answer tries again
            }
            lastTps = null; pendingAdd = null
            _reading.value = _reading.value.copy(cores = null, busy = null)
            return@withLock
        }
        val th = readThermal()
        val headroom = th.headroom
        val level = th.honorLevel
        // Real throttling: Android's status MODERATE or worse, or Honor's own step 3 and up.
        val throttling = th.status >= STATUS_MODERATE || level >= HONOR_THROTTLING
        val warm = th.status >= STATUS_LIGHT || (headroom != null && headroom >= TAKE_AWAY_AT)
        val now = SystemClock.elapsedRealtime()
        // The last answer ran with the current set (changes happen only here, before an answer).
        val json = engine.lastRunJson()
        var busy: Double? = _reading.value.busy
        if (json != null && json != lastJson) {
            lastJson = json
            val o = runCatching { JSONObject(json) }.getOrNull()
            busy = o?.optDouble("cores_busy", Double.NaN)?.takeIf { !it.isNaN() && it > 0.0 }
            val gen = o?.optDouble("n_generated", 0.0) ?: 0.0
            val tps = o?.optDouble("decode_tps", Double.NaN)?.takeIf { !it.isNaN() && it > 0.0 }
            if (tps != null && gen >= MIN_SAMPLE_TOKENS) {
                val added = pendingAdd
                if (added != null) {
                    pendingAdd = null
                    if (tps < tpsBeforeAdd * KEEP_RATIO && (current shr added) and 1L == 1L) {
                        // The added core made it slower: back to the set before it, and no new try for a while.
                        val back = current and (1L shl added).inv()
                        if (engine.tuneLive(java.lang.Long.bitCount(back), java.lang.Long.bitCount(prefillMask), null, decodeMask = back, prefillMask = prefillMask)) {
                            current = back; lastChange = now
                        }
                        blockAddUntil = now + BLOCK_AFTER_SLOWER_MS
                        lastTps = tpsBeforeAdd
                        _reading.value = Reading(headroom, th.status, level, java.lang.Long.bitCount(current), busy)
                        return@withLock
                    }
                }
                lastTps = tps
            }
        }
        val threads = java.lang.Long.bitCount(current)
        val freq = CpuTopology.maxFreqKhz()
        fun slowestFirst(mask: Long) = (0 until 64).filter { (mask shr it) and 1L == 1L }
            .sortedWith(compareBy<Int> { freq.getOrElse(it) { 0L } }.thenByDescending { it })
        val extra = current and baseMask.inv()   // cores Auto added on top of the chosen ones
        val missing = baseMask and current.inv() // chosen cores throttling took away
        var next = current
        when {
            throttling && threads > MIN_CORES -> {
                // The device is throttling: shed two cores at once, added ones first, below the chosen ones if need be
                repeat(minOf(2, threads - MIN_CORES)) {
                    val pool = if (next and baseMask.inv() != 0L) next and baseMask.inv() else next
                    next = next and (1L shl slowestFirst(pool).first()).inv()
                }
                blockAddUntil = now + BLOCK_AFTER_HEAT_MS
                pendingAdd = null
            }
            warm && extra != 0L -> {
                // Close to throttling: an added core goes; the chosen ones stay
                next = next and (1L shl slowestFirst(extra).first()).inv()
                blockAddUntil = now + BLOCK_AFTER_HEAT_MS
                pendingAdd = null
            }
            missing != 0L && !throttling && !warm -> {
                // Throttling stopped: the chosen cores come back (no speed test, they are the minimum)
                next = next or missing
                pendingAdd = null
            }
            headroom != null && headroom < ADD_BELOW && th.status == 0 && level < 2 && now >= blockAddUntil &&
                now - lastChange >= ADD_PAUSE_MS && pendingAdd == null && lastTps != null -> {
                val add = CpuTopology.fastestFirst(engine.allowedCpus()).firstOrNull { (current shr it) and 1L == 0L }
                if (add != null) {
                    next = next or (1L shl add)
                    pendingAdd = add
                    tpsBeforeAdd = lastTps ?: 0.0
                }
            }
        }
        if (next != current) {
            val ok = engine.tuneLive(java.lang.Long.bitCount(next), java.lang.Long.bitCount(prefillMask), null, decodeMask = next, prefillMask = prefillMask)
            if (ok) { current = next; lastChange = now; if (pendingAdd == null) lastTps = null }
            else pendingAdd = null // busy: nothing changed, the next answer tries again
        }
        _reading.value = Reading(headroom, th.status, level, java.lang.Long.bitCount(current), busy)
    }
}
