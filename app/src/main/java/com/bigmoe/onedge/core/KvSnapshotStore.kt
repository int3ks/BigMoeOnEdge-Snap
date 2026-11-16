package com.bigmoe.onedge.core

import java.io.File
import java.security.MessageDigest

/**
 * Where the KV snapshots live (engine: `Session::save_kv` / `load_kv`, see docs/prompt-and-kv-design.md): one set of
 * files per chat and model setup under `root/<fingerprint>/<session>.{kv,dft,aux}`.
 *
 * The fingerprint ties a snapshot to what produced it: the model file (name, size and the start of its header - not
 * its date, so a model that was removed from the cache and fetched again keeps its snapshots), the KV type, the LoRA
 * and the draft setup. The engine refuses a state that does not fit the context; a state from a different model of the
 * same shape would fit and be wrong, which is what the fingerprint is for.
 *
 * Snapshots are a cache: the oldest go first once all of them together pass [budgetBytes].
 */
class KvSnapshotStore(private val root: File, private val budgetBytes: Long = DEFAULT_BUDGET_BYTES) {

    fun prefix(fingerprint: String, sessionId: String): String =
        File(File(root, fingerprint), sessionId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(96)).path

    /** A snapshot counts only with its index (.aux, written last) and its state file. */
    fun exists(prefix: String): Boolean = File("$prefix.aux").isFile && File("$prefix.kv").isFile

    fun delete(prefix: String) {
        EXTENSIONS.forEach { File(prefix + it).delete() }
    }

    /** Room for another snapshot: never fill the storage the models and the app itself need. */
    fun hasRoom(): Boolean {
        root.mkdirs()
        return root.usableSpace > MIN_FREE_BYTES
    }

    fun prepare(prefix: String) {
        File(prefix).parentFile?.mkdirs()
    }

    fun totalBytes(): Long = files().sumOf { it.length() }

    fun clear() {
        root.deleteRecursively()
    }

    /** Drops the oldest snapshots until all of them fit the budget; [keep] (the one just written) always stays. */
    fun trim(keep: String?) {
        val byPrefix = files().groupBy { it.path.substringBeforeLast('.') }
        var total = byPrefix.values.sumOf { set -> set.sumOf { it.length() } }
        if (total <= budgetBytes) return
        val oldestFirst = byPrefix.entries.sortedBy { (_, set) -> set.maxOf { it.lastModified() } }
        for ((prefix, set) in oldestFirst) {
            if (total <= budgetBytes) break
            if (prefix == keep) continue
            total -= set.sumOf { it.length() }
            delete(prefix)
            File("$prefix.aux.tmp").delete()
        }
        root.listFiles()?.forEach { dir -> if (dir.isDirectory && dir.list().isNullOrEmpty()) dir.delete() }
    }

    private fun files(): List<File> =
        root.listFiles()?.filter { it.isDirectory }?.flatMap { it.listFiles()?.toList().orEmpty() }.orEmpty()
            .filter { f -> f.isFile && EXTENSIONS.any { f.name.endsWith(it) } }

    companion object {
        const val DEFAULT_BUDGET_BYTES = 6L * 1024 * 1024 * 1024
        const val MIN_FREE_BYTES = 4L * 1024 * 1024 * 1024
        /** Below this a prefill is quicker than the bookkeeping is worth. */
        const val MIN_TOKENS = 512
        private val EXTENSIONS = listOf(".kv", ".dft", ".aux")
        private const val HEADER_BYTES = 1 shl 20

        /** See the class comment. Null when the model file cannot be read. */
        fun fingerprint(model: File, vararg setup: String): String? = try {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(model.name.toByteArray(Charsets.UTF_8))
            md.update(model.length().toString().toByteArray(Charsets.UTF_8))
            model.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                var left = HEADER_BYTES
                while (left > 0) {
                    val n = input.read(buf, 0, minOf(buf.size, left))
                    if (n <= 0) break
                    md.update(buf, 0, n)
                    left -= n
                }
            }
            setup.forEach { md.update(0.toByte()); md.update(it.toByteArray(Charsets.UTF_8)) }
            md.digest().take(10).joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }
}
