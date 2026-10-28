package com.bigmoe.onedge.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updates from the fork's GitHub releases. A release is published by hand once a build is approved
 * (.github/workflows/release.yml takes the APK of the latest successful NPU build, tag v0.6.N); release assets
 * download without a GitHub login, unlike Actions artifacts. The APK is signed with the same release key, so
 * Android installs it as an update.
 */
object AppUpdater {
    const val REPO = "int3ks/BigMoeOnEdge-Snap"
    private const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val UPDATE_DIR = "updates" // under cacheDir, shared through the FileProvider (file_paths.xml)

    data class Release(val version: String, val apkName: String, val apkUrl: String, val sizeBytes: Long, val notes: String)

    /** "0.6.12" or "v0.6.12" -> [0, 6, 12]; null when the text is not a dotted version. */
    fun parseVersion(text: String): List<Int>? {
        val parts = text.trim().removePrefix("v").removePrefix("V").split('.')
        if (parts.isEmpty()) return null
        return parts.map { it.toIntOrNull() ?: return null }
    }

    /** True when [candidate] is a higher version than [current] (missing trailing parts count as 0). */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parseVersion(candidate) ?: return false
        val b = parseVersion(current) ?: return true
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** The release in GitHub's `releases/latest` JSON, with its first .apk asset; null when it has none. */
    fun parseLatest(json: String): Release? {
        val o = JSONObject(json)
        val version = o.optString("tag_name").removePrefix("v").ifBlank { return null }
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name")
            if (name.endsWith(".apk", ignoreCase = true)) {
                return Release(version, name, a.optString("browser_download_url"), a.optLong("size", -1L), o.optString("body"))
            }
        }
        return null
    }

    fun installedVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0"

    suspend fun fetchLatest(): Release = withContext(Dispatchers.IO) {
        val c = (URL(LATEST_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "BigMoeOnEdge-Snap")
        }
        try {
            when (val code = c.responseCode) {
                200 -> parseLatest(c.inputStream.bufferedReader().use { it.readText() })
                    ?: throw IOException("The latest release has no APK")
                404 -> throw IOException("No release published yet")
                403 -> throw IOException("GitHub rate limit reached, try again later")
                else -> throw IOException("GitHub answered HTTP $code")
            }
        } finally {
            c.disconnect()
        }
    }

    /** Downloads the release's APK into the cache (replacing older downloads) and returns the file. */
    suspend fun download(context: Context, release: Release, onProgress: (copied: Long, total: Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, UPDATE_DIR).apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, release.apkName.replace(Regex("[^A-Za-z0-9._-]"), "_"))
            val part = File(dir, target.name + ".part")
            val c = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true // GitHub redirects the asset to its download host
                setRequestProperty("User-Agent", "BigMoeOnEdge-Snap")
            }
            try {
                if (c.responseCode != 200) throw IOException("Download failed: HTTP ${c.responseCode}")
                val total = c.contentLengthLong.takeIf { it > 0 } ?: release.sizeBytes
                c.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            copied += n
                            onProgress(copied, total)
                        }
                    }
                }
            } finally {
                c.disconnect()
            }
            if (!part.renameTo(target)) throw IOException("Could not store the download")
            target
        }

    /** Android asks once per app before it lets the app install packages ("Install unknown apps"). */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
