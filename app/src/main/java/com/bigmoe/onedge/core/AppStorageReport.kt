package com.bigmoe.onedge.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** One thing in the app's own storage, with what it is and whether it may go. */
data class StorageItem(
    val title: String,
    val explanation: String,
    val bytes: Long,
    /** The files or folders it stands for (a group of small files has several). */
    val paths: List<String>,
    /** Whether the page offers to delete it; [deleteNote] says what deleting means. */
    val deletable: Boolean,
    val deleteNote: String? = null
)

/**
 * What uses the app's storage (Android counts it as the app's "data"): the app's private folders and its folders on
 * shared storage, broken down into the parts the app knows, each with a short explanation. Models are listed one by
 * one; the model folder in Internal storage/Models is not part of it (it belongs to the user, not to the app).
 */
class AppStorageReport(context: Context) {
    private val app = context.applicationContext
    private val dataDir = File(app.applicationInfo.dataDir)
    private val files = app.filesDir
    private val cache = app.cacheDir

    suspend fun scan(): List<StorageItem> = withContext(Dispatchers.IO) {
        val out = ArrayList<StorageItem>()
        val small = ArrayList<File>()
        fun add(f: File, title: String, why: String, deletable: Boolean, note: String? = null) {
            if (!f.exists()) return
            out += StorageItem(title, why, size(f), listOf(f.path), deletable, note)
        }

        // The app storage's model folder: models one by one, unfinished copies, the small bookkeeping files.
        val models = File(files, "models")
        models.listFiles().orEmpty().forEach { f ->
            when {
                f.isFile && f.name.endsWith(".gguf", true) && !f.name.startsWith(".") ->
                    add(f, f.name, "A model (or a shard of one) left in the app storage by a version before 7.17. It is moved " +
                        "into the model folder at start when there is room; delete what you do not need.", true,
                        "The file is deleted from the app storage, with the other parts of a split model and what was attached to it.")
                f.isFile && f.name.endsWith(".part") ->
                    add(f, "Unfinished copy: ${f.name.removePrefix(".").removeSuffix(".part")}",
                        "Left by an import or an archive fetch that was cancelled or broke off. A fetch continues from it; nothing else uses it.",
                        true, "The next fetch of this model starts from the beginning.")
                f.isDirectory && (f.name == ".incomplete-split-imports" || f.name.startsWith("split-") || f.name.startsWith(".pending")) ->
                    add(f, "Unfinished split-model import (${f.name})",
                        "Shards of a split model whose import did not finish. Also listed under \"Incomplete imports\" above.",
                        true, "That import cannot be continued any more.")
                f.isDirectory && size(f) >= GROUP_BELOW -> add(f, "Model storage: ${f.name}", "A folder inside the app's model storage.", false)
                else -> small += f
            }
        }

        add(File(files, "kv-snapshots"), "KV snapshots",
            "Saved chat contexts, so a chat continues without prefilling it again after a chat switch, a reload or an app restart. Oldest go first above 6 GB.",
            true, "Each chat prefills once more the next time it is opened.")
        add(File(files, "workspace"), "Agent workspace (private)",
            "Files the agent created or downloaded while the workspace was the private one.", true, "The agent's private files are deleted.")
        add(File(files, "logs"), "Log files", "Written while \"Record log\" and \"Write to file\" are on (Settings → Diagnostics).", true)
        add(File(files, "last-crash.txt"), "Last crash report", "The stack trace of the last Java crash, shown in the next log.", true)
        add(File(files, "hexagon-dsp"), "NPU runtime", "Libraries for the Hexagon NPU, copied out of the app so the DSP can load them.", false)
        add(File(files, "device-tune"), "Device tuner results", "What the device tuner measured per model.", false)
        add(File(files, "datastore"), "Settings", "The app's settings.", false)
        val known = setOf("models", "kv-snapshots", "workspace", "logs", "last-crash.txt", "hexagon-dsp", "device-tune", "datastore")
        files.listFiles().orEmpty().filter { it.name !in known }.forEach { f ->
            if (size(f) >= GROUP_BELOW) add(f, "files/${f.name}", "Not one of the app's known files.", false) else small += f
        }

        add(File(dataDir, "databases"), "Chats", "The chat history (database).", false)
        add(File(cache, "updates"), "Downloaded app updates", "APKs the in-app updater downloaded.", true)
        add(File(cache, "conversation_exports"), "Exported conversations", "Files made to share a conversation.", true)
        val cacheRest = cache.listFiles().orEmpty().filter { it.name != "updates" && it.name != "conversation_exports" }
        if (cacheRest.isNotEmpty()) {
            out += StorageItem("Other temporary files", "The app's cache folder; Android may also clear it on its own.",
                cacheRest.sumOf { size(it) }, cacheRest.map { it.path }, true)
        }
        app.getExternalFilesDir(null)?.let { ext ->
            add(File(ext, "metrics"), "Metrics CSV files", "Written while \"Metrics CSV\" is on (Settings → Diagnostics).", true)
            ext.listFiles().orEmpty().filter { it.name != "metrics" }.forEach { f ->
                add(f, "Android/data …/files/${f.name}", "In the app's folder on shared storage.", false)
            }
        }
        app.externalCacheDir?.takeIf { it.exists() }?.let { add(it, "Temporary files on shared storage", "The app's cache folder on shared storage.", true) }
        listOf("shared_prefs", "code_cache", "no_backup", "app_webview").map { File(dataDir, it) }.filter { it.exists() }.forEach { small += it }

        if (small.isNotEmpty()) {
            out += StorageItem("Small files", "Settings, indexes, links between models and add-ons, and system folders.",
                small.sumOf { size(it) }, small.map { it.path }, false)
        }
        out.filter { it.bytes > 0 }.sortedByDescending { it.bytes }
    }

    /** Deletes what [item] stands for. Only items the page offers to delete. */
    suspend fun delete(item: StorageItem) = withContext(Dispatchers.IO) {
        if (!item.deletable) return@withContext
        item.paths.map(::File).forEach { f -> if (f.isDirectory) f.deleteRecursively() else f.delete() }
    }

    private fun size(f: File): Long =
        if (f.isFile) f.length() else runCatching { f.walkTopDown().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)

    companion object {
        private const val GROUP_BELOW = 1L shl 20
    }
}
