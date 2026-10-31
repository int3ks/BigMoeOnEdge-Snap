package com.bigmoe.onedge.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceTunerTest {

    private fun climb(values: List<Int>, start: Int, speed: (Int) -> Double?, allowed: (Int) -> Boolean = { true }): List<TunePoint> =
        runBlocking { DeviceTuner.climb(values, start, allowed) { v -> TunePoint(v, speed(v)) } }

    @Test
    fun climbWalksUpWhileItGetsFaster() {
        val speed = mapOf(1 to 3.0, 2 to 5.0, 4 to 7.0, 8 to 7.1)
        val pts = climb(listOf(1, 2, 4, 8), 2) { speed[it] }
        assertEquals(4, DeviceTuner.best(pts, 2))
        // 8 was measured (it did not gain 3 %), 1 was never needed: the upper side was better.
        assertEquals(setOf(2, 4, 8), pts.map { it.value }.toSet())
    }

    @Test
    fun climbGoesDownWhenUpIsSlower() {
        val speed = mapOf(1 to 6.0, 2 to 5.0, 4 to 4.0, 8 to 3.0)
        val pts = climb(listOf(1, 2, 4, 8), 4) { speed[it] }
        assertEquals(1, DeviceTuner.best(pts, 4))
        assertEquals(setOf(1, 2, 4, 8), pts.map { it.value }.toSet())
    }

    @Test
    fun climbKeepsTheStartWithinNoise() {
        val speed = mapOf(1 to 4.0, 2 to 5.05, 4 to 5.0, 8 to 4.0)
        val pts = climb(listOf(1, 2, 4, 8), 4) { speed[it] }
        // 2 is 1 % faster than the start: noise, so the start stays.
        assertEquals(4, DeviceTuner.best(pts, 4))
    }

    @Test
    fun climbStopsAtFailuresAndNotAllowedValues() {
        val pts = climb(listOf(500, 1000, 2000, 4000), 1000, { if (it == 500) null else it / 1000.0 }, allowed = { it <= 2000 })
        assertEquals(2000, DeviceTuner.best(pts, 1000))
        assertTrue(pts.none { it.value == 4000 })
    }

    @Test
    fun startBetweenCandidatesUsesTheNearest() {
        assertEquals(2, DeviceTuner.nearestIndex(listOf(64, 128, 256, 512), 300))
    }

    @Test
    fun npuThresholdIsTheShortestLengthFromWhichTheNpuAlwaysWins() {
        val npu = mapOf(16 to 50.0, 32 to 90.0, 64 to 200.0, 128 to 400.0, 256 to 700.0)
        val cpu = mapOf(16 to 60.0, 32 to 95.0, 64 to 120.0, 128 to 130.0, 256 to 135.0)
        assertEquals(64, DeviceTuner.npuThreshold(npu, cpu))
        assertNull(DeviceTuner.npuThreshold(cpu, npu.mapValues { it.value * 10 }))
    }

    @Test
    fun medianOfTwoIsTheirMean() {
        assertEquals(5.0, DeviceTuner.median(listOf(4.0, 6.0))!!, 1e-9)
        assertEquals(3.0, DeviceTuner.median(listOf(9.0, 1.0, 3.0))!!, 1e-9)
    }
}
