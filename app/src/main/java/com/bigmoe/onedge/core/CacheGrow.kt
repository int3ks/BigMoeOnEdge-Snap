package com.bigmoe.onedge.core

import android.os.SystemClock
import com.bigmoe.onedge.data.local.datastore.EngineSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The growing expert cache (cache mode Auto). The model opens with the cache set in Settings or on its card, the
 * minimum. While the device keeps more than the reserve free and none of the app lands in swap (zram), the budget
 * climbs in steps; once free RAM falls under the reserve or the app's swap grows, it comes back down, never under
 * the minimum. The engine offers everything above the minimum to the kernel (MoeStreamConfig::cache_keep_mib), so
 * a sudden need (another app starting) is met by the kernel dropping the grown part at once; this controller,
 * at MemoryWatch's 2 s pace, then moves the budget to match and stops the cache from growing back into it.
 */
object CacheGrow {
    /** The kernel dropping the grown cache is told from the engine's budget lagging a step behind by this much. */
    private const val DROP_TOLERANCE_MB = 250
    /** The app's swap growing by this much between two samples counts as pressure. */
    private const val SWAP_RISE_MB = 16

    /** Pace and sizes come from Settings (live): step, interval, headroom, least shrink, pause after a shrink. */
    @Volatile private var tune = EngineSettings()

    /** The expert cache now as (budget, resident) in MiB, sampled with the memory watch; null without a local model. */
    private val _cache = MutableStateFlow<Pair<Int, Int>?>(null)
    val cache: StateFlow<Pair<Int, Int>?> = _cache

    private var target = 0
    private var model: ModelInfo? = null
    private var lastSwap = -1L
    private var lastChange = 0L
    private var quietUntil = 0L

    fun start(engine: EngineController, scope: CoroutineScope, settings: Flow<EngineSettings>) {
        scope.launch { settings.collect { tune = it } }
        scope.launch {
            MemoryWatch.now.collect { now ->
                if (now == null) return@collect
                val c = engine.cacheMib()?.takeIf { it.size >= 2 }
                _cache.value = c?.let { it[0] to it[1] }
                step(engine, now, c)
            }
        }
    }

    private suspend fun step(engine: EngineController, now: MemoryNow, cacheMib: IntArray?) {
        val info = engine.loadedModel()
        val cfg = info?.config
        if (info == null || cfg == null || cfg.cacheMode != CacheMode.AUTO || cfg.cacheMb <= 0) {
            model = null
            return
        }
        val (budget, resident) = cacheMib?.let { it[0] to it[1] } ?: return
        if (budget <= 0) return
        val t = SystemClock.elapsedRealtime()
        val tn = tune
        val stepMb = tn.cacheGrowStepMb
        val min = cfg.cacheMb
        if (model !== info) { // a new load: start from what it opened with
            model = info
            target = maxOf(min, budget)
            lastSwap = now.swapMb
            lastChange = t
            quietUntil = 0L
            return
        }
        val reserve = cfg.cacheFloorMb.coerceAtLeast(0)
        val max = Int.MAX_VALUE // the engine clamps to the full expert set; free RAM and the reserve are the limit
        val swapRise = if (now.swapMb >= 0 && lastSwap >= 0) now.swapMb - lastSwap else 0L
        lastSwap = now.swapMb
        // The kernel dropped part of the grown cache: the engine's budget followed it down. Take that as the target.
        if (budget < target - maxOf(DROP_TOLERANCE_MB, stepMb)) target = maxOf(min, budget)

        if (now.freeMb < reserve || swapRise >= SWAP_RISE_MB) {
            val next = maxOf(min, target - maxOf(tn.cacheShrinkMinMb, (reserve - now.freeMb).toInt()))
            if (next < target) {
                target = next
                engine.resizeCache(target)
                lastChange = t
            }
            quietUntil = t + tn.cacheQuietSec * 1000L
            return
        }
        val full = resident >= budget * 9 / 10 // growing an unfilled cache buys nothing
        if (t >= quietUntil && t - lastChange >= tn.cacheGrowEverySec * 1000L && full && budget >= target - 64 &&
            now.freeMb > reserve + tn.cacheGrowHeadroomMb && swapRise <= 0 && target < max
        ) {
            target = minOf(max, target + stepMb)
            engine.resizeCache(target)
            lastChange = t
        }
    }
}
