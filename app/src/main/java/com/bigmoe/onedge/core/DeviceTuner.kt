package com.bigmoe.onedge.core

import com.bigmoe.onedge.data.local.datastore.CACHE_AUTO_MB
import com.bigmoe.onedge.data.local.datastore.EngineSettings
import org.json.JSONArray
import org.json.JSONObject

/**
 * The device tuner: measures, on this device and with one chosen model, the settings that depend on the hardware
 * (threads, expert cache, I/O lanes, prompt batch width, NPU) instead of deriving them from a formula.
 *
 * One parameter at a time, starting from the current settings: the current value is measured, then its neighbours;
 * the search walks towards the faster side until a step no longer helps. The other parameters stay at their best
 * values found so far. Pure logic here (JVM-testable); the runner that loads models and generates text is
 * [DeviceTuneRunner].
 */

/** What a parameter is judged by. */
enum class TuneMetric(val label: String) {
    DECODE("writing"),        // tokens/s while the answer is written (decode)
    PREFILL("prompt"),        // tokens/s while a long prompt is read (prefill)
}

enum class TuneParam(
    val title: String,
    val metric: TuneMetric,
    /** True: can be changed on the loaded model; false: needs a reload per value. */
    val live: Boolean,
    /** Only for MoE models (expert streaming). */
    val moeOnly: Boolean = false,
    /** Only when the NPU runtime is available. */
    val npuOnly: Boolean = false,
    /** Only when the NPU is on (in the settings being tuned). */
    val needsNpuOn: Boolean = false,
) {
    DECODE_CORES("Decode threads", TuneMetric.DECODE, live = true),
    CACHE("Expert cache", TuneMetric.DECODE, live = true, moeOnly = true),
    IO_LANES("Parallel I/O lanes", TuneMetric.DECODE, live = false, moeOnly = true),
    PREFILL_CORES("Prefill threads", TuneMetric.PREFILL, live = true),
    UBATCH("Prompt batch width (ubatch)", TuneMetric.PREFILL, live = false),
    NPU("NPU prefill", TuneMetric.PREFILL, live = false, npuOnly = true),
    NPU_LOADERS("NPU loader threads", TuneMetric.PREFILL, live = false, npuOnly = true, needsNpuOn = true),
    NPU_MIN_TOKENS("Minimum prompt tokens for the NPU", TuneMetric.PREFILL, live = false, npuOnly = true, needsNpuOn = true);

    companion object {
        /** Live parameters first (no reload), then the ones that need a reload; decode threads are checked again after
         * the I/O lanes changed, since both compete for the same cores. */
        val ORDER = listOf(DECODE_CORES, CACHE, PREFILL_CORES, IO_LANES, UBATCH, NPU, NPU_LOADERS, NPU_MIN_TOKENS)
    }
}

/**
 * One measured value of a parameter: its tokens/s (median of the runs), or why it could not be measured. [altTps] is
 * the comparison series where a parameter has one (the CPU's prompt speed at the same length, for the NPU threshold).
 */
data class TunePoint(val value: Int, val tps: Double?, val error: String? = null, val altTps: Double? = null)

data class TuneResult(
    val param: TuneParam,
    /** The value the settings had when the test started. */
    val startValue: Int,
    val points: List<TunePoint>,
    /** The fastest value measured; null when nothing could be measured. */
    val best: Int?,
    val testedAt: Long
) {
    fun tpsOf(value: Int): Double? = points.firstOrNull { it.value == value }?.tps
    /** Gain of [best] over [startValue] in percent, when both were measured. */
    val gainPct: Double?
        get() {
            val b = best?.let { tpsOf(it) } ?: return null
            val s = tpsOf(startValue) ?: return null
            return if (s > 0) (b / s - 1.0) * 100.0 else null
        }
}

data class TuneReport(
    val modelPath: String,
    val modelName: String,
    val results: Map<TuneParam, TuneResult> = emptyMap(),
    /** Set when the device got slower during the test (heat): the same settings measured lower at the end. */
    val driftNote: String? = null,
    val updatedAt: Long = 0L
)

object DeviceTuner {

    /** Below this a difference is measurement noise, not a better value. */
    const val MIN_GAIN = 0.03

    /** Candidate values per parameter, ascending. [cores] is the number of CPU cores Android lets the app use. */
    fun candidates(param: TuneParam, cores: Int): List<Int> = when (param) {
        TuneParam.DECODE_CORES, TuneParam.PREFILL_CORES -> (1..cores.coerceIn(1, 16)).toList()
        // Coarse steps, each one a value the settings screen offers (SettingChoices); the fine pass fills in the rest.
        TuneParam.CACHE -> listOf(500, 1000, 1500, 2000, 3000, 4000, 5000, 6000, 7000, 8000)
        TuneParam.IO_LANES -> listOf(1, 2, 4, 8)
        TuneParam.UBATCH -> listOf(64, 128, 256, 512, 1024)
        TuneParam.NPU -> listOf(0, 1)
        TuneParam.NPU_LOADERS -> listOf(2, 4, 6, 8, 12, 16)
        TuneParam.NPU_MIN_TOKENS -> NPU_MIN_STEPS
    }

    /**
     * The fine grid a parameter is refined on after the coarse search found its best value: the values the settings
     * screen offers between the coarse steps (I/O lanes 3, 5, 6, 7; cache 1250, 1750, 2500, ...; ubatch 384, 768;
     * NPU loaders one by one). Null when the coarse
     * list is already every value (threads) or there is nothing in between (NPU on/off, the NPU threshold).
     */
    fun fineCandidates(param: TuneParam): List<Int>? = when (param) {
        // Only values the settings screen offers, so every result can also be chosen by hand.
        TuneParam.IO_LANES -> SettingChoices.IO_LANES
        TuneParam.CACHE -> SettingChoices.CACHE_MB
        TuneParam.UBATCH -> SettingChoices.BATCH_WIDTH.filter { it in 64..1024 } // wider graphs cost compute memory
        TuneParam.NPU_LOADERS -> SettingChoices.NPU_LOADERS
        else -> null
    }

    /** Prompt lengths at which the NPU threshold is decided: the smallest one where the NPU is faster wins. */
    val NPU_MIN_STEPS = listOf(16, 32, 64, 128, 256)

    /** The parameter's value in [s]; core counts from the masks, an Auto cache as 2000 MiB to start from. */
    fun valueOf(param: TuneParam, s: EngineSettings): Int = when (param) {
        TuneParam.DECODE_CORES -> java.lang.Long.bitCount(CpuTopology.effective(s.cpuMask))
        TuneParam.PREFILL_CORES -> java.lang.Long.bitCount(CpuTopology.effective(s.cpuMaskPrefill))
        TuneParam.CACHE -> if (s.cacheMb == CACHE_AUTO_MB || s.cacheMb <= 0) 2000 else s.cacheMb
        TuneParam.IO_LANES -> s.ioThreads
        TuneParam.UBATCH -> s.batchWidth
        TuneParam.NPU -> if (s.npuPrefill) 1 else 0
        TuneParam.NPU_LOADERS -> s.npuLoaders
        TuneParam.NPU_MIN_TOKENS -> s.npuMinTokens
    }

    /** [s] with [param] set to [value]; [coreMask] turns a core count into a mask (the fastest cores first). */
    fun applyTo(param: TuneParam, value: Int, s: EngineSettings, coreMask: (Int) -> Long): EngineSettings = when (param) {
        TuneParam.DECODE_CORES -> s.copy(cpuMask = coreMask(value), threadCount = value)
        TuneParam.PREFILL_CORES -> s.copy(cpuMaskPrefill = coreMask(value), batchThreadCount = value)
        TuneParam.CACHE -> s.copy(cacheMb = value)
        TuneParam.IO_LANES -> s.copy(ioThreads = value)
        TuneParam.UBATCH -> s.copy(batchWidth = value)
        TuneParam.NPU -> s.copy(npuPrefill = value == 1)
        TuneParam.NPU_LOADERS -> s.copy(npuLoaders = value)
        TuneParam.NPU_MIN_TOKENS -> s.copy(npuMinTokens = value)
    }

    /** Index of the candidate closest to [value]. */
    fun nearestIndex(values: List<Int>, value: Int): Int =
        values.indices.minByOrNull { kotlin.math.abs(values[it] - value) } ?: 0

    /**
     * Hill climb over the ordered [values] from the one nearest [start]: measure it and its neighbours, walk towards
     * the faster side while each step gains at least [MIN_GAIN], and stop at the first step that does not. [measure]
     * returns tokens/s, or null when the value could not be measured (then that side is not followed further).
     * [allowed] can stop a direction early (e.g. a cache that would not fit in RAM).
     */
    suspend fun climb(
        values: List<Int>,
        start: Int,
        allowed: (Int) -> Boolean = { true },
        /** Values already measured (the coarse search before a fine one): reused, not measured again. */
        seed: List<TunePoint> = emptyList(),
        measure: suspend (Int) -> TunePoint
    ): List<TunePoint> {
        val points = LinkedHashMap<Int, TunePoint>()
        seed.forEach { points[it.value] = it }
        suspend fun at(i: Int): TunePoint? {
            if (i !in values.indices || !allowed(values[i])) return null
            return points[values[i]] ?: measure(values[i]).also { points[values[i]] = it }
        }
        val s = nearestIndex(values, start)
        val base = at(s)?.tps ?: return points.values.toList()
        var bestI = s
        var bestTps = base
        for (dir in listOf(+1, -1)) {
            var i = s + dir
            var walked = false
            while (true) {
                val tps = at(i)?.tps ?: break
                if (tps >= bestTps * (1.0 + MIN_GAIN)) {
                    bestI = i
                    bestTps = tps
                    walked = true
                    i += dir
                } else break
            }
            // Once one side was better, the other side is not worth testing.
            if (walked) break
        }
        return points.values.toList()
    }

    /**
     * Coarse, then fine: [climb] over [coarse] from [start], and then once more over [fine] from the best value found,
     * so the steps in between are tried too (best 4 of 2, 4, 8: then 5, and 6 while each step is faster, or 3 when 5 is
     * not). Points measured in the first pass are not measured again.
     */
    suspend fun search(
        coarse: List<Int>,
        fine: List<Int>?,
        start: Int,
        allowed: (Int) -> Boolean = { true },
        measure: suspend (Int) -> TunePoint
    ): List<TunePoint> {
        val first = climb(coarse, start, allowed, measure = measure)
        if (fine == null) return first
        val from = best(first, start) ?: return first
        return climb(fine, from, allowed, seed = first, measure = measure)
    }

    /**
     * The fastest measured value, or null when nothing was measured. A value that beats [start] by less than
     * [MIN_GAIN] is noise: then [start] stays (when it was measured).
     */
    fun best(points: List<TunePoint>, start: Int? = null): Int? {
        val fastest = points.filter { it.tps != null }.maxByOrNull { it.tps!! } ?: return null
        val startTps = start?.let { s -> points.firstOrNull { it.value == s }?.tps } ?: return fastest.value
        return if (fastest.tps!! >= startTps * (1.0 + MIN_GAIN)) fastest.value else start
    }

    /**
     * The NPU threshold from two prefill series over [NPU_MIN_STEPS] prompt lengths: the smallest length from which
     * the NPU is faster than the CPU at every longer length. Null when the NPU never wins (then the threshold stays).
     */
    fun npuThreshold(npu: Map<Int, Double>, cpu: Map<Int, Double>): Int? {
        val steps = NPU_MIN_STEPS.filter { npu[it] != null && cpu[it] != null }
        for ((k, len) in steps.withIndex()) {
            if (steps.drop(k).all { npu[it]!! > cpu[it]!! }) return len
        }
        return null
    }

    /** Median of a few runs (the mean of the middle two for an even count). */
    fun median(xs: List<Double>): Double? {
        if (xs.isEmpty()) return null
        val s = xs.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }

    // ---------------------------------------------------------------- persistence

    fun toJson(r: TuneReport): String = JSONObject().apply {
        put("modelPath", r.modelPath)
        put("modelName", r.modelName)
        put("updatedAt", r.updatedAt)
        r.driftNote?.let { put("driftNote", it) }
        put("results", JSONArray().apply {
            r.results.values.forEach { res ->
                put(JSONObject().apply {
                    put("param", res.param.name)
                    put("start", res.startValue)
                    res.best?.let { put("best", it) }
                    put("testedAt", res.testedAt)
                    put("points", JSONArray().apply {
                        res.points.forEach { p ->
                            put(JSONObject().apply {
                                put("value", p.value)
                                p.tps?.let { put("tps", it) }
                                p.error?.let { put("error", it) }
                                p.altTps?.let { put("alt", it) }
                            })
                        }
                    })
                })
            }
        })
    }.toString()

    fun fromJson(text: String): TuneReport? = runCatching {
        val o = JSONObject(text)
        val results = LinkedHashMap<TuneParam, TuneResult>()
        val arr = o.optJSONArray("results") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            val param = runCatching { TuneParam.valueOf(r.getString("param")) }.getOrNull() ?: continue
            val pts = r.optJSONArray("points") ?: JSONArray()
            val points = (0 until pts.length()).map { k ->
                val p = pts.getJSONObject(k)
                TunePoint(
                    p.getInt("value"), if (p.has("tps")) p.getDouble("tps") else null, p.optString("error").ifBlank { null },
                    if (p.has("alt")) p.getDouble("alt") else null
                )
            }
            results[param] = TuneResult(
                param, r.getInt("start"), points, if (r.has("best")) r.getInt("best") else null, r.optLong("testedAt")
            )
        }
        TuneReport(
            o.getString("modelPath"), o.optString("modelName"), results,
            o.optString("driftNote").ifBlank { null }, o.optLong("updatedAt")
        )
    }.getOrNull()

    // ---------------------------------------------------------------- test prompts

    /** Decode tests: a short prompt that asks for a long answer, so the timing is the writing. */
    const val DECODE_PROMPT =
        "Write a detailed explanation, at least 600 words long, of how a mechanical clock keeps time: the escapement, " +
            "the balance wheel or pendulum, the gear train, the mainspring and how they work together. Do not stop early."

    /** Prefill tests: a long text to read (about 1200 tokens), answered with one token. */
    val PREFILL_PROMPT: String = buildString {
        append("Read the following text carefully. Afterwards answer with a single word.\n\n")
        append(LONG_TEXT)
        append("\n\nQuestion: Which component releases the energy of the mainspring in small, equal steps? Answer with one word.")
    }

    /**
     * Short prompts of roughly [tokens] tokens for the NPU threshold, the chat template and the run's first line
     * included (about [TEMPLATE_TOKENS] of them; four characters per token for the text).
     */
    fun shortPrompt(tokens: Int): String =
        LONG_TEXT.take(((tokens - TEMPLATE_TOKENS) * 4).coerceIn(8, LONG_TEXT.length)) + "\nSummarise this in one word."

    private const val TEMPLATE_TOKENS = 24

    private const val LONG_TEXT = """For most of history people told the time by the sun. A stick in the ground, a gnomon, threw a shadow that moved through the day, and the length and direction of that shadow divided daylight into rough portions. Water clocks, in which water dripped from one vessel into another at a steady rate, worked at night and indoors, and the best of them, in China and in the Islamic world, drove elaborate displays of moving figures and ringing bells. Yet all of these devices drifted with the weather, the season and the skill of their keepers, and none of them could be carried.

The mechanical clock, which appeared in European monasteries and town towers around the end of the thirteenth century, solved the problem in a different way. Instead of measuring a continuous flow, it counts repeated events. A weight hanging from a cord, or later a coiled spring, provides a constant force that would make the wheels of the clock spin freely if nothing stopped them. The heart of the clock is the part that stops them and lets them go again at regular intervals: the escapement.

The earliest escapement was the verge and foliot. A vertical rod, the verge, carries two small flags, the pallets, which alternately engage the teeth of a crown-shaped wheel. As the wheel pushes one pallet aside, the verge swings, the other pallet enters the teeth on the opposite side and the wheel is stopped again. A horizontal bar with adjustable weights, the foliot, sits on top of the verge; moving the weights in or out changes how fast it swings and therefore the rate of the clock. Such clocks might gain or lose a quarter of an hour in a day, which was good enough to ring the hours for prayer and for the market.

The great improvement came with the pendulum. Galileo had noticed that a pendulum of a given length takes almost the same time for each swing, whether the swing is wide or narrow, and in 1656 Christiaan Huygens built the first pendulum clock. Because the period of a pendulum depends mainly on its length and on gravity, a pendulum clock could keep time to within a minute a day, and soon to within seconds. The anchor escapement, introduced a few years later, needed only a small swing, which made the pendulum even more regular and allowed the long, slow pendulums of tall case clocks.

A pendulum cannot work in a pocket or on a ship, where every movement disturbs it. For portable clocks the regulating element is the balance wheel: a small wheel that turns back and forth, driven by a fine spiral hairspring that pulls it back to its centre. Like the pendulum, the balance swings with a natural period, set by its weight and the stiffness of the spring. Robert Hooke and Huygens both claimed the idea of the balance spring in the 1670s, and with it a watch could keep time to a few minutes a day.

The energy for a watch comes from the mainspring, a long ribbon of steel coiled inside a drum called the barrel. Winding the watch tightens the coil. As the spring unwinds, it turns the barrel, and the barrel turns a chain of gears, the gear train, which steps the speed up from the slow turn of the barrel to the faster turn of the escape wheel. At the other end of the train the escapement lets the escape wheel advance by one tooth for each swing of the balance, and with each step it gives the balance a tiny push that keeps it swinging. The same gear train drives the hands: one wheel turns once an hour for the minute hand, and a further reduction of twelve to one turns the hour hand.

A spring does not push with the same force when it is fully wound as when it is nearly run down, and early watches ran fast at the start of the day and slow at the end. Watchmakers fought this with the fusee, a cone-shaped pulley joined to the barrel by a tiny chain, which gave the spring more leverage as it weakened. Later the lever escapement, with its detached balance that touches the escapement only briefly in each swing, made the timekeeper far less sensitive to the force arriving from the spring.

Temperature was the next enemy. Metal expands when it warms, so a pendulum grows longer and a balance wheel grows larger, and both swing more slowly. The hairspring also loses some of its stiffness. Clockmakers built pendulums from rods of different metals arranged so that their expansions cancelled, and watchmakers built balance wheels whose rims were made of two metals that bent inward as they warmed. In the twentieth century special alloys that hardly change with temperature made these tricks unnecessary.

The demand for accurate time at sea drove much of this work. A navigator who knows the time at his home port and the local time from the sun can work out his longitude, but only if his clock keeps time to a few seconds a day through months of rolling, damp and changing temperatures. John Harrison spent decades building the marine chronometers that finally achieved this in the eighteenth century, and chronometers based on his ideas guided ships until radio time signals took over.

Today the quartz crystal in an ordinary wristwatch vibrates thirty-two thousand seven hundred and sixty-eight times each second, and atomic clocks count the oscillations of caesium atoms so precisely that they would lose less than a second in millions of years. But the principle is the one the first clockmakers found: a source of energy, a regular oscillation, and a mechanism that lets the energy flow out in small, equal steps and counts them."""
}
