package com.bigmoe.onedge.workspace

import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Moves a workspace tree between two roots that sit on different file systems (app-private storage and shared
 * storage), where a plain rename does not work. Copy first, delete later: [copyTree] never deletes anything
 * from the source, and [deleteCopied] only removes a source file once its copy is byte-identical, so an
 * interrupted move loses nothing and can simply be run again. Symbolic links are neither followed nor copied.
 * Pure java.io, unit-tested on the JVM.
 */
object WorkspaceMover {

    enum class OnConflict {
        /** A different file already sits at the target path: rename it "<stem> (before move).<ext>", then copy. */
        KEEP_EXISTING_ASIDE,

        /** A different file already sits at the target path: replace it. */
        OVERWRITE
    }

    /**
     * [copied] files were written, [unchanged] already had an identical copy at the target, [keptAside] lists
     * the target-relative paths that existing, different files were renamed to.
     */
    data class CopyResult(val copied: Int, val unchanged: Int, val keptAside: List<String>)

    private const val ASIDE_TAG = "before move"

    /**
     * Copies every file and folder below [from] into [to] with the same relative layout (empty folders too).
     * Names in [skipNames] are left out. [onFile] gets each relative path before it is copied.
     */
    fun copyTree(
        from: File,
        to: File,
        onConflict: OnConflict,
        skipNames: Set<String> = emptySet(),
        onFile: (String) -> Unit = {}
    ): CopyResult {
        if (!from.isDirectory) return CopyResult(0, 0, emptyList())
        checkNotNested(from, to)
        if (!to.isDirectory && !to.mkdirs()) throw IOException("Could not create ${to.path}")
        var copied = 0
        var unchanged = 0
        val aside = ArrayList<String>()

        fun walk(srcDir: File, dstDir: File, rel: String) {
            val children = srcDir.listFiles()?.sortedBy { it.name } ?: throw IOException("Could not list ${srcDir.path}")
            for (child in children) {
                if (child.name in skipNames || Files.isSymbolicLink(child.toPath())) continue
                val relPath = if (rel.isEmpty()) child.name else "$rel/${child.name}"
                val target = File(dstDir, child.name)
                if (child.isDirectory) {
                    if (target.exists() && !target.isDirectory) aside += moveAside(target, relPath)
                    if (!target.isDirectory && !target.mkdirs()) throw IOException("Could not create ${target.path}")
                    walk(child, target, relPath)
                } else if (child.isFile) {
                    onFile(relPath)
                    if (target.isDirectory) {
                        // A folder where a file should go is never overwritten, whatever the conflict policy.
                        aside += moveAside(target, relPath)
                    } else if (target.isFile) {
                        if (sameContent(child, target)) { unchanged++; continue }
                        if (onConflict == OnConflict.KEEP_EXISTING_ASIDE) aside += moveAside(target, relPath)
                    }
                    copyFile(child, target)
                    copied++
                }
            }
        }
        walk(from, to, "")
        return CopyResult(copied, unchanged, aside)
    }

    /**
     * Deletes every file below [from] whose copy at the same place below [to] is byte-identical, then every folder
     * left empty, [from] included. Files named in [skipNames] are housekeeping markers and are deleted as well.
     * Returns the [from]-relative paths that stayed behind (no identical copy, or the delete failed).
     */
    fun deleteCopied(from: File, to: File, skipNames: Set<String> = emptySet()): List<String> {
        if (!from.isDirectory) return emptyList()
        checkNotNested(from, to)
        val left = ArrayList<String>()

        fun walk(srcDir: File, dstDir: File, rel: String) {
            val children = srcDir.listFiles()?.sortedBy { it.name } ?: run { left += rel.ifEmpty { "." }; return }
            for (child in children) {
                val relPath = if (rel.isEmpty()) child.name else "$rel/${child.name}"
                when {
                    Files.isSymbolicLink(child.toPath()) -> left += relPath
                    child.isDirectory -> {
                        walk(child, File(dstDir, child.name), relPath)
                        if (child.list()?.isEmpty() == true) child.delete()
                    }
                    child.name in skipNames -> if (!child.delete()) left += relPath
                    else -> {
                        val copy = File(dstDir, child.name)
                        val deleted = copy.isFile && sameContent(child, copy) && child.delete()
                        if (!deleted) left += relPath
                    }
                }
            }
        }
        walk(from, to, "")
        if (from.list()?.isEmpty() == true) from.delete()
        return left
    }

    /** True when both are regular files with the same bytes. */
    fun sameContent(a: File, b: File): Boolean {
        if (!a.isFile || !b.isFile || a.length() != b.length()) return false
        a.inputStream().use { ia ->
            b.inputStream().use { ib ->
                val ba = ByteArray(1 shl 16)
                val bb = ByteArray(1 shl 16)
                while (true) {
                    val na = readFully(ia, ba)
                    val nb = readFully(ib, bb)
                    if (na != nb) return false
                    if (na == 0) return true
                    for (i in 0 until na) if (ba[i] != bb[i]) return false
                }
            }
        }
    }

    /** Fills [buf] as far as the stream allows (InputStream.readNBytes needs API 33, minSdk is 29). */
    private fun readFully(input: java.io.InputStream, buf: ByteArray): Int {
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        return n
    }

    /** "<stem> (before move).<ext>" next to [file], numbered if taken; renames [file] there, returns its new relative path. */
    private fun moveAside(file: File, relPath: String): String {
        val name = file.name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0 && file.isFile) name.substring(0, dot) else name
        val ext = if (dot > 0 && file.isFile) name.substring(dot) else ""
        var n = 1
        var candidate: File
        do {
            val tag = if (n == 1) ASIDE_TAG else "$ASIDE_TAG $n"
            candidate = File(file.parentFile, "$stem ($tag)$ext")
            n++
        } while (candidate.exists())
        if (!file.renameTo(candidate)) throw IOException("Could not rename ${file.path} out of the way")
        val dir = relPath.substringBeforeLast('/', "")
        return if (dir.isEmpty()) candidate.name else "$dir/${candidate.name}"
    }

    /** Copies through a temporary ".<name>.part" next to the target, so a cut-off copy never looks complete. */
    private fun copyFile(src: File, dst: File) {
        val tmp = File(dst.parentFile, ".${dst.name}.part")
        try {
            src.inputStream().use { input -> tmp.outputStream().use { output -> input.copyTo(output, 1 shl 16) } }
            tmp.setLastModified(src.lastModified())
            if (dst.exists() && !dst.delete()) throw IOException("Could not replace ${dst.path}")
            if (!tmp.renameTo(dst)) throw IOException("Could not write ${dst.path}")
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun checkNotNested(a: File, b: File) {
        val pa = a.canonicalFile.path
        val pb = b.canonicalFile.path
        if (pa == pb || pb.startsWith(pa + File.separator) || pa.startsWith(pb + File.separator)) {
            throw IOException("Source and target overlap: $pa / $pb")
        }
    }
}
