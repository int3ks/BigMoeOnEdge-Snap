package com.bigmoe.onedge.core

import java.io.File

/**
 * The phone's CPU cores, and the default core choice for the engine's threads: the first two and the last two cores
 * (on a Snapdragon 8 Elite Gen 5: cpu0, cpu1 and the two prime cores cpu6, cpu7). A stored mask of 0 means that
 * default, so a fresh install and "Reset to default" behave the same.
 */
object CpuTopology {
    /** Number of CPU cores as the kernel counts them (cpu0 ... cpuN-1). */
    val coreCount: Int by lazy {
        File("/sys/devices/system/cpu").listFiles { f -> f.isDirectory && f.name.matches(Regex("cpu\\d+")) }?.size
            ?.takeIf { it > 0 } ?: Runtime.getRuntime().availableProcessors()
    }

    /** Bit i = cpu i: the first two and the last two cores (all of them on a phone with four or fewer). */
    fun defaultMask(cores: Int = coreCount): Long {
        val n = cores.coerceIn(1, 64)
        if (n <= 4) return (1L shl n) - 1
        return 0b11L or (0b11L shl (n - 2))
    }

    /** The mask the engine uses: the stored one, or the default when none is stored. */
    fun effective(mask: Long): Long = if (mask != 0L) mask else defaultMask()
}
