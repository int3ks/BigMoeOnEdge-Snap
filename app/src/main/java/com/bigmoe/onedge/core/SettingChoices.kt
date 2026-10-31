package com.bigmoe.onedge.core

/**
 * The values the settings screen offers for the settings the device tuner measures. One list for both, so the tuner
 * only ever finds a value that can also be chosen by hand.
 */
object SettingChoices {
    /** Expert cache in MiB: 250 MiB steps up to 2000, 500 up to 5000, then 1000 up to 8000 (Auto and off come first on screen). */
    val CACHE_MB = listOf(500, 1000, 1250, 1500, 1750, 2000, 2500, 3000, 3500, 4000, 4500, 5000, 6000, 7000, 8000)
    /** Parallel I/O lanes; the engine allows up to 8. */
    val IO_LANES = (1..8).toList()
    /** Prompt batch width (ubatch) in tokens. */
    val BATCH_WIDTH = listOf(32, 64, 128, 256, 384, 512, 768, 1024, 1536, 2048)
    val NPU_LOADERS = (1..16).toList()
    val NPU_MIN_TOKENS = listOf(8, 16, 32, 64, 128, 256)
}
