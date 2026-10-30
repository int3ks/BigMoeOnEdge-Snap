package com.bigmoe.onedge.di

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.bigmoe.onedge.ui.chat.ChatViewModel
import com.bigmoe.onedge.ui.files.FileViewModel
import com.bigmoe.onedge.ui.history.HistoryViewModel
import com.bigmoe.onedge.ui.models.ModelViewModel
import com.bigmoe.onedge.ui.settings.AutoTuneViewModel
import com.bigmoe.onedge.ui.settings.SettingsViewModel

class AppViewModelFactory(
    private val container: AppContainer
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(ChatViewModel::class.java) -> ChatViewModel(
                repository = container.chatRepository,
                settingsDataStore = container.settingsDataStore,
                engineController = container.engineController,
                toolManager = container.toolManager,
                artifactRepository = container.artifactRepository,
                attachmentManager = container.attachmentManager,
                workspace = container.workspace,
                composerStore = container.chatComposerDataStore,
                generationManager = container.generationManager
            ) as T
            modelClass.isAssignableFrom(HistoryViewModel::class.java) ->
                HistoryViewModel(repository = container.chatRepository, composerStore = container.chatComposerDataStore, generationManager = container.generationManager) as T
            modelClass.isAssignableFrom(SettingsViewModel::class.java) -> SettingsViewModel(
                dataStore = container.settingsDataStore,
                engineController = container.engineController,
                modelManager = container.modelManager,
                apiServer = container.apiServerController,
                workspaceSwitcher = container.workspaceSwitcher,
                termuxStatus = { container.termuxBridge.isInstalled() to container.termuxBridge.hasPermission() },
                termuxTest = {
                    val tool = container.toolManager.getTool("run_shell")
                    if (tool == null) "run_shell is not registered." else try {
                        val out = tool.execute(
                            org.json.JSONObject()
                                .put("command", "echo \"Termux answers from: \$(pwd)\"; uname -m; command -v python >/dev/null && python --version || echo 'Python is not installed yet (the agent can run: pkg install -y python)'")
                                .put("timeout_seconds", 30)
                        )
                        // Also refreshes what the agent is told about Termux (system prompt).
                        val root = container.workspace.rootDir.takeIf { container.workspaceLocation.isShared(it) }
                        val env = if (root != null) (tool as? com.bigmoe.onedge.tools.RunShellTool)?.refreshEnvironment(root, force = true) else null
                        if (env != null) "$out\n\nThe agent is told: $env" else out
                    } catch (e: com.bigmoe.onedge.tools.ToolInputException) {
                        "Error: ${e.message}"
                    }
                }
            ) as T
            modelClass.isAssignableFrom(AutoTuneViewModel::class.java) -> AutoTuneViewModel(
                dataStore = container.settingsDataStore,
                engineController = container.engineController,
                modelManager = container.modelManager,
                store = container.autoSaveStore,
                scanner = container.deviceScanner,
                contentResolver = container.contentResolver
            ) as T
            modelClass.isAssignableFrom(ModelViewModel::class.java) -> ModelViewModel(
                modelManager = container.modelManager,
                engineController = container.engineController,
                settingsDataStore = container.settingsDataStore,
                totalRamBytes = { container.totalRamBytes() }
            ) as T
            modelClass.isAssignableFrom(FileViewModel::class.java) -> FileViewModel(
                workspace = container.workspace,
                artifactRepository = container.artifactRepository,
                javaScriptRunner = container.javaScriptRunner
            ) as T
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
