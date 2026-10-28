package com.bigmoe.onedge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {
    @Test
    fun parsesVersions() {
        assertEquals(listOf(0, 6, 12), AppUpdater.parseVersion("v0.6.12"))
        assertEquals(listOf(0, 6, 0), AppUpdater.parseVersion("0.6.0"))
        assertNull(AppUpdater.parseVersion("0.6.x"))
        assertNull(AppUpdater.parseVersion(""))
    }

    @Test
    fun comparesNumericallyNotAsText() {
        assertTrue(AppUpdater.isNewer("0.6.10", "0.6.9"))
        assertTrue(AppUpdater.isNewer("0.7.0", "0.6.42"))
        assertTrue(AppUpdater.isNewer("0.6.1", "0.6"))
        assertFalse(AppUpdater.isNewer("0.6.2", "0.6.2"))
        assertFalse(AppUpdater.isNewer("0.6.1", "0.6.2"))
        assertFalse(AppUpdater.isNewer("garbage", "0.6.2"))
    }

    @Test
    fun picksTheApkAssetOfTheLatestRelease() {
        val json = """
            {"tag_name": "v0.6.5", "body": "notes", "assets": [
              {"name": "checksums.txt", "browser_download_url": "https://x/c.txt", "size": 10},
              {"name": "BigMoeOnEdge-Snap-NPU-0.6.5-abc1234.apk", "browser_download_url": "https://x/a.apk", "size": 17000000}
            ]}
        """.trimIndent()
        val r = AppUpdater.parseLatest(json)!!
        assertEquals("0.6.5", r.version)
        assertEquals("BigMoeOnEdge-Snap-NPU-0.6.5-abc1234.apk", r.apkName)
        assertEquals("https://x/a.apk", r.apkUrl)
        assertEquals(17000000L, r.sizeBytes)
        assertNull(AppUpdater.parseLatest("""{"tag_name": "v0.6.5", "assets": []}"""))
    }
}
