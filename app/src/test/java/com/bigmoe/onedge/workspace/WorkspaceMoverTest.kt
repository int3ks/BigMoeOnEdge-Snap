package com.bigmoe.onedge.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class WorkspaceMoverTest {
    private fun tempDir(prefix: String): File =
        File.createTempFile(prefix, "").apply { delete(); mkdirs() }

    private fun File.put(rel: String, text: String): File =
        File(this, rel).apply { parentFile?.mkdirs(); writeText(text) }

    private fun tree(root: File): List<String> =
        root.walkTopDown().filter { it != root }.map { it.relativeTo(root).path.replace(File.separatorChar, '/') + if (it.isDirectory) "/" else "" }
            .sorted().toList()

    @Test
    fun copiesFilesAndEmptyFoldersThenDeletesOnlyIdenticalSources() {
        val from = tempDir("bmoe-from")
        val to = tempDir("bmoe-to").apply { delete() } // missing target is created
        try {
            from.put("a.txt", "a")
            from.put("uploads/report.md", "report")
            File(from, "project/empty").mkdirs()
            File(from, Workspace.NO_MEDIA).createNewFile()

            val r = WorkspaceMover.copyTree(from, to, WorkspaceMover.OnConflict.KEEP_EXISTING_ASIDE, Workspace.HIDDEN_NAMES)
            assertEquals(2, r.copied)
            assertEquals(0, r.unchanged)
            assertTrue(r.keptAside.isEmpty())
            assertEquals(listOf("a.txt", "project/", "project/empty/", "uploads/", "uploads/report.md"), tree(to))
            assertEquals("report", File(to, "uploads/report.md").readText())

            val left = WorkspaceMover.deleteCopied(from, to, Workspace.HIDDEN_NAMES)
            assertTrue(left.isEmpty())
            assertFalse(from.exists()) // emptied completely, marker included
        } finally { from.deleteRecursively(); to.deleteRecursively() }
    }

    @Test
    fun keepsDifferentExistingTargetFilesAsideAndSkipsIdenticalOnes() {
        val from = tempDir("bmoe-from")
        val to = tempDir("bmoe-to")
        try {
            from.put("same.txt", "same")
            from.put("notes.md", "new notes")
            to.put("same.txt", "same")
            to.put("notes.md", "old notes from a previous install")

            val r = WorkspaceMover.copyTree(from, to, WorkspaceMover.OnConflict.KEEP_EXISTING_ASIDE)
            assertEquals(1, r.copied)
            assertEquals(1, r.unchanged)
            assertEquals(listOf("notes (before move).md"), r.keptAside)
            assertEquals("new notes", File(to, "notes.md").readText())
            assertEquals("old notes from a previous install", File(to, "notes (before move).md").readText())

            // A second aside of the same name gets a number instead of overwriting the first one.
            from.put("notes.md", "newer notes")
            val again = WorkspaceMover.copyTree(from, to, WorkspaceMover.OnConflict.KEEP_EXISTING_ASIDE)
            assertEquals(listOf("notes (before move 2).md"), again.keptAside)
            assertEquals("old notes from a previous install", File(to, "notes (before move).md").readText())
        } finally { from.deleteRecursively(); to.deleteRecursively() }
    }

    @Test
    fun overwriteReplacesChangedFilesWithoutAside() {
        val from = tempDir("bmoe-from")
        val to = tempDir("bmoe-to")
        try {
            from.put("late.txt", "written during the move")
            to.put("late.txt", "first copy")
            val r = WorkspaceMover.copyTree(from, to, WorkspaceMover.OnConflict.OVERWRITE)
            assertEquals(1, r.copied)
            assertTrue(r.keptAside.isEmpty())
            assertEquals("written during the move", File(to, "late.txt").readText())
            assertEquals(listOf("late.txt"), tree(to))
        } finally { from.deleteRecursively(); to.deleteRecursively() }
    }

    @Test
    fun deleteKeepsSourcesWithoutAnIdenticalCopy() {
        val from = tempDir("bmoe-from")
        val to = tempDir("bmoe-to")
        try {
            from.put("copied.txt", "x")
            from.put("dir/changed.txt", "changed after copy")
            to.put("copied.txt", "x")
            to.put("dir/changed.txt", "older copy")
            val left = WorkspaceMover.deleteCopied(from, to)
            assertEquals(listOf("dir/changed.txt"), left)
            assertFalse(File(from, "copied.txt").exists())
            assertTrue(File(from, "dir/changed.txt").isFile)
        } finally { from.deleteRecursively(); to.deleteRecursively() }
    }

    @Test
    fun interruptedMoveCanBeRunAgain() {
        val from = tempDir("bmoe-from")
        val to = tempDir("bmoe-to")
        try {
            from.put("a.txt", "a")
            from.put("b.txt", "b")
            to.put("a.txt", "a") // the first run got this far
            val r = WorkspaceMover.copyTree(from, to, WorkspaceMover.OnConflict.KEEP_EXISTING_ASIDE)
            assertEquals(1, r.copied)
            assertEquals(1, r.unchanged)
            assertTrue(r.keptAside.isEmpty())
            assertTrue(WorkspaceMover.deleteCopied(from, to).isEmpty())
            assertEquals(listOf("a.txt", "b.txt"), tree(to))
        } finally { from.deleteRecursively(); to.deleteRecursively() }
    }

    @Test
    fun refusesOverlappingRoots() {
        val from = tempDir("bmoe-from")
        try {
            from.put("a.txt", "a")
            try {
                WorkspaceMover.copyTree(from, File(from, "inner"), WorkspaceMover.OnConflict.OVERWRITE)
                throw AssertionError("nested target accepted")
            } catch (_: IOException) { }
            try {
                WorkspaceMover.deleteCopied(from, from)
                throw AssertionError("same root accepted")
            } catch (_: IOException) { }
            assertTrue(File(from, "a.txt").isFile)
        } finally { from.deleteRecursively() }
    }

    @Test
    fun symlinksAreNeitherFollowedNorCopied() {
        val from = tempDir("bmoe-from")
        val to = tempDir("bmoe-to")
        val outside = File.createTempFile("bmoe-outside", ".txt").apply { writeText("secret") }
        try {
            from.put("a.txt", "a")
            try {
                java.nio.file.Files.createSymbolicLink(File(from, "link.txt").toPath(), outside.toPath())
            } catch (_: Exception) { return }
            val r = WorkspaceMover.copyTree(from, to, WorkspaceMover.OnConflict.OVERWRITE)
            assertEquals(1, r.copied)
            assertEquals(listOf("a.txt"), tree(to))
            assertEquals(listOf("link.txt"), WorkspaceMover.deleteCopied(from, to))
            assertTrue(outside.isFile)
        } finally { from.deleteRecursively(); to.deleteRecursively(); outside.delete() }
    }

    @Test
    fun sameContentComparesBytes() {
        val dir = tempDir("bmoe-cmp")
        try {
            val big = ByteArray(200_000) { (it % 251).toByte() }
            val a = File(dir, "a.bin").apply { writeBytes(big) }
            val b = File(dir, "b.bin").apply { writeBytes(big) }
            val c = File(dir, "c.bin").apply { writeBytes(big.copyOf().also { it[150_000] = 7 }) }
            assertTrue(WorkspaceMover.sameContent(a, b))
            assertFalse(WorkspaceMover.sameContent(a, c))
            assertFalse(WorkspaceMover.sameContent(a, File(dir, "missing")))
        } finally { dir.deleteRecursively() }
    }
}
