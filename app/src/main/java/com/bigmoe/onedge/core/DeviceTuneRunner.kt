package com.bigmoe.onedge.core

import com.bigmoe.onedge.data.local.datastore.EngineSettings
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** What the tuner is doing right now, for the screen. */
data class TuneStatus(
    val step: Int,
    val steps: Int,
    val param: TuneParam?,
    val value: Int?,
    val text: String,
    /** The last measured tokens/s and what it was. */
    val last: String? = null,
    val startedAtMs: Long = System.currentTimeMillis()
)

/**
 * Runs the device tuner ([DeviceTuner]) on the real engine: loads the chosen model, generates with the test prompts
 * and times it. Guess ahead is off for every measurement (it would measure the text, not the device), the context is
 * at least 4096 tokens, and nothing is written to the settings: the caller applies the results it wants.
 *
 * Live parameters (threads, expert cache) are changed on the loaded model; the others reload it. After a reload or a
 * cache change one run is discarded, so the expert cache is filled before it is measured. Every value is measured
 * [RUNS] times and the median counts.
 */
class DeviceTuneRunner(
    private val engine: EngineController,
    /** Free RAM in MiB right now (for the cache's upper limit). */
    private val availRamMb: () -> Long,
    /** Allowed CPU cores, fastest first (bit numbers). */
    private val coresFastestFirst: () -> List<Int>,
    /** Called before a value that needs a reload (a crash there is remembered) and with null after it. */
    private val riskyPoint: suspend (TuneParam?, Int?) -> Unit = { _, _ -> }
) {
    private var loadedWith: EngineSettings? = null
    private var needsWarmup = false
    private var runNonce = 0

    fun coreMask(n: Int): Long = coresFastestFirst().take(n.coerceAtLeast(1)).fold(0L) { m, c -> m or (1L shl c) }

    /**
     * Tunes [params] for [modelPath], starting from [start]. [onStatus] and [onResult] report progress and each
     * parameter's result as soon as it is known. Returns the drift note (device got slower), if any.
     */
    suspend fun run(
        modelPath: String,
        isMoe: Boolean,
        npuAvailable: Boolean,
        start: EngineSettings,
        params: List<TuneParam>,
        onStatus: (TuneStatus) -> Unit,
        onResult: (TuneResult) -> Unit,
        /** What the previous model is loaded with again at the end: the user's own settings. */
        restore: EngineSettings = start
    ): String? {
        val previous = engine.loadedModel()
        var cur = start.copy(spec = SpecSource.OFF, contextLength = maxOf(start.contextLength, 4096))
        val cores = coresFastestFirst().size.coerceAtLeast(1)
        val todo = params.filter { (!it.moeOnly || isMoe) && (!it.npuOnly || npuAvailable) }
        val began = System.currentTimeMillis()
        var lastDecode: Pair<EngineSettings, Double>? = null
        var drift: String? = null
        loadedWith = null
        try {
            onStatus(TuneStatus(0, todo.size, null, null, "Loading the model for the test…", startedAtMs = began))
            start(modelPath, cur)?.let { throw TuneException("The model could not be loaded: $it") }
            var step = 0
            val queue = ArrayDeque(todo)
            var decodeRechecked = false
            while (queue.isNotEmpty()) {
                val param = queue.removeFirst()
                step++
                val steps = step + queue.size
                if (param.needsNpuOn && !cur.npuPrefill) {
                    onStatus(TuneStatus(step, steps, param, null, "${param.title}: skipped, the NPU is off.", startedAtMs = began))
                    continue
                }
                val startValue = DeviceTuner.valueOf(param, cur)
                val status = { v: Int?, text: String, last: String? ->
                    onStatus(TuneStatus(step, steps, param, v, text, last, began))
                }
                val result = if (param == TuneParam.NPU_MIN_TOKENS) {
                    npuThreshold(cur, startValue, status)
                } else {
                    val values = DeviceTuner.candidates(param, cores)
                    val loadedCache = { DeviceTuner.valueOf(TuneParam.CACHE, loadedWith ?: cur) }
                    val points = DeviceTuner.climb(
                        values, startValue,
                        allowed = { v ->
                            // A bigger cache must fit: what it adds on top of the current one, plus 1.5 GB for the rest.
                            param != TuneParam.CACHE || v <= loadedCache() || availRamMb() - (v - loadedCache()) >= 1536
                        }
                    ) { v ->
                        val s = DeviceTuner.applyTo(param, v, cur, ::coreMask)
                        status(v, "${param.title}: testing ${display(param, v)}", null)
                        val point = measure(s, param, v, status)
                        if (param.metric == TuneMetric.DECODE && point.tps != null) lastDecode = s to point.tps
                        point
                    }
                    TuneResult(param, startValue, points, DeviceTuner.best(points, startValue), System.currentTimeMillis())
                }
                onResult(result)
                result.best?.let { cur = DeviceTuner.applyTo(param, it, cur, ::coreMask) }
                // Decode threads and I/O lanes compete for the same cores: look at the threads again after the lanes moved.
                if (param == TuneParam.IO_LANES && result.best != null && result.best != startValue && !decodeRechecked &&
                    TuneParam.DECODE_CORES in todo
                ) {
                    decodeRechecked = true
                    queue.addFirst(TuneParam.DECODE_CORES)
                }
            }
            // Heat check: the final decode settings once more, against what they measured during the test.
            val ref = lastDecode
            if (ref != null) {
                onStatus(TuneStatus(step, step, null, null, "Checking whether the device got warm and slower…", startedAtMs = began))
                val again = measure(ref.first, null, null, status = { _, _, _ -> }).tps
                if (again != null && again < ref.second * 0.9) {
                    drift = "The device got slower during the test (${fmt(ref.second)} → ${fmt(again)} tokens/s with the same " +
                        "settings), most likely from heat. Results measured late in the test may be on the low side; " +
                        "retest single values after it cooled down."
                }
            }
            return drift
        } finally {
            withContext(NonCancellable) {
                engine.stopGeneration()
                onStatus(TuneStatus(0, 0, null, null, "Restoring your model and settings…", startedAtMs = began))
                riskyPoint(null, null)
                engine.unload()
                engine.setCpuMasks(CpuTopology.effective(restore.cpuMask), CpuTopology.effective(restore.cpuMaskPrefill))
                if (previous != null) {
                    delay(1000)
                    engine.loadModel(previous.path, restore.toLoadConfig(), previous.mmprojPath, previous.draftModelPath)
                }
                loadedWith = null
            }
        }
    }

    /** The NPU threshold: prompt speed at a few lengths with the NPU taking every prompt, and with the NPU off. */
    private suspend fun npuThreshold(
        cur: EngineSettings,
        startValue: Int,
        status: (Int?, String, String?) -> Unit
    ): TuneResult {
        val npu = HashMap<Int, Double>()
        val cpu = HashMap<Int, Double>()
        val errors = HashMap<Int, String>()
        for ((series, s) in listOf(npu to cur.copy(npuPrefill = true, npuMinTokens = 2), cpu to cur.copy(npuPrefill = false))) {
            for (len in DeviceTuner.NPU_MIN_STEPS) {
                status(len, "NPU threshold: ${len}-token prompt, " + if (s.npuPrefill) "on the NPU" else "on the CPU", null)
                val p = measure(s, TuneParam.NPU_MIN_TOKENS, len, status, shortPromptTokens = len)
                p.tps?.let { series[len] = it } ?: p.error?.let { errors[len] = it }
            }
        }
        val points = DeviceTuner.NPU_MIN_STEPS.map { TunePoint(it, npu[it], errors[it], cpu[it]) }
        val best = DeviceTuner.npuThreshold(npu, cpu) ?: DeviceTuner.NPU_MIN_STEPS.last().takeIf { npu.isNotEmpty() && cpu.isNotEmpty() }
        return TuneResult(TuneParam.NPU_MIN_TOKENS, startValue, points, best, System.currentTimeMillis())
    }

    /** Median tokens/s of [RUNS] runs of [s], loading or changing the model as needed. */
    private suspend fun measure(
        s: EngineSettings,
        param: TuneParam?,
        value: Int?,
        status: (Int?, String, String?) -> Unit,
        shortPromptTokens: Int = 0
    ): TunePoint {
        val v = value ?: 0
        val metric = when {
            shortPromptTokens > 0 -> TuneMetric.PREFILL
            param != null -> param.metric
            else -> TuneMetric.DECODE
        }
        prepare(s, param, value)?.let { return TunePoint(v, null, it) }
        val label = param?.let { "${it.title} ${display(it, v)}" } ?: "check"
        if (needsWarmup) {
            status(value, "$label: warming up (the expert cache fills)", null)
            runOnce(metric, shortPromptTokens) // discarded
            needsWarmup = false
        }
        val got = ArrayList<Double>()
        var error: String? = null
        for (r in 1..RUNS) {
            status(value, "$label: run $r of $RUNS", got.lastOrNull()?.let { "${fmt(it)} tokens/s (${metric.label})" })
            val (tps, err) = runOnce(metric, shortPromptTokens)
            if (tps != null) got += tps else error = err
        }
        val med = DeviceTuner.median(got)
        status(value, "$label: ${med?.let { fmt(it) } ?: "failed"}", med?.let { "${fmt(it)} tokens/s (${metric.label})" })
        return TunePoint(v, med?.let { Math.round(it * 100) / 100.0 }, if (med == null) (error ?: "No tokens were produced.") else null)
    }

    /** Loads the model for [s] when a reload setting differs, otherwise changes threads and cache live. Error or null. */
    private suspend fun prepare(s: EngineSettings, param: TuneParam?, value: Int?): String? {
        val decodeMask = CpuTopology.effective(s.cpuMask)
        val prefillMask = CpuTopology.effective(s.cpuMaskPrefill)
        val loaded = loadedWith
        val path = engine.loadedModel()?.path
        // A cache that is off has no LRU buffers to grow live; switching it on or off needs a load.
        val cacheOnOff = loaded != null && (loaded.cacheMb == 0) != (s.cacheMb == 0)
        if (loaded != null && path != null && reloadKey(loaded) == reloadKey(s) && !cacheOnOff) {
            engine.setCpuMasks(decodeMask, prefillMask)
            val cacheChanged = loaded.cacheMb != s.cacheMb
            val ok = engine.tuneLive(
                java.lang.Long.bitCount(decodeMask), java.lang.Long.bitCount(prefillMask), if (cacheChanged) s.cacheMb else null
            )
            if (!ok) return "The engine was busy; the settings could not be changed."
            if (cacheChanged) needsWarmup = true
            loadedWith = s
            return null
        }
        val modelPath = path ?: tunedModelPath ?: return "No model to test."
        riskyPoint(param, value)
        engine.unload()
        delay(1500) // two big loads must never overlap
        engine.setCpuMasks(decodeMask, prefillMask)
        val err = engine.loadModel(modelPath, s.toLoadConfig().copy(spec = SpecSource.OFF), null, null, useModelSpec = false)
        riskyPoint(null, null)
        if (err != null) {
            loadedWith = null
            return err.take(300)
        }
        loadedWith = s
        needsWarmup = true
        return null
    }

    /** The model the run is about, for a reload after a failed one. Set by [start]. */
    private var tunedModelPath: String? = null

    /** Loads [modelPath] with [s] for the first measurement. Error text or null. */
    private suspend fun start(modelPath: String, s: EngineSettings): String? {
        tunedModelPath = modelPath
        loadedWith = null
        engine.unload()
        delay(1000)
        engine.setCpuMasks(CpuTopology.effective(s.cpuMask), CpuTopology.effective(s.cpuMaskPrefill))
        val err = engine.loadModel(modelPath, s.copy(spec = SpecSource.OFF).toLoadConfig(), null, null, useModelSpec = false)
        if (err == null) {
            loadedWith = s.copy(spec = SpecSource.OFF)
            needsWarmup = true
        }
        return err
    }

    /** The settings that only take effect at load; threads and the expert cache change on the loaded model. */
    private fun reloadKey(s: EngineSettings): List<Any> = listOf(
        s.ioThreads, s.batchWidth, s.npuPrefill, s.npuLoaders, s.npuMinTokens, s.contextLength, s.denseWeights,
        s.oDirect, s.overlap
    )

    /** One generation; tokens/s for [metric] or an error. */
    private suspend fun runOnce(metric: TuneMetric, shortPromptTokens: Int): Pair<Double?, String?> {
        runNonce++
        val prompt = when {
            shortPromptTokens > 0 -> DeviceTuner.shortPrompt(shortPromptTokens)
            metric == TuneMetric.PREFILL -> DeviceTuner.PREFILL_PROMPT
            else -> DeviceTuner.DECODE_PROMPT
        }
        val request = GenerationRequest(
            // A different first line every run, so no run reuses the previous run's prompt from the KV cache.
            system = "Benchmark run $runNonce.",
            history = emptyList(),
            prompt = prompt,
            expectedKey = "device-tune-$runNonce-${System.nanoTime()}",
            maxTokens = if (metric == TuneMetric.DECODE) DECODE_SKIP + DECODE_COUNT else 1,
            think = false
        )
        var t0 = 0L
        var t1 = 0L
        var n1 = 0
        var stats: GenerationStats? = null
        var error: String? = null
        engine.generate(request).collect { e ->
            when (e) {
                is EngineEvent.Progress -> {
                    if (e.generatedTokens == DECODE_SKIP) t0 = e.atNs
                    if (e.generatedTokens > DECODE_SKIP) {
                        t1 = e.atNs
                        n1 = e.generatedTokens - DECODE_SKIP
                    }
                }
                is EngineEvent.Finished -> if (e.ok && !e.cancelled) stats = e.stats else error = e.error.ifBlank { "cancelled" }
                else -> Unit
            }
        }
        engine.invalidateState()
        val st = stats ?: return null to (error ?: "The test generation failed.")
        return when (metric) {
            TuneMetric.PREFILL -> {
                if (st.prefillSeconds <= 0.0 || st.nPrompt <= 0) null to "No prompt speed was reported."
                else st.nPrompt / st.prefillSeconds to null
            }
            TuneMetric.DECODE -> when {
                n1 >= 16 && t1 > t0 && t0 > 0 -> n1 / ((t1 - t0) / 1e9) to null
                // The answer ended early: the engine's own average is the best there is.
                st.tokensPerSecond > 0 -> st.tokensPerSecond to null
                else -> null to "The model produced no tokens."
            }
        }
    }

    companion object {
        const val RUNS = 2
        /** The first tokens of an answer are not timed: the expert cache is still filling for this text. */
        const val DECODE_SKIP = 32
        const val DECODE_COUNT = 128

        fun display(param: TuneParam, v: Int): String = when (param) {
            TuneParam.DECODE_CORES, TuneParam.PREFILL_CORES -> "$v core${if (v == 1) "" else "s"}"
            TuneParam.CACHE -> if (v < 0) "Auto" else if (v == 0) "off" else "$v MiB"
            TuneParam.IO_LANES -> "$v lane${if (v == 1) "" else "s"}"
            TuneParam.UBATCH -> "$v tokens"
            TuneParam.NPU -> if (v == 1) "on" else "off"
            TuneParam.NPU_LOADERS -> "$v threads"
            TuneParam.NPU_MIN_TOKENS -> "$v tokens"
        }

        fun fmt(x: Double): String = String.format(java.util.Locale.US, "%.1f", x)
    }
}

/** A tuning run that cannot go on (the model does not load); its message is for the user. */
class TuneException(message: String) : Exception(message)
