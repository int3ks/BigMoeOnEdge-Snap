package com.bigmoe.onedge.di

import android.app.ActivityManager
import android.content.Context
import androidx.room.Room
import com.bigmoe.onedge.attachments.AttachmentManager
import com.bigmoe.onedge.core.AgentController
import com.bigmoe.onedge.core.EngineController
import com.bigmoe.onedge.core.EngineNativeBridge
import com.bigmoe.onedge.core.ModelManager
import com.bigmoe.onedge.core.NativeEngineBackend
import com.bigmoe.onedge.data.local.AppDatabase
import com.bigmoe.onedge.data.local.datastore.EngineSettingsDataStore
import com.bigmoe.onedge.data.local.datastore.ChatComposerDataStore
import com.bigmoe.onedge.data.repository.ArtifactRepository
import com.bigmoe.onedge.data.repository.ChatRepository
import com.bigmoe.onedge.tools.AndroidDeviceInfo
import com.bigmoe.onedge.tools.ToolManager
import com.bigmoe.onedge.tools.JavaScriptRunner
import com.bigmoe.onedge.tools.WebViewJavaScriptRunner
import com.bigmoe.onedge.tools.registerCodeTools
import com.bigmoe.onedge.tools.registerFileTools
import com.bigmoe.onedge.tools.registerUtilityTools
import com.bigmoe.onedge.tools.registerWebPageTools
import com.bigmoe.onedge.tools.WebSearchTool
import com.bigmoe.onedge.tools.ImageSearchTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.bigmoe.onedge.workspace.Workspace
import java.io.File

class AppContainer(private val context: Context) {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** True once when an old v1 database is detected before Room's intentional destructive upgrade. */
    private var roomUpgradeNoticePending: Boolean = detectRoomV1Upgrade()

    fun consumeRoomUpgradeNotice(): Boolean {
        if (!roomUpgradeNoticePending) return false
        roomUpgradeNoticePending = false
        return true
    }

    private fun detectRoomV1Upgrade(): Boolean {
        val dbFile = context.getDatabasePath("onedge_database.db")
        if (!dbFile.isFile) return false
        return runCatching {
            android.database.sqlite.SQLiteDatabase.openDatabase(
                dbFile.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            ).use { it.version == 1 }
        }.getOrDefault(false)
    }

    val database: AppDatabase by lazy {
        Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "onedge_database.db")
            // v1 -> v2 used a destructive development migration. v2 -> v3 is a real additive migration
            // for generation/model metadata so existing v2 chats remain intact.
            .addMigrations(
                com.bigmoe.onedge.data.local.MIGRATION_2_3,
                com.bigmoe.onedge.data.local.MIGRATION_3_4
            )
            .fallbackToDestructiveMigration()
            .build()
    }

    val settingsDataStore: EngineSettingsDataStore by lazy {
        EngineSettingsDataStore(context.applicationContext)
    }

    val chatComposerDataStore: ChatComposerDataStore by lazy {
        ChatComposerDataStore(context.applicationContext)
    }

    val chatRepository: ChatRepository by lazy {
        ChatRepository(
            sessionDao = database.chatSessionDao(),
            messageDao = database.chatMessageDao(),
            attachmentDao = database.attachmentDao()
        )
    }

    /** Loads libonedge-engine.so on first use (lazily, so a missing library cannot crash app start). */
    val engineController: EngineController by lazy {
        EngineController(
            backendProvider = { NativeEngineBackend(EngineNativeBridge()) },
            csvPathProvider = { newMetricsCsvPath() },
            loraProvider = { path -> modelManager.loraForModel(path) },
            kvCacheTypeProvider = { path -> modelManager.kvCacheTypeForModel(path) }
        )
    }

    val modelManager: ModelManager by lazy {
        ModelManager(
            File(context.applicationContext.filesDir, "models"),
            defaultModelFolder = File(android.os.Environment.getExternalStorageDirectory(), "Models")
        )
    }

    /** Private (`<app files>/workspace`) or shared (`<internal storage>/BigMoe/workspace`), see [workspaceSwitcher]. */
    val workspaceLocation: com.bigmoe.onedge.core.WorkspaceLocation by lazy {
        com.bigmoe.onedge.core.WorkspaceLocation(context.applicationContext)
    }

    /** The sandbox every file tool, attachment and artifact lives in; its root is picked once at start. */
    val workspace: Workspace by lazy { Workspace(workspaceLocation.startupRoot()) }

    /** Settings → Workspace: moves the files between private and shared storage and repoints [workspace]. */
    val workspaceSwitcher: com.bigmoe.onedge.core.WorkspaceSwitcher by lazy {
        com.bigmoe.onedge.core.WorkspaceSwitcher(workspace, workspaceLocation)
    }

    val artifactRepository: ArtifactRepository by lazy { ArtifactRepository(database.artifactDao()) }

    val attachmentManager: AttachmentManager by lazy { AttachmentManager(workspace) }

    /** The offline, no-network WebView sandbox behind the run_javascript tool and the viewer's Run button. */
    val javaScriptRunner: JavaScriptRunner by lazy { WebViewJavaScriptRunner(context.applicationContext) }

    /**
     * To add a tool: implement com.bigmoe.onedge.tools.Tool and register it here. The prompt, the call parsing,
     * the approval dialog and the "running" label need no other change (pick its category and, if it should be
     * switchable, add its name to the matching *_TOOL_NAMES set).
     */
    val toolManager: ToolManager by lazy {
        ToolManager().apply {
            registerTool(WebSearchTool())
            registerTool(ImageSearchTool())
            registerWebPageTools(this)
            registerFileTools(this, workspace, artifactRepository)
            registerCodeTools(this, workspace, javaScriptRunner)
            registerUtilityTools(this, AndroidDeviceInfo(context.applicationContext))
            registerTool(
                com.bigmoe.onedge.tools.RunShellTool(termuxBridge, workspace, {
                    workspace.rootDir.takeIf { workspaceLocation.isShared(it) }
                })
            )
        }
    }

    /** run_shell's way into the separately installed Termux app (RUN_COMMAND intent). */
    val termuxBridge: com.bigmoe.onedge.termux.TermuxBridge by lazy {
        com.bigmoe.onedge.termux.TermuxBridge(context.applicationContext)
    }

    val agentController: AgentController by lazy {
        AgentController(engineController = engineController, toolManager = toolManager)
    }

    val generationManager: com.bigmoe.onedge.core.GenerationManager by lazy {
        com.bigmoe.onedge.core.GenerationManager(context.applicationContext, chatRepository, agentController, engineController, toolManager)
    }

    /** `<external files>/metrics/session-<timestamp>.csv` (shared through FileProvider, see res/xml/file_paths.xml). */
    private fun newMetricsCsvPath(): String? {
        val base = context.applicationContext.getExternalFilesDir(null) ?: return null
        val dir = File(base, "metrics")
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        return File(dir, "session-$stamp.csv").absolutePath
    }

    fun totalRamBytes(): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0L
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem
    }

    val autoSaveStore: com.bigmoe.onedge.data.local.AutoSaveStore by lazy {
        com.bigmoe.onedge.data.local.AutoSaveStore(File(context.applicationContext.filesDir, "auto-saves"))
    }

    val deviceScanner: com.bigmoe.onedge.core.DeviceScanner by lazy {
        com.bigmoe.onedge.core.DeviceScanner(context.applicationContext)
    }

    val contentResolver: android.content.ContentResolver get() = context.applicationContext.contentResolver

    val apiServerController: com.bigmoe.onedge.server.ApiServerController by lazy {
        com.bigmoe.onedge.server.ApiServerController(context.applicationContext, settingsDataStore)
    }

    /** adb test hook, see [com.bigmoe.onedge.server.ApiServerIntentHook]. No-op unless the intent carries its extras. */
    fun handleApiServerIntent(intent: android.content.Intent?) {
        // MainActivity is exported, so any app could send these extras (set the key, switch authentication off...).
        // The hook therefore exists in debuggable builds only.
        val debuggable = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return
        if (intent?.hasExtra(com.bigmoe.onedge.server.ApiServerIntentHook.EXTRA_ACTION) != true) return
        appScope.launch { com.bigmoe.onedge.server.ApiServerIntentHook.handle(intent, apiServerController, settingsDataStore) }
    }

    /** The notification's Stop button: persist "off" in the app-wide scope (the service's own scope dies with the service). */
    fun disableApiServer() {
        appScope.launch { apiServerController.setEnabled(false) }
    }

    /** Called once from Application.onCreate: brings the local API server back if it was left enabled. */
    fun startApiServerIfEnabled() {
        appScope.launch { apiServerController.startIfEnabled() }
    }

    /** Called once from Application.onCreate: keeps the engine on the CPU cores chosen in Settings (or the default). */
    fun followCpuCoreSetting() {
        appScope.launch {
            var applied = 0L to 0L
            settingsDataStore.settingsFlow.collect { s ->
                val masks = com.bigmoe.onedge.core.CpuTopology.effective(s.cpuMask) to
                    com.bigmoe.onedge.core.CpuTopology.effective(s.cpuMaskPrefill)
                if (masks != applied) {
                    engineController.setCpuMasks(masks.first, masks.second)
                    applied = masks
                }
            }
        }
    }

    /** Called once from Application.onCreate: every model that finishes loading moves to the top of the model list. */
    fun trackModelUse() {
        appScope.launch {
            engineController.state.collect { s ->
                if (s is com.bigmoe.onedge.core.EngineState.Ready) runCatching { modelManager.markUsed(s.info.path) }
            }
        }
    }

    /** Called once from Application.onCreate: honours "load the last model on app start". */
    fun autoLoadLastModelIfEnabled() {
        appScope.launch {
            val s = settingsDataStore.settingsFlow.first()
            if (s.autoLoadLastModel && s.lastModelPath.isNotBlank() && File(s.lastModelPath).isFile) {
                // Before the load, so its threads start on those cores.
                engineController.setCpuMasks(
                    com.bigmoe.onedge.core.CpuTopology.effective(s.cpuMask),
                    com.bigmoe.onedge.core.CpuTopology.effective(s.cpuMaskPrefill)
                )
                val projector = modelManager.mmprojForModel(s.lastModelPath)
                    ?: s.lastMmprojPath.takeIf { it.isNotBlank() && File(it).isFile }
                val draftPath = if (s.spec == com.bigmoe.onedge.core.SpecSource.MTP)
                    modelManager.draftForModel(s.lastModelPath) else null
                engineController.loadModel(s.lastModelPath, s.toLoadConfig(), projector, draftPath)
            }
        }
    }
}
