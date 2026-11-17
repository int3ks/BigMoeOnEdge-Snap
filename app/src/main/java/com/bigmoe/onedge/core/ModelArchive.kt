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

/** A model of the archive, as the Models page shows it: one entry however many sources hold it. */
data class ArchiveEntry(
    val name: String,
    val sizeBytes: Long,
    val shardCount: Int,
    /** Names of the sources that hold it (connected or not). */
    val sources: List<String>,
    /** A connected source holds it complete: it can be fetched or used in place now. */
    val reachable: Boolean,
    /** Where complete local copies are: the app's storage, the model folder, both or neither. */
    val copies: Set<FetchTarget>,
    /** One of its archive files is registered to be used in place, without a copy. */
    val inPlace: Boolean,
    /** A fetch was interrupted and can be continued. */
    val partial: Boolean,
    val complete: Boolean
) {
    val displayName: String get() = ModelArchive.displayName(name)
    val cached: Boolean get() = copies.isNotEmpty()
}

/** One archive folder: a USB stick, an SSD, the SD card - any folder with a file path. */
data class ArchiveSource(val name: String, val path: String)

/** A source and how it is right now. */
data class SourceStatus(
    val source: ArchiveSource,
    val reachable: Boolean,
    val modelCount: Int,
    val writable: Boolean,
    /**
     * The source path with links resolved. Models used in place are registered under their canonical path, which on
     * some devices differs (e.g. /storage/8ACF-22C7 -> /mnt/media_rw/8ACF-22C7).
     */
    val canonicalPath: String = source.path
)

/** A folder the page offers as a new source: `<volume>/models` on an attached removable volume. */
data class SourceSuggestion(val name: String, val path: String)

/** Free and total space of one of the places model files go (Models page). */
data class StorageSpace(val label: String, val path: String, val freeBytes: Long, val totalBytes: Long)

/** Where "Fetch" puts an archive model. */
enum class FetchTarget { APP, MODEL_FOLDER }

/**
 * Three places for model files, each with one job:
 *  - the **model folder** (Internal storage/Models): the models that are kept; used in place ([ModelManager]);
 *  - the **archive**: any number of source folders (USB sticks, SSDs, the SD card), so that a model is not downloaded
 *    again; each source keeps an index of what it held, so its models stay listed while it is not attached;
 *  - the **cache**, the app's own model directory: where an archive model is fetched to for use, and cleared from
 *    again ([clearCopy]) without losing what was set on its card.
 * Downloads only feeds the other two ([moveToModelFolder], [moveToArchive]).
 *
 * The archive is the original: fetching copies, clearing deletes only the copy. What is set on a model's card is
 * stored by path, so it survives a clear and a later fetch by itself; a copy of it travels beside the model in its
 * source (`<first file>.options.properties`) for the way between the places and for a reinstall.
 *
 * Fetching copies into the app's storage or, per model (the button pressed), into the model folder; an archive model can
 * also be used where it lies ([useInPlace]) - which of the three is quickest depends on the drive, so all are offered.
 *
 * Everything here uses plain file paths: the app holds the all-files access the model folder already needs. A source
 * on the network (no file path) is not supported yet.
 */
class ModelArchive(
    private val cacheDir: File,
    private val modelManager: ModelManager,
    private val downloadsDir: () -> File?,
    /** Removable volumes attached right now: their description (e.g. "SanDisk USB drive") and root. */
    private val removableRoots: () -> List<Pair<String, File>> = { emptyList() },
    private val io: CoroutineDispatcher = Dispatchers.IO
) {
    private val sourcesFile: File get() = File(cacheDir, ".archive-sources.properties")
    private val lastTargetFile: File get() = File(cacheDir, ".archive-last-target")

    @Volatile
    private var indexNames: Set<String> = emptySet()

    init {
        migrateSingleFolder()
        indexNames = sources().flatMap { readIndex(it).keys }.toSet()
        // Attachments of a model that is only in the archive right now stay ([ModelManager.keepLinksFor]).
        modelManager.keepLinksFor = { path ->
            val f = File(path)
            firstShardName(f.name) in indexNames && f.parentFile?.let { parent ->
                runCatching { localDirs().any { it.canonicalPath == parent.canonicalPath } }.getOrDefault(false)
            } == true
        }
    }

    /** Where a local copy of an archive model can be: the app's storage first, then the model folder. */
    private fun localDirs(): List<File> = listOfNotNull(cacheDir, modelManager.modelFolder())

    private fun dirOf(target: FetchTarget): File? = when (target) {
        FetchTarget.APP -> cacheDir
        FetchTarget.MODEL_FOLDER -> modelManager.modelFolder()
    }


    // ---------------------------------------------------------------------------------- sources

    fun sources(): List<ArchiveSource> = try {
        val p = Properties()
        sourcesFile.inputStream().use(p::load)
        p.stringPropertyNames().filter { it.endsWith(".path") }.sortedBy { it.substringBefore('.').toIntOrNull() ?: 0 }
            .mapNotNull { key ->
                val n = key.substringBefore('.')
                val path = p.getProperty(key)?.trim().orEmpty()
                if (path.isEmpty()) null else ArchiveSource(p.getProperty("$n.name")?.trim().orEmpty().ifEmpty { File(path).name }, path)
            }
    } catch (_: Exception) {
        emptyList()
    }

    private fun saveSources(list: List<ArchiveSource>) {
        cacheDir.mkdirs()
        val p = Properties()
        list.forEachIndexed { i, s ->
            p.setProperty("$i.name", s.name)
            p.setProperty("$i.path", s.path)
        }
        sourcesFile.outputStream().use { p.store(it, "BigMoeOnEdge model archive sources") }
    }

    /** Adds a source folder (or renames the one with this path). */
    suspend fun addSource(name: String, path: String) = withContext(io) {
        val p = path.trim().trimEnd('/')
        if (p.isEmpty()) throw IOException("No folder.")
        val list = sources().filterNot { samePath(it.path, p) }
        saveSources(list + ArchiveSource(name.trim().ifEmpty { File(p).name }, p))
    }

    /** Forgets a source (its files stay where they are). */
    suspend fun removeSource(path: String) = withContext(io) {
        saveSources(sources().filterNot { samePath(it.path, path) })
        indexFile(path).delete()
        indexNames = sources().flatMap { readIndex(it).keys }.toSet()
        if (samePath(runCatching { lastTargetFile.readText().trim() }.getOrDefault(""), path)) lastTargetFile.delete()
        Unit
    }

    private fun reachable(s: ArchiveSource): Boolean = File(s.path).let { it.isDirectory && it.canRead() }

    /** Every source with its state; scans the connected ones. */
    suspend fun sourceStatus(): List<SourceStatus> = withContext(io) {
        sources().map { s ->
            val ok = reachable(s)
            val count = if (ok) scan(File(s.path), ARCHIVE_SCAN_DEPTH).size else readIndex(s).size
            val canonical = if (ok) runCatching { File(s.path).canonicalPath }.getOrDefault(s.path) else s.path
            SourceStatus(s, ok, count, ok && File(s.path).canWrite(), canonical)
        }
    }

    /** `<volume>/models` on every attached removable volume that is not a source yet, the existing folders first. */
    suspend fun suggestions(): List<SourceSuggestion> = withContext(io) {
        val known = sources()
        runCatching { removableRoots() }.getOrDefault(emptyList())
            .map { (label, root) -> label to File(root, "models") }
            .filter { (_, dir) -> known.none { samePath(it.path, dir.path) } }
            .sortedByDescending { (_, dir) -> dir.isDirectory }
            .map { (label, dir) -> SourceSuggestion(label.ifBlank { dir.parentFile?.name.orEmpty() }, dir.path) }
    }

    /** The source a model was moved to last; offered first the next time. */
    fun lastTarget(): String? = runCatching { lastTargetFile.readText().trim() }.getOrDefault("").ifEmpty { null }

    /** Sources a model can be moved into now (connected and writable), the last used first. */
    suspend fun writableSources(): List<ArchiveSource> = withContext(io) {
        val last = lastTarget()
        sources().filter { reachable(it) && File(it.path).canWrite() }.sortedByDescending { last != null && samePath(it.path, last) }
    }

    /** The old single archive folder (v0.6.80-0.6.83) becomes the first source, its index goes with it. */
    private fun migrateSingleFolder() {
        val old = File(cacheDir, ".archive-folder")
        if (!old.isFile) return
        runCatching {
            val path = old.readText().trim()
            if (path.isNotEmpty() && sources().none { samePath(it.path, path) }) {
                saveSources(sources() + ArchiveSource(File(path).name.ifEmpty { "Archive" }, path))
                File(cacheDir, ".archive-index.properties").let { if (it.isFile) it.renameTo(indexFile(path)) }
            }
            old.delete()
        }
    }

    /**
     * Free space of the app storage, the model folder and every connected source. A folder that does not exist yet
     * reports the volume it would be created on.
     */
    suspend fun storageSpace(): List<StorageSpace> = withContext(io) {
        fun of(label: String, dir: File?): StorageSpace? {
            val d = dir ?: return null
            var probe: File? = d
            while (probe != null && !probe.exists()) probe = probe.parentFile
            val p = probe ?: return null
            val total = runCatching { p.totalSpace }.getOrDefault(0L)
            if (total <= 0L) return null
            return StorageSpace(label, d.path, runCatching { p.usableSpace }.getOrDefault(0L), total)
        }
        listOfNotNull(of("App storage", cacheDir), of("Model folder", modelManager.modelFolder())) +
            sources().filter { reachable(it) }.mapNotNull { of("Archive: ${it.name}", File(it.path)) }
    }

    // ---------------------------------------------------------------------------------- listing

    /** The archive's models over all sources. A source that is not attached contributes what it held last time. */
    suspend fun list(): List<ArchiveEntry> = withContext(io) {
        class Acc(val name: String, var size: Long, var shards: Int) {
            val sources = ArrayList<String>()
            var reachable = false
            var complete = false
            var inPlace = false
            var anyReachableSet = false
        }
        val acc = LinkedHashMap<String, Acc>()
        for (src in sources()) {
            if (reachable(src)) {
                val sets = scan(File(src.path), ARCHIVE_SCAN_DEPTH)
                writeIndex(src, sets)
                for (set in sets) {
                    val a = acc.getOrPut(set.name) { Acc(set.name, set.sizeBytes, set.shardCount) }
                    a.sources += src.name
                    a.anyReachableSet = true
                    if (set.complete) {
                        a.reachable = true
                        a.complete = true
                        a.size = set.sizeBytes
                    }
                    val first = File(set.files.first().parentFile, set.name)
                    if (modelManager.isRegisteredPath(first.path)) a.inPlace = true
                }
            } else {
                for ((name, meta) in readIndex(src)) {
                    val a = acc.getOrPut(name) { Acc(name, meta.first, meta.second) }
                    a.sources += "${src.name} (not connected)"
                    if (!a.anyReachableSet) a.complete = true // what the index remembers was complete when it was read
                }
            }
        }
        indexNames = sources().flatMap { readIndex(it).keys }.toSet()
        acc.values.map { a ->
            val names = shardNames(a.name, a.shards)
            ArchiveEntry(
                name = a.name, sizeBytes = a.size, shardCount = a.shards, sources = a.sources, reachable = a.reachable,
                copies = FetchTarget.values().filterTo(HashSet()) { target ->
                    dirOf(target)?.let { d -> names.all { File(d, it).isFile } } == true
                },
                inPlace = a.inPlace,
                partial = localDirs().any { d -> names.any { partFile(File(d, it)).isFile } },
                complete = a.complete
            )
        }.sortedBy { it.displayName.lowercase() }
    }

    /** GGUFs waiting in Downloads. */
    suspend fun listDownloads(): List<GgufSet> = withContext(io) {
        val dir = runCatching { downloadsDir() }.getOrNull()?.takeIf { it.isDirectory && it.canRead() } ?: return@withContext emptyList()
        scan(dir, DOWNLOADS_SCAN_DEPTH).sortedBy { it.displayName.lowercase() }
    }

    /** Whether [path] is inside one of the sources (a model used in place from the archive). */
    fun isInArchive(path: String): Boolean = sources().any { path.startsWith(it.path.trimEnd('/') + "/") }

    /** The model that starts with [first], with every shard that is there. */
    fun setOf(first: File): GgufSet {
        val m = SHARD.find(first.name) ?: return GgufSet(first.name, listOf(first), first.length(), 1)
        val count = m.groupValues[2].toIntOrNull() ?: 1
        val files = shardNames(first.name, count).map { File(first.parentFile, it) }.filter { it.isFile }
        return GgufSet(first.name, files, files.sumOf { it.length() }, count)
    }

    /** The first connected source that holds [name] complete. */
    private fun findComplete(name: String): Pair<ArchiveSource, GgufSet>? {
        for (src in sources()) {
            if (!reachable(src)) continue
            val set = scan(File(src.path), ARCHIVE_SCAN_DEPTH).firstOrNull { it.name == name && it.complete } ?: continue
            return src to set
        }
        return null
    }

    private fun notFound(name: String): IOException {
        val known = sources().filter { readIndex(it).containsKey(name) }.map { it.name }
        return IOException(
            if (known.isEmpty()) "${displayName(name)} is in no connected source."
            else "${displayName(name)} is on ${known.joinToString()}, which is not connected."
        )
    }

    // ---------------------------------------------------------------------------------- archive -> local

    /**
     * Copies an archive model (from the first connected source that holds it) into [target]. Files that
     * are already there are skipped and an interrupted copy is continued, so a cancelled fetch (or a pulled cable)
     * loses nothing. Returns the local first file.
     */
    suspend fun fetch(name: String, into: FetchTarget, onProgress: (copied: Long, total: Long) -> Unit): File = withContext(io) {
        val (src, set) = findComplete(name) ?: throw notFound(name)
        val target = dirOf(into) ?: throw IOException("The model folder is switched off; fetch into the app storage or turn it on.")
        if (!target.isDirectory && !target.mkdirs()) throw IOException("Cannot create ${target.path}.")
        val todo = set.files.filterNot { s -> File(target, s.name).let { it.isFile && it.length() == s.length() } }
        val missing = todo.sumOf { s ->
            val started = partFile(File(target, s.name)).let { if (it.isFile) it.length() else 0L }
            s.length() - started.coerceAtMost(s.length())
        }
        if (target.usableSpace < missing + SPACE_MARGIN) {
            throw IOException("Not enough free storage: ${ModelManager.formatBytes(missing)} needed, ${ModelManager.formatBytes(target.usableSpace)} free.")
        }
        // Starts at what is there already, whole files and the part of an interrupted one.
        var copied = set.sizeBytes - missing
        onProgress(copied, set.sizeBytes)
        for (s in todo) {
            copyFile(s, File(target, s.name), resume = true) { n -> copied += n; onProgress(copied, set.sizeBytes) }
        }
        val first = File(target, set.name)
        // What was set on the card comes back with the model; what is set here already wins.
        modelManager.importOptions(first.path, readSidecar(File(set.files.first().parentFile, set.name)), overwrite = false)
        first
    }

    /** The local copy of archive model [name] in [target], when there is one. */
    fun copyPath(name: String, target: FetchTarget): String? = dirOf(target)?.let { File(it, name) }?.takeIf { it.isFile }?.path

    /**
     * Deletes ONE local copy of an archive model - the file at [path] (with its shards) and nothing else, so a copy kept
     * in the model folder survives clearing the one in the app's storage. Its card settings and attachments stay for
     * the next fetch. Refused unless a connected source holds the same files - or [force] says it may go.
     */
    suspend fun clearCopy(path: String, force: Boolean = false) = withContext(io) {
        val first = File(path)
        val name = first.name
        val dir = first.parentFile ?: throw IOException("No folder for $name.")
        val isLocal = runCatching { localDirs().any { it.canonicalPath == dir.canonicalPath } }.getOrDefault(false)
        if (!isLocal) throw IOException("$name is not a copy in the app storage or the model folder.")
        val count = SHARD.find(name)?.groupValues?.get(2)?.toIntOrNull() ?: 1
        val files = shardNames(name, count).map { File(dir, it) }
        // A source whose files match this copy: it gets the card's settings, and it is the proof the model is safe.
        val match = sources().filter { reachable(it) }.firstNotNullOfOrNull { src ->
            scan(File(src.path), ARCHIVE_SCAN_DEPTH).firstOrNull { set ->
                set.name == name && set.complete && set.files.all { a -> File(dir, a.name).let { !it.exists() || it.length() == a.length() } }
            }
        }
        if (!force && match == null) {
            throw ArchiveNotVerified(
                if (sources().none { reachable(it) }) "No archive source is connected, so the copy there cannot be checked."
                else "No connected source holds the same files as this copy."
            )
        }
        match?.let { set ->
            val archiveFirst = File(set.files.first().parentFile, set.name)
            if (archiveFirst.parentFile?.canWrite() == true) {
                // What is set on this copy goes back beside the archive model (kept when this copy has nothing set).
                runCatching { writeSidecar(archiveFirst, modelManager.exportOptions(first.path)) }
            }
        }
        files.forEach { f ->
            if (f.exists() && !f.delete()) throw IOException("Could not delete ${f.name}.")
            partFile(f).delete()
        }
    }

    /**
     * Uses an archive model where it lies (the first connected source that holds it): its first file is registered
     * like any file used in place, no copy. The card's "delete" then only removes that registration.
     */
    suspend fun useInPlace(name: String): ModelFile = withContext(io) {
        val (_, set) = findComplete(name) ?: throw notFound(name)
        val first = File(set.files.first().parentFile, set.name)
        val model = modelManager.registerExistingPath(first.path)
        modelManager.importOptions(model.path, readSidecar(first), overwrite = false)
        model
    }

    // ---------------------------------------------------------------------------------- moving files in

    /**
     * Moves a model (from Downloads or the model folder) into the source at [sourcePath]: copy, check, then delete the
     * source file, file by file - a split model never needs more free space than one shard.
     */
    suspend fun moveToArchive(set: GgufSet, sourcePath: String, onProgress: (copied: Long, total: Long) -> Unit) = withContext(io) {
        val src = sources().firstOrNull { samePath(it.path, sourcePath) } ?: throw IOException("Unknown archive source: $sourcePath")
        val dir = File(src.path)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("${src.name} is not connected: ${dir.path}")
        if (!dir.canWrite()) throw IOException("${src.name} cannot be written: ${dir.path}")
        if (!set.complete) throw IOException("Only ${set.files.size} of ${set.shardCount} shards of ${set.displayName} are there.")
        val options = modelManager.exportOptions(File(set.files.first().parentFile, set.name).path)
        moveFiles(set, dir, onProgress)
        if (options.isNotEmpty()) runCatching { writeSidecar(File(dir, set.name), options) }
        runCatching { cacheDir.mkdirs(); lastTargetFile.writeText(src.path) }
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

    /** [onBytes] counts what this call copies; the part a resumed copy finds is the caller's to account for. */
    private suspend fun copyFile(src: File, dst: File, resume: Boolean, onBytes: (Long) -> Unit) {
        val part = partFile(dst)
        var done = if (resume && part.isFile && part.length() <= src.length()) part.length() else 0L
        if (done == 0L) part.delete()
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

    private fun samePath(a: String, b: String): Boolean = a.trimEnd('/') == b.trimEnd('/')

    /** One index per source, named by its path, in the app's storage (a source may be read-only). */
    private fun indexFile(path: String): File =
        File(cacheDir, ".archive-index-" + Integer.toHexString(path.trimEnd('/').hashCode()) + ".properties")

    private fun readIndex(src: ArchiveSource): Map<String, Pair<Long, Int>> = try {
        val p = Properties()
        indexFile(src.path).inputStream().use(p::load)
        p.stringPropertyNames().associateWith { k ->
            val v = p.getProperty(k).split('|')
            (v.getOrNull(0)?.toLongOrNull() ?: 0L) to (v.getOrNull(1)?.toIntOrNull() ?: 1)
        }
    } catch (_: Exception) {
        emptyMap()
    }

    private fun writeIndex(src: ArchiveSource, sets: List<GgufSet>) {
        val p = Properties()
        sets.forEach { p.setProperty(it.name, "${it.sizeBytes}|${it.shardCount}") }
        runCatching {
            cacheDir.mkdirs()
            indexFile(src.path).outputStream().use { p.store(it, "What the archive source ${src.name} held when it was last read") }
        }
    }

    /** The options file beside an archive model's first file. */
    private fun sidecar(first: File): File = File(first.parentFile, "${first.name}.options.properties")

    private fun readSidecar(first: File): Map<String, String> = try {
        val p = Properties()
        sidecar(first).inputStream().use(p::load)
        p.stringPropertyNames().associateWith { p.getProperty(it) }
    } catch (_: Exception) {
        emptyMap()
    }

    private fun writeSidecar(first: File, options: Map<String, String>) {
        if (options.isEmpty()) return
        val p = Properties()
        options.forEach { (k, v) -> p.setProperty(k, v) }
        sidecar(first).outputStream().use { p.store(it, "BigMoeOnEdge per-model options of ${first.name}") }
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
