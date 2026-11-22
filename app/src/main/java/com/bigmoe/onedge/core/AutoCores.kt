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
 * MINIMUM: a model opens with them, Auto adds cores on top while the device is cool and takes the added ones away
 * again when it gets hot. Only Honor's own throttling (Settings.System "ThermalTempLevel" 3 and up) takes cores below
 * the chosen ones (never below [MIN_CORES]); they come back as soon as it stops.
 *
 * A core is added (the fastest one not in use) while the skin temperature is below the "add" value, Honor's throttle
 * step is calm and the last answer of the current set gave a usable decode speed. The next usable answer then decides
 * whether the core stays: slower than [KEEP_RATIO] of the speed before the add, it is taken back and no core is added
 * for [BLOCK_AFTER_SLOWER_MS]. ("cores_busy" is no criterion: threads spinning at a barrier count as busy, so a busy
 * set is often a slow one.) Thread count and mask change between two answers (EngineController.tuneLive).
 *
 * Temperature as in SamTweak: PowerManager.getThermalHeadroom(0) is linear, 0 = SEVERE - 30 C, 1 = SEVERE
 * (43 C skin on Honor).
 */
object AutoCores {
    private const val SEVERE_C = 43f
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
        val (skin, level) = readThermal()
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
                        _reading.value = Reading(skin, level, java.lang.Long.bitCount(current), busy)
                        return@withLock
                    }
                }
                lastTps = tps
            }
        }
        val threads = java.lang.Long.bitCount(current)
        val hot = s.autoCoresHotTenths / 10f
        val cool = s.autoCoresCoolTenths / 10f
        val freq = CpuTopology.maxFreqKhz()
        fun slowestFirst(mask: Long) = (0 until 64).filter { (mask shr it) and 1L == 1L }
            .sortedWith(compareBy<Int> { freq.getOrElse(it) { 0L } }.thenByDescending { it })
        val extra = current and baseMask.inv()   // cores Auto added on top of the chosen ones
        val missing = baseMask and current.inv() // chosen cores Honor's throttling took away
        var next = current
        when {
            level >= HONOR_THROTTLING && threads > MIN_CORES -> {
                // Honor is throttling: shed two cores at once, added ones first, below the chosen ones if need be
                repeat(minOf(2, threads - MIN_CORES)) {
                    val pool = if (next and baseMask.inv() != 0L) next and baseMask.inv() else next
                    next = next and (1L shl slowestFirst(pool).first()).inv()
                }
                blockAddUntil = now + BLOCK_AFTER_HEAT_MS
                pendingAdd = null
            }
            skin != null && skin >= hot && extra != 0L -> {
                // Hot: an added core goes; the chosen ones stay
                next = next and (1L shl slowestFirst(extra).first()).inv()
                blockAddUntil = now + BLOCK_AFTER_HEAT_MS
                pendingAdd = null
            }
            missing != 0L && level < HONOR_THROTTLING && (skin == null || skin < hot) -> {
                // Honor stopped throttling: the chosen cores come back (no speed test, they are the minimum)
                next = next or missing
                pendingAdd = null
            }
            skin != null && skin < cool && skin < hot && level < 2 && now >= blockAddUntil &&
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
        _reading.value = Reading(skin, level, java.lang.Long.bitCount(current), busy)
    }
}
