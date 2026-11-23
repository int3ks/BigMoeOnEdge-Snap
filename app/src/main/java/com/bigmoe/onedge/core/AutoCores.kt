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
 * Auto CPU cores by Android's throttling signals (decode cores, also while an answer runs). The cores chosen in
 * Settings are the MINIMUM: a model opens with them, Auto adds cores on top while the device has thermal headroom and
 * takes the added ones away again when it gets close to throttling. Only real throttling (Android thermal status
 * MODERATE or worse, or Honor's own step, Settings.System "ThermalTempLevel" 3 and up) takes cores below the chosen
 * ones (never below [MIN_CORES]); they come back as soon as it stops. With added cores in use, throttling first only
 * takes those away (and blocks adding for [BLOCK_AFTER_SLOWER_MS]); if it goes on, Auto goes below the chosen ones.
 *
 * Signals, the same on every device (no temperatures): PowerManager.getThermalHeadroom(0), where 1.0 means the device
 * reaches SEVERE throttling, and PowerManager.currentThermalStatus (0 NONE, 1 LIGHT, 2 MODERATE, ...). Status LIGHT
 * is no heat signal of its own (the MagicPad 3 Pro reports it permanently under load, fan or not, at about 80 %).
 *
 * Every [POLL_MS] (and before each answer) Auto decides; the engine takes a change between two tokens
 * (EngineController.requestCores), so it acts in the middle of a long answer too. The speed comes from the engine's
 * live counters (tokens and decode time, prefill excluded): a window closes every [WINDOW_TOKENS] tokens of one core
 * set. A core is added (the fastest one not in use) while the headroom is below [ADD_BELOW], the status is at most
 * LIGHT, Honor's step is below 2 and the current set has [WINDOWS] windows. Once the engine took it, its next
 * [WINDOWS] windows decide: slower than [KEEP_RATIO] of the speed before, it is taken back and no core is added for
 * [BLOCK_AFTER_SLOWER_MS]. From [TAKE_AWAY_AT] an added core goes again. Heat steps are at least [HEAT_STEP_MS]
 * apart, since the thermal signals lag behind a change.
 */
object AutoCores {
    private const val ADD_BELOW = 0.90f
    private const val TAKE_AWAY_AT = 0.95f
    private const val STATUS_LIGHT = 1
    private const val STATUS_MODERATE = 2
    private const val POLL_MS = 2000L
    private const val HONOR_WARM = 2
    private const val HONOR_THROTTLING = 3
    private const val MIN_CORES = 2
    private const val ADD_PAUSE_MS = 15_000L
    private const val HEAT_STEP_MS = 20_000L
    private const val BLOCK_AFTER_HEAT_MS = 60_000L
    private const val BLOCK_AFTER_SLOWER_MS = 300_000L
    /** Tokens per speed window (one core set, decode time only). */
    private const val WINDOW_TOKENS = 48
    /** Windows that give a set's speed (before an add, and after it to decide). */
    private const val WINDOWS = 2
    /** An added core stays when its windows reach at least this share of the speed before the add. */
    private const val KEEP_RATIO = 0.95

    /** Thermal headroom (1.0 = severe throttling; null when the device gives none), Android's thermal status, Honor's
     *  throttle step, cores in use and busy (null: auto off). */
    data class Thermal(val headroom: Float? = null, val status: Int = 0, val honorLevel: Int = 0)
    data class Reading(val headroom: Float? = null, val status: Int = 0, val honorLevel: Int = 0, val cores: Int? = null, val busy: Double? = null)

    private val _reading = MutableStateFlow(Reading())
    val reading: StateFlow<Reading> = _reading

    @Volatile private var tune = EngineSettings()
    @Volatile private var engineRef: EngineController? = null
    private var appContext: Context? = null
    private val lock = Mutex()
    private var model: ModelInfo? = null
    /** The decode cores asked for (0 = not set yet), the settings' mask they started from, the prefill cores. */
    private var current = 0L
    private var lastBase = 0L
    private var prefill = 0L
    private var lastChange = 0L
    private var lastHeatStep = 0L
    private var blockAddUntil = 0L
    /** Core requests the engine took (its counter), and whether one is sent but not taken yet. */
    private var seenTaken = -1L
    private var awaiting = false
    /** The open speed window (engine counters at its start; -1 = none) and the closed windows of the current set. */
    private var winTok = -1L
    private var winNs = 0L
    private val speeds = ArrayDeque<Double>()
    /** The core added last with the speed before it, while its windows are awaited. */
    private var pendingAdd: Int? = null
    private var tpsBeforeAdd = 0.0
    private var lastJson: String? = null

    fun start(context: Context, scope: CoroutineScope, settings: Flow<EngineSettings>) {
        appContext = context.applicationContext
        scope.launch { settings.collect { tune = it } }
        scope.launch {
            while (true) {
                val th = readThermal()
                _reading.value = _reading.value.copy(headroom = th.headroom, status = th.status, honorLevel = th.honorLevel)
                val engine = engineRef
                if (engine != null) runCatching { lock.withLock { step(engine, th) } }
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

    /** Called before every answer, while no generation runs: a new load or other cores in Settings start over. */
    suspend fun beforeRun(engine: EngineController) = lock.withLock {
        engineRef = engine
        val info = engine.loadedModel()
        val s = tune
        if (info == null || RemoteServer.isRemotePath(info.path)) {
            current = 0L; model = null
            _reading.value = _reading.value.copy(cores = null, busy = null)
            return@withLock
        }
        val baseMask = CpuTopology.effective(s.cpuMask)
        val prefillMask = CpuTopology.effective(s.cpuMaskPrefill)
        if (model !== info || baseMask != lastBase || prefillMask != prefill) {
            model = info; lastBase = baseMask; prefill = prefillMask; current = baseMask
            lastChange = 0L; lastHeatStep = 0L; blockAddUntil = 0L
            pendingAdd = null; lastJson = engine.lastRunJson()
            if (s.autoCores) request(engine, baseMask, 0L) // the engine may still hold cores Auto chose before
            else { awaiting = false; resetSpeed(engine) }
        }
        if (!s.autoCores) {
            if (current != baseMask) {
                val ok = engine.tuneLive(java.lang.Long.bitCount(baseMask), java.lang.Long.bitCount(prefillMask), null, decodeMask = baseMask, prefillMask = prefillMask)
                if (ok) current = baseMask // busy: the next answer tries again
            }
            pendingAdd = null; awaiting = false
            _reading.value = _reading.value.copy(cores = null, busy = null)
            return@withLock
        }
        step(engine, readThermal())
    }

    /** The engine's counters now: the window starts here, the closed windows belong to the set before. */
    private fun resetSpeed(engine: EngineController) {
        val live = engine.liveDecode()
        winTok = live?.get(0) ?: -1L
        winNs = live?.get(1) ?: 0L
        seenTaken = live?.get(2) ?: -1L
        speeds.clear()
    }

    /** Asks the engine for [mask] as the decode cores (taken between two tokens); the speed windows start over. */
    private fun request(engine: EngineController, mask: Long, now: Long): Boolean {
        val live = engine.liveDecode()
        if (!engine.requestCores(mask, prefill)) return false
        current = mask
        lastChange = now
        awaiting = true
        seenTaken = live?.get(2) ?: -1L
        winTok = -1L
        speeds.clear()
        return true
    }

    private fun mean(n: Int): Double? = if (speeds.size < n) null else speeds.takeLast(n).average()

    private fun step(engine: EngineController, th: Thermal) {
        val info = model ?: return
        if (!tune.autoCores || current == 0L || engine.loadedModel() !== info) return
        val now = SystemClock.elapsedRealtime()
        val baseMask = lastBase

        // Busy cores of the last finished answer (display only).
        engine.lastRunJson()?.takeIf { it != lastJson }?.let { json ->
            lastJson = json
            val busy = runCatching { JSONObject(json).optDouble("cores_busy", Double.NaN) }.getOrNull()
            _reading.value = _reading.value.copy(busy = busy?.takeIf { !it.isNaN() && it > 0.0 })
        }

        // Speed windows of the current set, once the engine took it.
        engine.liveDecode()?.let { live ->
            val tok = live[0]; val ns = live[1]; val taken = live[2]
            if (awaiting && taken != seenTaken) { awaiting = false; winTok = -1L }
            seenTaken = taken
            if (!awaiting) {
                if (winTok < 0L || tok < winTok) { winTok = tok; winNs = ns }
                else if (tok - winTok >= WINDOW_TOKENS && ns > winNs) {
                    speeds.addLast((tok - winTok) * 1e9 / (ns - winNs))
                    while (speeds.size > WINDOWS * 2) speeds.removeFirst()
                    winTok = tok; winNs = ns
                }
            }
        }

        val headroom = th.headroom
        val level = th.honorLevel
        // Real throttling: Android's status MODERATE or worse, or Honor's own step 3 and up.
        val throttling = th.status >= STATUS_MODERATE || level >= HONOR_THROTTLING
        val warm = headroom != null && headroom >= TAKE_AWAY_AT
        val threads = java.lang.Long.bitCount(current)
        val freq by lazy { CpuTopology.maxFreqKhz() }
        fun slowestFirst(mask: Long) = (0 until 64).filter { (mask shr it) and 1L == 1L }
            .sortedWith(compareBy<Int> { freq.getOrElse(it) { 0L } }.thenByDescending { it })
        val extra = current and baseMask.inv()   // cores Auto added on top of the chosen ones
        val missing = baseMask and current.inv() // chosen cores throttling took away
        val heatStepDue = now - lastHeatStep >= HEAT_STEP_MS
        var next = current
        var heat = false
        val added = pendingAdd
        when {
            throttling && extra != 0L -> {
                // Throttling with added cores: back to the chosen ones first, and no new try for a while
                next = current and extra.inv()
                blockAddUntil = now + BLOCK_AFTER_SLOWER_MS
                pendingAdd = null; heat = true
            }
            throttling && threads > MIN_CORES && heatStepDue -> {
                // Still throttling without added cores: shed two at once, below the chosen ones (never below MIN_CORES)
                repeat(minOf(2, threads - MIN_CORES)) { next = next and (1L shl slowestFirst(next).first()).inv() }
                blockAddUntil = now + BLOCK_AFTER_HEAT_MS
                pendingAdd = null; heat = true
            }
            warm && extra != 0L && heatStepDue -> {
                // Close to throttling: an added core goes; the chosen ones stay
                next = current and (1L shl slowestFirst(extra).first()).inv()
                blockAddUntil = now + BLOCK_AFTER_HEAT_MS
                pendingAdd = null; heat = true
            }
            missing != 0L && !throttling && !warm && heatStepDue -> {
                // Throttling stopped: the chosen cores come back (no speed test, they are the minimum)
                next = current or missing
                pendingAdd = null; heat = true
            }
            added != null -> {
                // The added core's own windows decide whether it stays.
                val after = mean(WINDOWS)
                if (after != null) {
                    pendingAdd = null
                    if (after < tpsBeforeAdd * KEEP_RATIO && (current shr added) and 1L == 1L) {
                        next = current and (1L shl added).inv()
                        blockAddUntil = now + BLOCK_AFTER_SLOWER_MS
                    }
                }
            }
            headroom != null && headroom < ADD_BELOW && th.status <= STATUS_LIGHT && level < HONOR_WARM &&
                now >= blockAddUntil && now - lastChange >= ADD_PAUSE_MS && !awaiting -> {
                val before = mean(WINDOWS)
                val add = if (before == null) null
                    else CpuTopology.fastestFirst(engine.allowedCpus()).firstOrNull { (current shr it) and 1L == 0L }
                if (before != null && add != null) {
                    next = current or (1L shl add)
                    pendingAdd = add
                    tpsBeforeAdd = before
                }
            }
        }
        if (next != current) {
            if (request(engine, next, now)) { if (heat) lastHeatStep = now }
            else pendingAdd = null
        }
        _reading.value = _reading.value.copy(cores = java.lang.Long.bitCount(current))
    }
}
