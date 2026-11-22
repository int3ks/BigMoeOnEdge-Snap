package com.bigmoe.onedge.core

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
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
    /** Growth step, and the least a shrink takes back. */
    private const val STEP_MB = 250
    private const val SHRINK_MIN_MB = 500
    /** Free RAM above the reserve needed before the cache may grow by a step. */
    private const val HEADROOM_MB = 500
    /** The app's swap growing by this much between two samples counts as pressure. */
    private const val SWAP_RISE_MB = 16
    private const val GROW_EVERY_MS = 8_000L
    private const val QUIET_AFTER_SHRINK_MS = 30_000L

    private var target = 0
    private var model: ModelInfo? = null
    private var lastSwap = -1L
    private var lastChange = 0L
    private var quietUntil = 0L

    fun start(engine: EngineController, scope: CoroutineScope) {
        scope.launch {
            MemoryWatch.now.collect { now -> if (now != null) step(engine, now) }
        }
    }

    private suspend fun step(engine: EngineController, now: MemoryNow) {
        val info = engine.loadedModel()
        val cfg = info?.config
        if (info == null || cfg == null || cfg.cacheMode != CacheMode.AUTO || cfg.cacheMb <= 0) {
            model = null
            return
        }
        val (budget, resident) = engine.cacheMib()?.takeIf { it.size >= 2 }?.let { it[0] to it[1] } ?: return
        if (budget <= 0) return
        val t = SystemClock.elapsedRealtime()
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
        if (budget < target - STEP_MB) target = maxOf(min, budget)

        if (now.freeMb < reserve || swapRise >= SWAP_RISE_MB) {
            val next = maxOf(min, target - maxOf(SHRINK_MIN_MB, (reserve - now.freeMb).toInt()))
            if (next < target) {
                target = next
                engine.resizeCache(target)
                lastChange = t
            }
            quietUntil = t + QUIET_AFTER_SHRINK_MS
            return
        }
        val full = resident >= budget * 9 / 10 // growing an unfilled cache buys nothing
        if (t >= quietUntil && t - lastChange >= GROW_EVERY_MS && full && budget >= target - 64 &&
            now.freeMb > reserve + HEADROOM_MB && swapRise <= 0 && target < max
        ) {
            target = minOf(max, target + STEP_MB)
            engine.resizeCache(target)
            lastChange = t
        }
    }
}
