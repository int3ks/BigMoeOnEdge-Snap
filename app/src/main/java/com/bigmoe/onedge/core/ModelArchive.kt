package com.bigmoe.onedge.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Properties

/** One model as files: a single GGUF, or every shard of a split one ([name] = the first file's name). */
data class GgufSet(
    val name: String,
    val files: List<File>,
    val sizeBytes: Long,
    /** Shards the file names announce; 1 for a single file. */
    val shardCount: Int
) {
    val complete: Boolean get() = files.size == shardCount
    val displayName: String get() = ModelArchive.displayName(name)
}

/** A model of the external archive, as the Models page shows it. */
data class ArchiveEntry(
    val name: String,
    val sizeBytes: Long,
    val shardCount: Int,
    /** The archive folder can be read right now; otherwise the entry comes from the remembered index. */
    val reachable: Boolean,
    /** All of its files are in the app's cache. */
    val cached: Boolean,
    /** A fetch was interrupted and can be continued. */
    val partial: Boolean,
    val complete: Boolean
) {
    val displayName: String get() = ModelArchive.displayName(name)
}

/**
 * Three places for model files, each with one job:
 *  - the **model folder** (Internal storage/Models): the models that are kept; used in place ([ModelManager]);
 *  - the **archive** on external storage (a USB drive): everything worth keeping around for tests;
 *  - the **cache**, the app's own model directory: where an archive model is fetched to for use, and cleared from
 *    again ([clearCache]) without losing what was set on its card.
 * Downloads only feeds the other two ([moveToModelFolder], [moveToArchive]).
 *
 * The archive is the original: fetching copies, clearing the cache deletes only the copy. What is set on a model's
 * card is stored by path, so it survives a clear and a later fetch by itself; a copy of it travels beside the model
 * in the archive (`<first file>.options.properties`) for the way between the three places and for a reinstall.
 *
 * Everything here uses plain file paths: the app holds the all-files access the model folder already needs.
 */
class ModelArchive(
    private val cacheDir: File,
    private val modelManager: ModelManager,
    private val downloadsDir: () -> File?,
    /** Roots of the removable volumes that are attached right now (for the folder suggestion). */
    private val removableRoots: () -> List<File> = { emptyList() },
    private val io: CoroutineDispatcher = Dispatchers.IO
) {
    private val folderFile: File get() = File(cacheDir, ".archive-folder")
    private val indexFile: File get() = File(cacheDir, ".archive-index.properties")

    @Volatile
    private var indexNames: Set<String> = emptySet()

    init {
        indexNames = readIndex().keys
        // Attachments of a model that is only in the archive right now stay ([ModelManager.keepLinksFor]).
        modelManager.keepLinksFor = { path ->
            val f = File(path)
            f.parentFile?.let { runCatching { it.canonicalPath == cacheDir.canonicalPath }.getOrDefault(false) } == true &&
                firstShardName(f.name) in indexNames
        }
    }

    // ---------------------------------------------------------------------------------- the archive folder

    fun folder(): File? = runCatching { folderFile.readText().trim() }.getOrDefault("").takeIf { it.isNotEmpty() }?.let(::File)

    suspend fun setFolder(path: String?) = withContext(io) {
        cacheDir.mkdirs()
        val p = path?.trim().orEmpty()
        if (p.isEmpty()) folderFile.delete() else folderFile.writeText(p)
        Unit
    }

    fun reachable(): Boolean = folder()?.let { it.isDirectory && it.canRead() } == true

    /** `<volume>/models` on every attached removable volume, the ones that exist first. */
    suspend fun suggestions(): List<String> = withContext(io) {
        runCatching { removableRoots() }.getOrDefault(emptyList())
            .map { File(it, "models") }
            .sortedByDescending { it.isDirectory }
            .map { it.path }
    }

    // ---------------------------------------------------------------------------------- listing

    /** The archive's models. Without the drive: what was there the last time it was read. */
    suspend fun list(): List<ArchiveEntry> = withContext(io) {
        val dir = folder() ?: return@withContext emptyList()
        val entries = if (dir.isDirectory && dir.canRead()) {
            val sets = scan(dir, ARCHIVE_SCAN_DEPTH)
            writeIndex(sets)
            sets.map { set ->
                ArchiveEntry(
                    name = set.name, sizeBytes = set.sizeBytes, shardCount = set.shardCount, reachable = true,
                    cached = set.complete && set.files.all { File(cacheDir, it.name).let { c -> c.isFile && c.length() == it.length() } },
                    partial = set.files.any { partFile(File(cacheDir, it.name)).isFile },
                    complete = set.complete
                )
            }
        } else {
            readIndex().map { (name, meta) ->
                val names = shardNames(name, meta.second)
                ArchiveEntry(
                    name = name, sizeBytes = meta.first, shardCount = meta.second, reachable = false,
                    cached = names.all { File(cacheDir, it).isFile },
                    partial = names.any { partFile(File(cacheDir, it)).isFile },
                    complete = true
                )
            }
        }
        entries.sortedBy { it.displayName.lowercase() }
    }

    /** GGUFs waiting in Downloads. */
    suspend fun listDownloads(): List<GgufSet> = withContext(io) {
        val dir = runCatching { downloadsDir() }.getOrNull()?.takeIf { it.isDirectory && it.canRead() } ?: return@withContext emptyList()
        scan(dir, DOWNLOADS_SCAN_DEPTH).sortedBy { it.displayName.lowercase() }
    }

    /** Whether [name] (a first-file name) is a model of the archive, attached or not. */
    fun isArchived(name: String): Boolean = name in indexNames

    /** The model that starts with [first], with every shard that is there. */
    fun setOf(first: File): GgufSet {
        val m = SHARD.find(first.name) ?: return GgufSet(first.name, listOf(first), first.length(), 1)
        val count = m.groupValues[2].toIntOrNull() ?: 1
        val files = shardNames(first.name, count).map { File(first.parentFile, it) }.filter { it.isFile }
        return GgufSet(first.name, files, files.sumOf { it.length() }, count)
    }

    // ---------------------------------------------------------------------------------- archive -> cache

    /**
     * Copies an archive model into the cache. Files that are already there are skipped and an interrupted copy is
     * continued, so a cancelled fetch (or a pulled cable) loses nothing. Returns the cached first file.
     */
    suspend fun fetch(name: String, onProgress: (copied: Long, total: Long) -> Unit): File = withContext(io) {
        val dir = folder()?.takeIf { it.isDirectory && it.canRead() } ?: throw IOException("The archive folder is not connected.")
        val set = scan(dir, ARCHIVE_SCAN_DEPTH).firstOrNull { it.name == name } ?: throw IOException("$name is no longer in the archive.")
        if (!set.complete) throw IOException("The archive holds only ${set.files.size} of ${set.shardCount} shards of ${set.displayName}.")
        cacheDir.mkdirs()
        val todo = set.files.filterNot { src -> File(cacheDir, src.name).let { it.isFile && it.length() == src.length() } }
        val missing = todo.sumOf { src ->
            val started = partFile(File(cacheDir, src.name)).let { if (it.isFile) it.length() else 0L }
            src.length() - started.coerceAtMost(src.length())
        }
        if (cacheDir.usableSpace < missing + SPACE_MARGIN) {
            throw IOException("Not enough free storage: ${ModelManager.formatBytes(missing)} needed, ${ModelManager.formatBytes(cacheDir.usableSpace)} free.")
        }
        var copied = set.sizeBytes - todo.sumOf { it.length() }
        onProgress(copied, set.sizeBytes)
        for (src in todo) {
            copyFile(src, File(cacheDir, src.name), resume = true) { n -> copied += n; onProgress(copied, set.sizeBytes) }
        }
        val first = File(cacheDir, set.name)
        // What was set on the card comes back with the model; what is set here already wins.
        modelManager.importOptions(first.path, readSidecar(dir, set.name), overwrite = false)
        first
    }

    /**
     * Removes an archive model from the cache; its card settings and attachments stay for the next fetch. Refused
     * unless the archive can be read and holds the same files - or [force] says the copy may go regardless.
     */
    suspend fun clearCache(name: String, force: Boolean = false) = withContext(io) {
        val count = SHARD.find(name)?.groupValues?.get(2)?.toIntOrNull() ?: 1
        val cached = shardNames(name, count).map { File(cacheDir, it) }
        if (!force) {
            val dir = folder()?.takeIf { it.isDirectory && it.canRead() } ?: throw ArchiveNotVerified("The archive is not connected, so the copy there cannot be checked.")
            val set = scan(dir, ARCHIVE_SCAN_DEPTH).firstOrNull { it.name == name }
                ?: throw ArchiveNotVerified("${displayName(name)} is not in the archive (any more).")
            val same = set.complete && set.files.all { a -> File(cacheDir, a.name).let { !it.exists() || it.length() == a.length() } }
            if (!same) throw ArchiveNotVerified("The files in the archive differ from the cached ones.")
        }
        folder()?.takeIf { it.isDirectory && it.canWrite() }?.let { dir ->
            runCatching { writeSidecar(dir, name, modelManager.exportOptions(File(cacheDir, name).path)) }
        }
        cached.forEach { f ->
            if (f.exists() && !f.delete()) throw IOException("Could not delete ${f.name}.")
            partFile(f).delete()
        }
    }

    // ---------------------------------------------------------------------------------- moving files in

    /**
     * Moves a model (from Downloads or the model folder) into the archive: copy, check, then delete the source,
     * file by file - a split model never needs more free space than one shard.
     */
    suspend fun moveToArchive(set: GgufSet, onProgress: (copied: Long, total: Long) -> Unit) = withContext(io) {
        val dir = folder() ?: throw IOException("Choose an archive folder first.")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("The archive folder is not connected: ${dir.path}")
        if (!dir.canWrite()) throw IOException("The archive folder cannot be written: ${dir.path}")
        if (!set.complete) throw IOException("Only ${set.files.size} of ${set.shardCount} shards of ${set.displayName} are there.")
        val options = modelManager.exportOptions(File(set.files.first().parentFile, set.name).path)
        moveFiles(set, dir, onProgress)
        if (options.isNotEmpty()) runCatching { writeSidecar(dir, set.name, options) }
        indexNames = indexNames + set.name
        Unit
    }

    /** Moves a download into the model folder: a rename on the same storage, a copy only when that is refused. */
    suspend fun moveToModelFolder(set: GgufSet, onProgress: (copied: Long, total: Long) -> Unit) = withContext(io) {
        val dir = modelManager.modelFolder() ?: throw IOException("The model folder is switched off (Models page).")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("The model folder cannot be created: ${dir.path}")
        if (!set.complete) throw IOException("Only ${set.files.size} of ${set.shardCount} shards of ${set.displayName} are there.")
        moveFiles(set, dir, onProgress)
    }

    private suspend fun moveFiles(set: GgufSet, dir: File, onProgress: (Long, Long) -> Unit) {
        set.files.firstOrNull { src -> File(dir, src.name).let { it.exists() && it.length() != src.length() } }?.let {
            throw IOException("A different file named ${it.name} is already in ${dir.path}.")
        }
        var copied = 0L
        onProgress(0, set.sizeBytes)
        for (src in set.files) {
            currentCoroutineContext().ensureActive()
            val dst = File(dir, src.name)
            val size = src.length()
            if (dst.isFile && dst.length() == size) {
                // Already there (an earlier move was interrupted after the copy): only the source is left to remove.
                src.delete()
                copied += size
            } else if (src.renameTo(dst)) {
                copied += size
            } else {
                if (dir.usableSpace < size + SPACE_MARGIN) {
                    throw IOException("Not enough free space in ${dir.path}: ${ModelManager.formatBytes(size)} needed for ${src.name}.")
                }
                copyFile(src, dst, resume = false) { n -> copied += n; onProgress(copied, set.sizeBytes) }
                if (dst.length() != size) throw IOException("${src.name} was not copied completely; the original was kept.")
                if (!src.delete()) throw IOException("${src.name} was copied, but the original could not be deleted.")
            }
            onProgress(copied, set.sizeBytes)
        }
    }

    // ---------------------------------------------------------------------------------- internals

    private suspend fun copyFile(src: File, dst: File, resume: Boolean, onBytes: (Long) -> Unit) {
        val part = partFile(dst)
        var done = if (resume && part.isFile && part.length() <= src.length()) part.length() else 0L
        if (done == 0L) part.delete()
        if (done > 0) onBytes(done)
        val target = dst.parentFile ?: throw IOException("No folder for ${dst.name}.")
        RandomAccessFile(src, "r").use { input ->
            input.seek(done)
            FileOutputStream(part, done > 0).use { out ->
                val buf = ByteArray(COPY_CHUNK)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    if (target.usableSpace < n + SPACE_MARGIN) throw IOException("The storage is nearly full; ${dst.name} was not finished.")
                    out.write(buf, 0, n)
                    done += n
                    onBytes(n.toLong())
                }
                out.fd.sync()
            }
        }
        if (part.length() != src.length()) throw IOException("${src.name}: ${part.length()} of ${src.length()} bytes arrived.")
        if (dst.exists()) dst.delete()
        if (!part.renameTo(dst)) throw IOException("Could not finish ${dst.name}.")
    }

    private fun partFile(dst: File): File = File(dst.parentFile, ".${dst.name}.part")

    private fun scan(root: File, depth: Int): List<GgufSet> {
        val files = try {
            root.walkTopDown().maxDepth(depth)
                .onEnter { it == root || !it.name.startsWith(".") }
                .filter { it.isFile && it.name.endsWith(".gguf", true) && !it.name.startsWith(".") }
                .toList()
        } catch (_: Exception) {
            emptyList()
        }
        val sets = ArrayList<GgufSet>()
        val (shards, singles) = files.partition { SHARD.containsMatchIn(it.name) }
        singles.forEach { sets += GgufSet(it.name, listOf(it), it.length(), 1) }
        shards.groupBy { f -> File(f.parentFile, firstShardName(f.name)).path }.forEach { (firstPath, group) ->
            val first = File(firstPath)
            val count = SHARD.find(first.name)?.groupValues?.get(2)?.toIntOrNull() ?: group.size
            val ordered = group.sortedBy { it.name }
            sets += GgufSet(first.name, ordered, ordered.sumOf { it.length() }, count)
        }
        return sets
    }

    private fun readIndex(): Map<String, Pair<Long, Int>> = try {
        val p = Properties()
        indexFile.inputStream().use(p::load)
        p.stringPropertyNames().associateWith { k ->
            val v = p.getProperty(k).split('|')
            (v.getOrNull(0)?.toLongOrNull() ?: 0L) to (v.getOrNull(1)?.toIntOrNull() ?: 1)
        }
    } catch (_: Exception) {
        emptyMap()
    }

    private fun writeIndex(sets: List<GgufSet>) {
        val p = Properties()
        sets.forEach { p.setProperty(it.name, "${it.sizeBytes}|${it.shardCount}") }
        runCatching {
            cacheDir.mkdirs()
            indexFile.outputStream().use { p.store(it, "BigMoeOnEdge model archive: what the archive folder held when it was last read") }
        }
        indexNames = sets.map { it.name }.toSet()
    }

    private fun sidecar(dir: File, name: String): File = File(dir, "$name.options.properties")

    private fun readSidecar(dir: File, name: String): Map<String, String> = try {
        val p = Properties()
        sidecar(dir, name).inputStream().use(p::load)
        p.stringPropertyNames().associateWith { p.getProperty(it) }
    } catch (_: Exception) {
        emptyMap()
    }

    private fun writeSidecar(dir: File, name: String, options: Map<String, String>) {
        if (options.isEmpty()) return
        val p = Properties()
        options.forEach { (k, v) -> p.setProperty(k, v) }
        sidecar(dir, name).outputStream().use { p.store(it, "BigMoeOnEdge per-model options of $name") }
    }

    /** Clearing the cache was refused because the archive copy could not be confirmed; the user may insist. */
    class ArchiveNotVerified(message: String) : IOException(message)

    companion object {
        private val SHARD = Regex("-(\\d{5})-of-(\\d{5})\\.gguf$", RegexOption.IGNORE_CASE)
        private const val SPACE_MARGIN = 256L * 1024 * 1024
        private const val COPY_CHUNK = 8 * 1024 * 1024
        private const val ARCHIVE_SCAN_DEPTH = 3
        private const val DOWNLOADS_SCAN_DEPTH = 2

        fun displayName(name: String): String = SHARD.find(name)?.let { name.substringBeforeLast(it.value) } ?: name

        /** "x-00003-of-00005.gguf" -> "x-00001-of-00005.gguf"; any other name unchanged. */
        fun firstShardName(name: String): String {
            val m = SHARD.find(name) ?: return name
            return name.substringBeforeLast(m.value) + "-00001-of-" + m.groupValues[2] + ".gguf"
        }

        fun shardNames(first: String, count: Int): List<String> {
            val m = SHARD.find(first) ?: return listOf(first)
            val prefix = first.substringBeforeLast(m.value)
            val of = m.groupValues[2]
            return (1..count.coerceAtLeast(1)).map { "$prefix-${it.toString().padStart(5, '0')}-of-$of.gguf" }
        }
    }
}
