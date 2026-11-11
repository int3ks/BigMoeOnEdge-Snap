package com.bigmoe.onedge.core

import java.io.File

/** What a model's card overrides of the global memory settings; a null field = the global setting applies. */
data class ModelMemoryOptions(val npu: Boolean? = null, val cacheMb: Int? = null)

/**
 * What a model will take in RAM, worked out from the tensor directory of its GGUF file(s) before it is loaded.
 * An ESTIMATE: the dense weights and the expert sizes are exact (they are sizes in the file), the NPU figures follow the
 * engine's rules (`device_arena.cpp`) with file sizes, while the NPU may lay a weight out wider than the file does.
 * The KV cache / model state and the compute buffers are not in here: they depend on the context and batch size.
 */
data class MemoryEstimate(
    /** A Mixture-of-Experts model: the experts stream from flash through the expert cache. */
    val moe: Boolean,
    /** Non-expert weights the engine keeps in RAM. */
    val denseResidentBytes: Long,
    /** Non-expert tensors too big for RAM (bigger than half of it): left on flash, read through the page cache. */
    val onFlashBytes: Long,
    /** All expert tensors together (on flash; the expert cache holds a part). For a non-MoE model 0. */
    val expertBytes: Long,
    /** NPU prefill: two slots, each as big as the largest layer's experts. */
    val npuExpertArenaBytes: Long,
    /** NPU prefill: two slots for the other layer weights (a non-MoE model: all layer weights, resident). */
    val npuDenseArenaBytes: Long,
    /** NPU prefill: copies of layer weights in a type the NPU's matmul takes (Q8_0), kept in RAM. */
    val npuConvertedBytes: Long,
    /** The types the experts are stored in, most bytes first, e.g. "IQ3_XXS, Q4_K". */
    val expertTypes: String,
    /** False when the main expert type is one the NPU's matmul does not take (those matmuls then run on the CPU). */
    val npuTakesExperts: Boolean
) {
    val npuBytes: Long get() = npuExpertArenaBytes + npuDenseArenaBytes + npuConvertedBytes
}

object ModelMemoryEstimator {
    private val SHARD = Regex("-(\\d{5})-of-(\\d{5})\\.gguf$", RegexOption.IGNORE_CASE)
    private val LAYER = Regex("^blk\\.(\\d+)\\.")

    // ggml type numbers (ggml.h).
    private val TYPE_NAMES = mapOf(
        0 to "F32", 1 to "F16", 2 to "Q4_0", 3 to "Q4_1", 6 to "Q5_0", 7 to "Q5_1", 8 to "Q8_0", 9 to "Q8_1",
        10 to "Q2_K", 11 to "Q3_K", 12 to "Q4_K", 13 to "Q5_K", 14 to "Q6_K", 15 to "Q8_K", 16 to "IQ2_XXS",
        17 to "IQ2_XS", 18 to "IQ3_XXS", 19 to "IQ1_S", 20 to "IQ4_NL", 21 to "IQ3_S", 22 to "IQ2_S", 23 to "IQ4_XS",
        24 to "I8", 25 to "I16", 26 to "I32", 27 to "I64", 28 to "F64", 29 to "IQ1_M", 30 to "BF16", 34 to "TQ1_0",
        35 to "TQ2_0", 39 to "MXFP4", 40 to "NVFP4", 41 to "Q1_0", 42 to "Q2_0", 43 to "F8_E4M3"
    )

    /** Not quantised: floats and integers. */
    private val PLAIN_TYPES = setOf(0, 1, 24, 25, 26, 27, 28, 30)

    /** Weight types the Hexagon backend's matmul takes (`htp_matmul_check_types` in htp-kparams.cpp). */
    private val NPU_MATMUL_TYPES = setOf(0, 1, 30, 2, 3, 8, 10, 11, 12, 13, 14, 20, 39, 41, 42)

    fun typeName(type: Int): String = TYPE_NAMES[type] ?: "type $type"

    /** Every shard of the model [first] belongs to ([first] itself for a single-file model). */
    fun shards(first: File): List<File> {
        val m = SHARD.find(first.name) ?: return listOf(first)
        val count = m.groupValues[2].toIntOrNull() ?: return listOf(first)
        val prefix = first.name.substring(0, m.range.first)
        return (1..count).map { i ->
            File(first.parentFile, "$prefix-${i.toString().padStart(5, '0')}-of-${count.toString().padStart(5, '0')}.gguf")
        }.filter { it.isFile }.ifEmpty { listOf(first) }
    }

    /** Null when no tensor directory could be read. Reads headers only; call off the main thread. */
    fun estimate(first: File, totalRamBytes: Long): MemoryEstimate? {
        val tensors = shards(first).flatMap { GgufInspector.tensors(it) }
        if (tensors.isEmpty()) return null
        return estimate(tensors, totalRamBytes)
    }

    /** Pure, for tests. */
    fun estimate(tensors: List<GgufTensor>, totalRamBytes: Long): MemoryEstimate {
        val experts = tensors.filter { isExpert(it.name) }
        val dense = tensors.filterNot { isExpert(it.name) }
        val moe = experts.isNotEmpty()
        // The engine leaves a dense tensor on flash when it is bigger than the RAM available at load; half of the
        // device's RAM stands in for that here.
        val tooBig = if (totalRamBytes > 0) totalRamBytes / 2 else Long.MAX_VALUE
        val onFlash = dense.filter { it.bytes > tooBig }
        val resident = dense.filter { it.bytes <= tooBig }

        // NPU prefill moves layer weights ("blk.N.") only.
        val layerExpertBytes = HashMap<Int, Long>()
        for (t in experts) layerOf(t.name)?.let { layerExpertBytes[it] = (layerExpertBytes[it] ?: 0L) + t.bytes }
        val layerDenseBytes = HashMap<Int, Long>()
        var converted = 0L
        for (t in resident) {
            val layer = layerOf(t.name) ?: continue
            val onDevice = if (needsConversion(t)) q8Bytes(t).also { converted += it } else t.bytes
            layerDenseBytes[layer] = (layerDenseBytes[layer] ?: 0L) + onDevice
        }
        val expertArena = if (moe) 2 * (layerExpertBytes.values.maxOrNull() ?: 0L) else 0L
        val denseArena = if (moe) {
            // Two slots, one per layer parity, each as big as the largest layer of its parity.
            (layerDenseBytes.filterKeys { it % 2 == 0 }.values.maxOrNull() ?: 0L) +
                (layerDenseBytes.filterKeys { it % 2 == 1 }.values.maxOrNull() ?: 0L)
        } else {
            layerDenseBytes.values.sum() // not streamed: the layer weights are resident on the device
        }
        val byType = experts.groupBy { it.type }.mapValues { e -> e.value.sumOf { it.bytes } }.entries.sortedByDescending { it.value }
        return MemoryEstimate(
            moe = moe,
            denseResidentBytes = resident.sumOf { it.bytes },
            onFlashBytes = onFlash.sumOf { it.bytes },
            expertBytes = experts.sumOf { it.bytes },
            npuExpertArenaBytes = expertArena,
            npuDenseArenaBytes = denseArena,
            npuConvertedBytes = converted,
            expertTypes = byType.joinToString(", ") { typeName(it.key) },
            npuTakesExperts = byType.firstOrNull()?.let { it.key in NPU_MATMUL_TYPES } ?: true
        )
    }

    /** "ffn_gate_exps", "ffn_up_exps", "ffn_down_exps", "ffn_gate_up_exps": the routed experts of every architecture. */
    private fun isExpert(name: String): Boolean = name.contains("_exps")

    private fun layerOf(name: String): Int? = LAYER.find(name)?.groupValues?.get(1)?.toIntOrNull()

    /** A quantised matrix in a type the NPU's matmul does not take is re-encoded as Q8_0 (`device_type_for`). */
    private fun needsConversion(t: GgufTensor): Boolean =
        t.dims.size == 2 && t.type !in PLAIN_TYPES && t.type !in NPU_MATMUL_TYPES

    /** Q8_0: 34 bytes per block of 32 weights. */
    private fun q8Bytes(t: GgufTensor): Long = (t.elements + 31) / 32 * 34
}
