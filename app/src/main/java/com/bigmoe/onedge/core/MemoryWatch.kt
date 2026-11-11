package com.bigmoe.onedge.core

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.util.Properties

/** Free RAM right now, as Android reports it. [thresholdMb] is the level below which Android calls memory low. */
data class MemoryNow(val freeMb: Long, val thresholdMb: Long, val totalMb: Long, val low: Boolean)

/**
 * What was seen of the free RAM while a model was in use, remembered per model across app starts.
 * "Ended normally" = the model was unloaded while the app was alive. A run that never got there (the tablet froze and
 * was restarted, Android killed the app, the app was closed with the model loaded) is kept apart: its lowest value is
 * near the point where the device stops coping.
 */
data class ModelMemoryRecord(
    /** Free RAM just before the last load began and right after it finished, in MiB (0 = not seen). */
    val beforeLoadMb: Long = 0,
    val afterLoadMb: Long = 0,
    /** How the last load was configured, e.g. "NPU on, cache 4000 MiB". */
    val loadConfig: String = "",
    /** Lowest free RAM of all runs that ended normally (0 = none yet), and the configuration of that run. */
    val lowestOkMb: Long = 0,
    val lowestOkConfig: String = "",
    /** Lowest free RAM of the last run that did not end normally (0 = none), its configuration and when it was last seen alive. */
    val lowestAbortedMb: Long = 0,
    val abortedConfig: String = "",
    val abortedAt: Long = 0
)

/**
 * Samples the free RAM every [INTERVAL_MS] while a model is loading or loaded and keeps the lowest value of the run
 * on disk (a tiny properties file, synced), so the figure survives a frozen tablet. Costs one `getMemoryInfo` call per
 * sample. While the log is recorded, each sample is also a line in the log.
 */
object MemoryWatch {
    private const val INTERVAL_MS = 2000L
    private const val PERSIST_EVERY_MS = 10_000L
    private const val PERSIST_DROP_MB = 64L

    private val _now = MutableStateFlow<MemoryNow?>(null)
    val now: StateFlow<MemoryNow?> = _now.asStateFlow()

    private val _records = MutableStateFlow<Map<String, ModelMemoryRecord>>(emptyMap())
    /** Keyed by model path. */
    val records: StateFlow<Map<String, ModelMemoryRecord>> = _records.asStateFlow()

    private lateinit var file: File
    private var started = false

    private class Run(val path: String) {
        var config = ""
        var beforeLoadMb = 0L
        var afterLoadMb = 0L
        var minMb = Long.MAX_VALUE
        var lastPersistMinMb = Long.MAX_VALUE
        var lastPersistAt = 0L
    }

    fun start(context: Context, engineState: StateFlow<EngineState>) {
        if (started) return
        started = true
        val app = context.applicationContext
        file = File(app.filesDir, "memory-watch.properties")
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        Thread {
            runCatching { adoptAbortedRun() }
            var run: Run? = null
            while (true) {
                runCatching {
                    val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
                    val mb = 1024L * 1024L
                    val now = MemoryNow(info.availMem / mb, info.threshold / mb, info.totalMem / mb, info.lowMemory)
                    _now.value = now
                    val state = engineState.value
                    val path = when (state) {
                        is EngineState.Loading -> state.path
                        is EngineState.Ready -> state.info.path
                        else -> null
                    }
                    var current = run
                    if (current != null && current.path != path) {
                        finish(current)
                        current = null
                    }
                    if (path != null) {
                        val r = current ?: Run(path).also { it.beforeLoadMb = now.freeMb }
                        if (state is EngineState.Ready && r.afterLoadMb == 0L) {
                            r.afterLoadMb = now.freeMb
                            val c = state.info.config
                            r.config = (if (state.info.npuPrefillReady) "NPU on" else "NPU off") + ", cache " + when (c.cacheMode) {
                                CacheMode.FIXED -> "${c.cacheMb} MiB"
                                CacheMode.AUTO -> "auto"
                                CacheMode.OFF -> "off"
                            }
                            r.lastPersistAt = 0L // write the load figures now
                        }
                        if (now.freeMb < r.minMb) r.minMb = now.freeMb
                        val t = System.currentTimeMillis()
                        if (r.lastPersistMinMb - r.minMb >= PERSIST_DROP_MB || t - r.lastPersistAt >= PERSIST_EVERY_MS) {
                            persistOpenRun(r, t)
                            r.lastPersistMinMb = r.minMb
                            r.lastPersistAt = t
                        }
                        current = r
                        if (LogFiles.recording) {
                            LogFiles.note(
                                "mem: free ${now.freeMb} MiB, app ${appRssMb()} MiB, lowest this run ${r.minMb} MiB" +
                                    " (Android calls memory low under ${now.thresholdMb} MiB" + (if (now.low) ", LOW NOW" else "") + ")"
                            )
                        }
                    }
                    run = current
                }
                try { Thread.sleep(INTERVAL_MS) } catch (_: InterruptedException) { return@Thread }
            }
        }.apply { isDaemon = true; name = "memory-watch"; priority = Thread.MIN_PRIORITY }.start()
    }

    /** Android asked the app to give memory back ([level] of onTrimMemory); a line in the log while it is recorded. */
    fun noteTrim(level: Int) {
        if (LogFiles.recording) LogFiles.note("mem: Android asks for memory back (onTrimMemory level $level), free ${_now.value?.freeMb ?: -1} MiB")
    }

    /** Resident memory of this process (VmRSS) in MiB; shared device buffers of the NPU are not part of it. */
    private fun appRssMb(): Long = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmRSS:") }?.filter { it.isDigit() }?.toLongOrNull()?.div(1024L)
        }
    }.getOrNull() ?: -1L

    // ── the file ──────────────────────────────────────────────────────────────────────────────────────────────

    private fun load(): Properties = Properties().also { p ->
        runCatching { if (file.isFile) file.inputStream().use(p::load) }
    }

    private fun save(p: Properties) {
        runCatching {
            FileOutputStream(file).use { out ->
                p.store(out, "BigMoeOnEdge free-RAM figures per model")
                out.flush()
                out.fd.sync() // must be on flash before a freeze, that is the whole point
            }
        }
        publish(p)
    }

    private fun key(path: String): String =
        "m." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(path.toByteArray(Charsets.UTF_8))

    private fun persistOpenRun(r: Run, t: Long) {
        val p = load()
        p.setProperty("run.path", r.path)
        p.setProperty("run.config", r.config)
        p.setProperty("run.min", r.minMb.toString())
        p.setProperty("run.seen", t.toString())
        val k = key(r.path)
        if (r.beforeLoadMb > 0) p.setProperty("$k.before", r.beforeLoadMb.toString())
        if (r.afterLoadMb > 0) {
            p.setProperty("$k.after", r.afterLoadMb.toString())
            p.setProperty("$k.config", r.config)
        }
        save(p)
    }

    /** The model was unloaded: the run ended normally. */
    private fun finish(r: Run) {
        val p = load()
        listOf("run.path", "run.config", "run.min", "run.seen").forEach { p.remove(it) }
        // A run that never finished loading says nothing about what the device copes with.
        if (r.afterLoadMb > 0 && r.minMb != Long.MAX_VALUE) {
            val k = key(r.path)
            val old = p.getProperty("$k.ok")?.toLongOrNull()
            if (old == null || r.minMb < old) {
                p.setProperty("$k.ok", r.minMb.toString())
                p.setProperty("$k.okConfig", r.config)
            }
        }
        save(p)
    }

    /** At app start: a run still marked open never ended normally. */
    private fun adoptAbortedRun() {
        val p = load()
        val path = p.getProperty("run.path")
        if (path != null) {
            val k = key(path)
            p.getProperty("run.min")?.toLongOrNull()?.let { p.setProperty("$k.aborted", it.toString()) }
            p.setProperty("$k.abortedConfig", p.getProperty("run.config") ?: "")
            p.setProperty("$k.abortedAt", p.getProperty("run.seen") ?: "0")
            listOf("run.path", "run.config", "run.min", "run.seen").forEach { p.remove(it) }
            save(p)
        } else {
            publish(p)
        }
    }

    private fun publish(p: Properties) {
        val out = HashMap<String, ModelMemoryRecord>()
        val keys = p.stringPropertyNames().filter { it.startsWith("m.") }.map { it.substringBeforeLast('.') }.toSet()
        for (k in keys) {
            val path = runCatching {
                String(java.util.Base64.getUrlDecoder().decode(k.removePrefix("m.")), Charsets.UTF_8)
            }.getOrNull() ?: continue
            fun long(name: String) = p.getProperty("$k.$name")?.toLongOrNull() ?: 0L
            fun text(name: String) = p.getProperty("$k.$name") ?: ""
            out[path] = ModelMemoryRecord(
                beforeLoadMb = long("before"), afterLoadMb = long("after"), loadConfig = text("config"),
                lowestOkMb = long("ok"), lowestOkConfig = text("okConfig"),
                lowestAbortedMb = long("aborted"), abortedConfig = text("abortedConfig"), abortedAt = long("abortedAt")
            )
        }
        _records.value = out
    }
}
