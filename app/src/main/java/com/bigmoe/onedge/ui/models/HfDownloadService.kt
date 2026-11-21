package com.bigmoe.onedge.ui.models

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.bigmoe.onedge.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

/**
 * Hugging Face downloads with several connections per file (HTTP ranges), straight into the models folder.
 *
 * Each job keeps its state in filesDir/hfdl/<id>.json (url, target, size, per-chunk position), so a job
 * survives a network drop, an app update or a crash and resumes where it stopped. The data goes into
 * `<target>.part`, preallocated to the full size; every chunk writes its own range. When all chunks are
 * done the file is renamed to its final name. Jobs run one after another (a split model's parts in order),
 * each with [connections] parallel ranges.
 */
object HfDl {
    data class Item(val id: String, val name: String, val done: Long, val total: Long, val bytesPerSec: Long, val state: String)

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items
    /** Bumped when a file landed in the models folder, for the model list to refresh. */
    val finished = MutableStateFlow(0)

    @Volatile var connections = 5

    internal fun dir(context: Context) = File(context.filesDir, "hfdl").also { it.mkdirs() }

    fun enqueue(context: Context, url: String, target: File, size: Long) {
        val id = System.currentTimeMillis().toString() + "_" + target.name.hashCode().toUInt().toString(16)
        val o = JSONObject().put("url", url).put("target", target.absolutePath).put("size", size).put("chunks", JSONArray())
        File(dir(context), "$id.json").writeText(o.toString())
        publish(context)
        start(context)
    }

    fun cancel(context: Context, id: String) {
        val f = File(dir(context), "$id.json")
        runCatching { File(JSONObject(f.readText()).getString("target") + ".part").delete() }
        f.delete()
        cancelled += id
        publish(context)
    }

    internal val cancelled = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    internal val live = java.util.concurrent.ConcurrentHashMap<String, Item>()

    /** The list the UI shows: jobs on disk, with live numbers for the running one. */
    fun publish(context: Context) {
        val files = dir(context).listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name } ?: emptyList()
        _items.value = files.mapNotNull { f ->
            val id = f.name.removeSuffix(".json")
            live[id] ?: runCatching {
                val o = JSONObject(f.readText())
                val ch = o.getJSONArray("chunks")
                var done = 0L
                for (i in 0 until ch.length()) { val c = ch.getJSONObject(i); done += c.getLong("pos") - c.getLong("start") }
                Item(id, File(o.getString("target")).name, done, o.getLong("size"), 0, if (done > 0) "paused" else "waiting")
            }.getOrNull()
        }
    }

    fun start(context: Context) {
        val i = Intent(context, HfDownloadService::class.java)
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
    }

    /** Resume pending jobs (at app start). */
    fun resume(context: Context) {
        publish(context)
        if (_items.value.isNotEmpty()) runCatching { start(context) }
    }
}

class HfDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running = false
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification("Starting…", 0, 0))
        if (!running) {
            running = true
            wake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bigmoe:hfdl").apply { acquire(12 * 60 * 60 * 1000L) }
            scope.launch {
                try {
                    while (isActive) {
                        val next = HfDl.dir(this@HfDownloadService).listFiles { f -> f.name.endsWith(".json") }
                            ?.minByOrNull { it.name } ?: break
                        runJob(next)
                    }
                } finally {
                    running = false
                    runCatching { wake?.release() }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun save(f: File, o: JSONObject) {
        val tmp = File(f.path + ".tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(f)
    }

    private suspend fun runJob(stateFile: File) {
        val id = stateFile.name.removeSuffix(".json")
        val o = JSONObject(stateFile.readText())
        val url = o.getString("url")
        val target = File(o.getString("target"))
        val part = File(target.path + ".part")
        var size = o.getLong("size")
        var attempt = 0
        while (true) {
            if (id in HfDl.cancelled || !stateFile.exists()) return
            try {
                if (size <= 0) {
                    size = probeSize(url)
                    o.put("size", size)
                }
                var chunks = o.getJSONArray("chunks")
                if (chunks.length() == 0) {
                    // Split into equal ranges, one per connection (a small file gets one).
                    val n = if (size < 64L shl 20) 1 else HfDl.connections.coerceIn(1, 16)
                    val step = size / n
                    chunks = JSONArray()
                    for (i in 0 until n) {
                        val s = i * step
                        val e = if (i == n - 1) size - 1 else (i + 1) * step - 1
                        chunks.put(JSONObject().put("start", s).put("end", e).put("pos", s))
                    }
                    o.put("chunks", chunks)
                    save(stateFile, o)
                }
                target.parentFile?.mkdirs()
                RandomAccessFile(part, "rw").use { if (it.length() != size) it.setLength(size) }
                downloadChunks(id, url, part, size, o, chunks, stateFile)
                if (id in HfDl.cancelled || !stateFile.exists()) { part.delete(); return }
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) error("rename failed")
                stateFile.delete()
                HfDl.live.remove(id)
                HfDl.publish(this)
                HfDl.finished.value++
                return
            } catch (e: Exception) {
                if (id in HfDl.cancelled || !stateFile.exists()) { part.delete(); HfDl.live.remove(id); HfDl.publish(this); return }
                attempt++
                val wait = if (e.message?.contains("429") == true) 30_000L else minOf(60_000L, 2_000L * attempt)
                HfDl.live[id] = (HfDl.live[id] ?: HfDl.Item(id, target.name, 0, size, 0, "")).copy(state = "retry in ${wait / 1000} s (${e.message})", bytesPerSec = 0)
                HfDl.publish(this)
                post("Retrying ${target.name}", 0, 0)
                delay(wait)
            }
        }
    }

    private fun probeSize(url: String): Long {
        val c = open(url, 0, 0)
        try {
            val cr = c.getHeaderField("Content-Range") ?: error("no size")
            return cr.substringAfter('/').trim().toLong()
        } finally { c.disconnect() }
    }

    private fun open(url: String, from: Long, to: Long): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 60000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "BigMoeOnEdge2")
        c.setRequestProperty("Range", "bytes=$from-$to")
        val code = c.responseCode
        if (code == 429) { c.disconnect(); error("HTTP 429 (too many connections)") }
        if (code != 206) { c.disconnect(); error("HTTP $code") }
        return c
    }

    private suspend fun downloadChunks(id: String, url: String, part: File, size: Long, o: JSONObject, chunks: JSONArray, stateFile: File) = coroutineScope {
        val pos = (0 until chunks.length()).map { AtomicLong(chunks.getJSONObject(it).getLong("pos")) }
        val total = { (0 until chunks.length()).sumOf { pos[it].get() - chunks.getJSONObject(it).getLong("start") } }
        val name = File(o.getString("target")).name
        val reporter = launch {
            var last = total()
            var t0 = System.nanoTime()
            var lastSave = System.nanoTime()
            while (isActive) {
                delay(1000)
                val now = total()
                val t = System.nanoTime()
                val rate = ((now - last) * 1_000_000_000L / maxOf(1L, t - t0))
                last = now; t0 = t
                HfDl.live[id] = HfDl.Item(id, name, now, size, rate, "${chunks.length()} connections")
                HfDl.publish(this@HfDownloadService)
                post(name, now, size, rate)
                if (t - lastSave > 2_000_000_000L) {
                    for (i in 0 until chunks.length()) chunks.getJSONObject(i).put("pos", pos[i].get())
                    if (stateFile.exists()) save(stateFile, o)
                    lastSave = t
                }
            }
        }
        try {
            (0 until chunks.length()).map { i ->
                async(Dispatchers.IO) {
                    val end = chunks.getJSONObject(i).getLong("end")
                    if (pos[i].get() > end) return@async
                    val c = open(url, pos[i].get(), end)
                    try {
                        RandomAccessFile(part, "rw").use { raf ->
                            raf.seek(pos[i].get())
                            val buf = ByteArray(1 shl 18)
                            c.inputStream.use { ins ->
                                while (pos[i].get() <= end) {
                                    if (id in HfDl.cancelled) error("cancelled")
                                    val want = minOf(buf.size.toLong(), end - pos[i].get() + 1).toInt()
                                    val n = ins.read(buf, 0, want)
                                    if (n < 0) break
                                    raf.write(buf, 0, n)
                                    pos[i].addAndGet(n.toLong())
                                }
                            }
                        }
                    } finally { c.disconnect() }
                    if (pos[i].get() <= end) error("connection closed early")
                }
            }.awaitAll()
        } finally {
            reporter.cancel()
            for (i in 0 until chunks.length()) chunks.getJSONObject(i).put("pos", pos[i].get())
            if (stateFile.exists()) save(stateFile, o)
        }
    }

    private fun notification(text: String, done: Long, total: Long, rate: Long = 0): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Model downloads", NotificationManager.IMPORTANCE_LOW))
        val pct = if (total > 0) (done * 100 / total).toInt() else 0
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_generation)
            .setContentTitle(text)
            .setContentText(if (total > 0) "$pct %  ·  ${done shr 20} of ${total shr 20} MiB  ·  ${rate shr 20} MiB/s" else "")
            .setProgress(100, pct, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun post(text: String, done: Long, total: Long, rate: Long = 0) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification(text, done, total, rate))
    }

    companion object {
        const val CHANNEL_ID = "model_downloads"
        const val NOTIFICATION_ID = 4711
    }
}
