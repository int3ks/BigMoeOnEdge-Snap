package com.bigmoe.onedge.core

import android.content.Context
import android.os.Build
import android.os.Environment
import com.bigmoe.onedge.workspace.Workspace
import com.bigmoe.onedge.workspace.WorkspaceMover
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException

/**
 * Where the workspace lives: app-private storage (default) or `<internal storage>/BigMoe/workspace`, a folder that
 * other apps with storage access (Termux) can read and write. The choice is kept in SharedPreferences rather than
 * DataStore because it must be read synchronously, before the first tool or attachment touches the workspace.
 */
class WorkspaceLocation(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val privateRoot: File = File(context.filesDir, "workspace")

    @Suppress("DEPRECATION") // direct File access to shared storage is exactly what all-files access grants
    val sharedRoot: File = File(Environment.getExternalStorageDirectory(), SHARED_SUBDIR)

    /** All-files access (MANAGE_EXTERNAL_STORAGE) exists from Android 11 on; Android 10 keeps the private workspace. */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun accessGranted(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    /** True when [root] (a workspace root) is the shared folder, i.e. other apps such as Termux can reach it. */
    fun isShared(root: File): Boolean {
        val shared = try { sharedRoot.canonicalFile } catch (_: java.io.IOException) { sharedRoot.absoluteFile }
        return root.path == shared.path
    }

    var sharedWanted: Boolean
        get() = prefs.getBoolean(KEY_SHARED, false)
        set(value) { prefs.edit().putBoolean(KEY_SHARED, value).commit() }

    /** Set when the app had to fall back to the private workspace at start; read once by [WorkspaceSwitcher]. */
    var accessLostNotice: Boolean
        get() = prefs.getBoolean(KEY_ACCESS_LOST, false)
        set(value) { prefs.edit().putBoolean(KEY_ACCESS_LOST, value).commit() }

    /**
     * The root to open at app start. If the shared folder was chosen but all-files access has been taken away since,
     * the app goes back to the private workspace (the shared files are left untouched) and says so in Settings.
     */
    fun startupRoot(): File {
        if (!sharedWanted) return privateRoot
        if (accessGranted()) return sharedRoot
        sharedWanted = false
        accessLostNotice = true
        return privateRoot
    }

    companion object {
        /** Below Environment.getExternalStorageDirectory(), i.e. "Internal storage/BigMoe/workspace". */
        const val SHARED_SUBDIR = "BigMoe/workspace"
        private const val PREFS = "workspace_location"
        private const val KEY_SHARED = "shared"
        private const val KEY_ACCESS_LOST = "access_lost_notice"
    }
}

/**
 * Moves the workspace between [WorkspaceLocation.privateRoot] and [WorkspaceLocation.sharedRoot] and points the one
 * [Workspace] instance at the new place. Runs in its own scope so leaving the Settings screen cannot cut a move off.
 *
 * Order of a move: copy everything (an existing, different file at the target is renamed "(before move)", never
 * overwritten) → switch the workspace → copy again to catch files written during the first pass → delete only the
 * source files whose copy is byte-identical. A failure before the switch leaves the old place complete and in use.
 * Paths stored in the database are workspace-relative, so artifacts and attachments stay linked.
 */
class WorkspaceSwitcher(
    private val workspace: Workspace,
    private val location: WorkspaceLocation
) {
    data class State(
        /** The workspace currently lives in the shared folder. */
        val shared: Boolean,
        /** Current workspace root, for display. */
        val path: String,
        /** Where the shared workspace goes, for display. */
        val sharedPath: String,
        val supported: Boolean,
        val accessGranted: Boolean,
        val busy: Boolean = false,
        val progress: String? = null,
        val message: String? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _state = MutableStateFlow(
        snapshot(
            message = if (location.accessLostNotice) {
                location.accessLostNotice = false
                "All-files access was taken away, so the workspace went back to private storage. The files in " +
                    "${location.sharedRoot.path} were not touched; turn the switch on again to bring them back."
            } else null
        )
    )
    val state: StateFlow<State> = _state.asStateFlow()

    private fun snapshot(busy: Boolean = false, progress: String? = null, message: String? = null): State {
        val root = workspace.rootDir
        return State(
            shared = root.path == canonical(location.sharedRoot).path,
            path = root.path,
            sharedPath = location.sharedRoot.path,
            supported = location.supported,
            accessGranted = location.accessGranted(),
            busy = busy,
            progress = progress,
            message = message
        )
    }

    /** Re-reads the access grant, e.g. when Settings is shown again or the user comes back from Android's settings. */
    fun refresh() {
        val current = _state.value
        if (!current.busy) _state.value = snapshot(message = current.message)
    }

    fun showMessage(text: String) {
        val current = _state.value
        _state.value = current.copy(message = text, accessGranted = location.accessGranted())
    }

    /** Moves the workspace to the shared folder ([shared] = true) or back to private storage. */
    fun requestShared(shared: Boolean) {
        scope.launch { move(shared) }
    }

    private suspend fun move(shared: Boolean) = mutex.withLock {
        val from = workspace.rootDir
        val to = canonical(if (shared) location.sharedRoot else location.privateRoot)
        if (from.path == to.path) {
            location.sharedWanted = shared
            _state.value = snapshot()
            return@withLock
        }
        if (shared && !location.accessGranted()) {
            _state.value = snapshot(message = "The shared workspace needs all-files access. Nothing was moved.")
            return@withLock
        }

        _state.value = snapshot(busy = true, progress = "Copying files…")
        var switched = false
        try {
            if (!to.isDirectory && !to.mkdirs()) throw IOException("Could not create ${to.path}")
            if (shared) File(to, Workspace.NO_MEDIA).let { if (!it.exists()) it.createNewFile() }

            val first = WorkspaceMover.copyTree(from, to, WorkspaceMover.OnConflict.KEEP_EXISTING_ASIDE, Workspace.HIDDEN_NAMES) { rel ->
                _state.value = _state.value.copy(progress = "Copying $rel")
            }
            workspace.relocate(to)
            location.sharedWanted = shared
            switched = true

            _state.value = _state.value.copy(progress = "Cleaning up the old folder…")
            val late = WorkspaceMover.copyTree(from, to, WorkspaceMover.OnConflict.OVERWRITE, Workspace.HIDDEN_NAMES)
            val left = WorkspaceMover.deleteCopied(from, to, Workspace.HIDDEN_NAMES)

            val files = first.copied + first.unchanged + late.copied
            _state.value = snapshot(message = buildString {
                append("Workspace moved to ${to.path} ($files file${if (files == 1) "" else "s"}).")
                if (first.keptAside.isNotEmpty()) {
                    append(" ${first.keptAside.size} file(s) that were already there with the same name were kept as \"(before move)\": ")
                    append(first.keptAside.take(5).joinToString())
                    if (first.keptAside.size > 5) append(", …")
                    append('.')
                }
                if (left.isNotEmpty()) append(" ${left.size} item(s) could not be removed from ${from.path}.")
            })
        } catch (e: Exception) {
            val where = if (switched) {
                "The workspace now lives in ${to.path}, but the old folder ${from.path} was not cleaned up."
            } else {
                "Nothing was deleted; the workspace stays in ${from.path}."
            }
            _state.value = snapshot(message = "Moving the workspace failed: ${e.message ?: e.javaClass.simpleName}. $where")
        }
    }

    private fun canonical(f: File): File = try { f.canonicalFile } catch (_: IOException) { f.absoluteFile }
}
