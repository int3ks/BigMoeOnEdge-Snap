package com.bigmoe.onedge.ui.models

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bigmoe.onedge.core.EngineController
import com.bigmoe.onedge.core.EngineState
import com.bigmoe.onedge.core.LoadAdvice
import com.bigmoe.onedge.core.ModelFile
import com.bigmoe.onedge.core.ModelManager
import com.bigmoe.onedge.data.local.datastore.EngineSettings
import com.bigmoe.onedge.data.local.datastore.EngineSettingsDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File

data class ImportProgress(val name: String, val copiedBytes: Long, val totalBytes: Long, val bytesPerSecond: Long = 0)

/** A model to move into the archive, waiting for the source to be picked from [choices] (the last used first). */
data class PendingArchiveMove(
    val set: com.bigmoe.onedge.core.GgufSet,
    val choices: List<com.bigmoe.onedge.core.ArchiveSource>,
    val loadedPath: String?
)

/** A load the person still has to confirm because the pre-flight checks found something ([advice]). */
data class PendingLoad(
    val path: String,
    val advice: LoadAdvice,
    /** The vision projector attached to the model, loaded with it; null = text only. */
    val selectedProjector: String? = null
)

data class ModelsUiState(
    val models: List<ModelFile> = emptyList(),
    val drafts: List<ModelFile> = emptyList(),
    val incompleteImports: List<com.bigmoe.onedge.core.IncompleteModelImport> = emptyList(),
    val modelDrafts: Map<String, String> = emptyMap(),
    /** LoRA adapters in app storage, and the one attached to each model (with its strength). */
    val loras: List<ModelFile> = emptyList(),
    val modelLoras: Map<String, com.bigmoe.onedge.core.LoraChoice> = emptyMap(),
    /** The vision projector (mmproj) attached to each model. */
    val modelMmprojs: Map<String, String> = emptyMap(),
    /** NPU / expert cache set on a model's card; a model without an entry uses the global settings. */
    val modelMemoryOptions: Map<String, com.bigmoe.onedge.core.ModelMemoryOptions> = emptyMap(),
    /** Memory estimates from the GGUF tensor directory, filled when a card asks for one. */
    val modelEstimates: Map<String, com.bigmoe.onedge.core.MemoryEstimate> = emptyMap(),
    /** KV cache type per model; models not in the map use F16. */
    val modelKvTypes: Map<String, com.bigmoe.onedge.core.KvCacheType> = emptyMap(),
    /** Guess ahead set on a model's card; a model without an entry uses the default mode (Settings → Guess ahead). */
    val modelSpecs: Map<String, com.bigmoe.onedge.core.SpecSource> = emptyMap(),
    /** Decoding preset set on a model's card; a model without an entry uses Settings → Decoding. */
    val modelSamplingPresets: Map<String, com.bigmoe.onedge.core.SamplingPreset> = emptyMap(),
    val existingPathInput: String = "",
    val importProgress: ImportProgress? = null,
    val pendingLoad: PendingLoad? = null,
    val message: String? = null,
    val modelsDir: String = "",
    /** The folder whose GGUFs are listed automatically (null = off), and how many it holds. */
    val modelFolder: String? = null,
    val modelFolderFiles: Int = 0,
    val modelFolderReadable: Boolean = false,
    /** The archive ([com.bigmoe.onedge.core.ModelArchive]): its sources, what they hold, folders that could be added. */
    val archiveSources: List<com.bigmoe.onedge.core.SourceStatus> = emptyList(),
    val archiveEntries: List<com.bigmoe.onedge.core.ArchiveEntry> = emptyList(),
    val archiveSuggestions: List<com.bigmoe.onedge.core.SourceSuggestion> = emptyList(),
    /** What uses the app's storage; null until "Show" was pressed (it walks every folder). */
    val appStorage: List<com.bigmoe.onedge.core.StorageItem>? = null,
    val appStorageScanning: Boolean = false,
    /** A move into the archive waiting for the person to pick the source (more than one is connected). */
    val pendingArchiveMove: PendingArchiveMove? = null,
    /** Free space where model files go (app storage, model folder, archive when connected). */
    val storageSpace: List<com.bigmoe.onedge.core.StorageSpace> = emptyList(),
    /** Where "Fetch" copies to: the app's storage or the model folder. */
    /** GGUFs waiting in Downloads. */
    val downloads: List<com.bigmoe.onedge.core.GgufSet> = emptyList(),
    /** Clearing the copy at this path needs a second yes: the archive copy could not be checked ([second] says why). */
    val pendingClearCache: Pair<String, String>? = null
)

class ModelViewModel(
    private val modelManager: ModelManager,
    private val engineController: EngineController,
    private val settingsDataStore: EngineSettingsDataStore,
    private val totalRamBytes: () -> Long,
    private val modelArchive: com.bigmoe.onedge.core.ModelArchive? = null,
    private val storageReport: com.bigmoe.onedge.core.AppStorageReport? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(ModelsUiState(modelsDir = modelManager.directoryPath()))
    val uiState: StateFlow<ModelsUiState> = _uiState.asStateFlow()

    val engineState: StateFlow<EngineState> = engineController.state

    val settings: StateFlow<EngineSettings> = settingsDataStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), EngineSettings())

    private var importJob: Job? = null

    init {
        refresh()
        migrateAppStorage()
    }

    /**
     * Models are kept in the model folder only (7.17). Moves what an older version left in the app's storage
     * there once, with the import progress bar; the loaded model is unloaded first when it is one of them.
     */
    private fun migrateAppStorage() {
        viewModelScope.launch {
            val files = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { modelManager.appStorageModels() } }
                .getOrDefault(emptyList())
            if (files.isEmpty() || modelManager.modelFolder() == null) return@launch
            val paths = files.map { it.canonicalPath }.toSet()
            if (engineModelPath()?.let { runCatching { java.io.File(it).canonicalPath }.getOrDefault(it) } in paths) engineController.unload()
            _uiState.update { it.copy(importProgress = ImportProgress("Moving models out of the app", 0, 0)) }
            try {
                val left = modelManager.migrateAppStorage { done, total, name ->
                    _uiState.update { it.copy(importProgress = ImportProgress("Moving $name to the model folder", done, total)) }
                }
                _uiState.update {
                    it.copy(
                        importProgress = null,
                        message = if (left.isEmpty()) "Models moved from the app storage into the model folder."
                        else "Moved into the model folder, except (a different file of the same name is there): " + left.joinToString()
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(importProgress = null, message = "Could not move the models out of the app storage: ${e.message}") }
            }
            refresh()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            // Reading incomplete groups first also performs recovery of an interrupted shard promotion.
            val incomplete = modelManager.listIncompleteImports()
            val list = modelManager.listModels()
            val drafts = modelManager.listDrafts()
            val links = list.mapNotNull { model -> modelManager.draftForModel(model.path)?.let { model.path to it } }.toMap()
            val loras = modelManager.listLoras()
            val loraLinks = list.mapNotNull { model -> modelManager.loraForModel(model.path)?.let { model.path to it } }.toMap()
            val mmprojLinks = list.mapNotNull { model -> modelManager.mmprojForModel(model.path)?.let { model.path to it } }.toMap()
            val kvTypes = list.associate { model -> model.path to modelManager.kvCacheTypeForModel(model.path) }
            val specs = list.mapNotNull { model -> modelManager.specForModel(model.path)?.let { model.path to it } }.toMap()
            val memoryOptions = list.associate { model -> model.path to modelManager.memoryOptionsForModel(model.path) }
            val samplingPresets = list.mapNotNull { model -> modelManager.samplingPresetForModel(model.path)?.let { model.path to it } }.toMap()
            val folder = modelManager.modelFolder()
            val folderReadable = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { folder?.let { it.isDirectory && it.canRead() } == true }
            val folderFiles = if (folderReadable) modelManager.modelFolderFileCount() else 0
            val archive = modelArchive
            val archiveEntries = archive?.list().orEmpty()
            val archiveSources = archive?.sourceStatus().orEmpty()
            val archiveSuggestions = archive?.suggestions().orEmpty()
            val downloads = archive?.listDownloads().orEmpty()
            val storageSpace = archive?.storageSpace().orEmpty()
            _uiState.update {
                it.copy(
                    models = list, drafts = drafts, incompleteImports = incomplete, modelDrafts = links, loras = loras,
                    modelLoras = loraLinks, modelMmprojs = mmprojLinks, modelKvTypes = kvTypes, modelSpecs = specs, modelSamplingPresets = samplingPresets, modelMemoryOptions = memoryOptions,
                    modelFolder = folder?.path, modelFolderFiles = folderFiles, modelFolderReadable = folderReadable,
                    archiveSources = archiveSources, archiveEntries = archiveEntries,
                    archiveSuggestions = archiveSuggestions, downloads = downloads,
                    storageSpace = storageSpace
                )
            }
        }
    }

    fun importModel(resolver: ContentResolver, uri: Uri) = importModels(resolver, listOf(uri))

    fun importModels(resolver: ContentResolver, uris: List<Uri>, targetImportId: String? = null) {
        if (importJob?.isActive == true || uris.isEmpty()) return
        importJob = viewModelScope.launch {
            _uiState.update { it.copy(message = null, importProgress = ImportProgress("…", 0, 0)) }
            try {
                val result = modelManager.importModelsIncrementally(resolver, uris, targetImportId) { copied, total ->
                    _uiState.update { s -> s.copy(importProgress = ImportProgress(s.importProgress?.name ?: "…", copied, total)) }
                }
                // A file picked from Downloads is moved, not copied: once the whole import is in the model folder
                // the original goes (where the provider lets us; otherwise it stays and nothing is lost).
                val movedOut = if (result.importedModels.isNotEmpty() && result.incompleteImport == null)
                    uris.count { deleteIfInDownloads(resolver, it) } else 0
                _uiState.update { it.copy(message = result.message + if (movedOut > 0) " Removed from Downloads." else "") }
                refresh()
            } catch (e: CancellationException) {
                _uiState.update { it.copy(message = "Import cancelled") }
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(message = e.message ?: "Import failed") }
                refresh()
            } finally {
                _uiState.update { it.copy(importProgress = null) }
            }
        }
    }

    private fun deleteIfInDownloads(resolver: ContentResolver, uri: Uri): Boolean = runCatching {
        val id = android.provider.DocumentsContract.getDocumentId(uri)
        val inDownloads = uri.authority == "com.android.providers.downloads.documents" ||
            (uri.authority == "com.android.externalstorage.documents" && id.substringAfter(':').startsWith("Download/"))
        inDownloads && android.provider.DocumentsContract.deleteDocument(resolver, uri)
    }.getOrDefault(false)

    fun removeIncompleteImport(id: String) {
        viewModelScope.launch {
            val removed = modelManager.removeIncompleteImport(id)
            _uiState.update { it.copy(message = if (removed) "Removed the incomplete import and its app-owned partial copies." else "Could not remove this incomplete import.") }
            refresh()
        }
    }

    fun setExistingPathInput(value: String) { _uiState.update { it.copy(existingPathInput = value) } }

    fun showMessage(message: String) { _uiState.update { it.copy(message = message) } }

    fun registerExistingPath() {
        val path = _uiState.value.existingPathInput.trim()
        if (path.isEmpty()) {
            _uiState.update { it.copy(message = "Enter an absolute file path first.") }
            return
        }
        viewModelScope.launch {
            try {
                val file = modelManager.registerExistingPath(path)
                _uiState.update { it.copy(message = "Registered without copying: ${file.name}", existingPathInput = "") }
                refresh()
            } catch (e: Exception) {
                _uiState.update { it.copy(message = e.message ?: "Could not register the path") }
            }
        }
    }

    fun attachDraft(modelPath: String, draftPath: String?) {
        viewModelScope.launch {
            try {
                modelManager.attachDraft(modelPath, draftPath)
                _uiState.update { it.copy(message = if (draftPath == null) "Detached the MTP draft." else "Attached ${File(draftPath).name} as the separate MTP draft.") }
                refresh()
            } catch (e: Exception) {
                _uiState.update { it.copy(message = e.message ?: "Could not attach the draft") }
            }
        }
    }

    /** Attaches a vision projector (mmproj) to a model (null detaches). Loaded together with the model from then on. */
    fun attachMmproj(modelPath: String, mmprojPath: String?) {
        viewModelScope.launch {
            try {
                modelManager.attachMmproj(modelPath, mmprojPath)
                _uiState.update {
                    it.copy(message = if (mmprojPath == null) "Detached the vision projector." else "Attached ${File(mmprojPath).name}. It loads together with this model.")
                }
                refresh()
            } catch (e: Exception) {
                _uiState.update { it.copy(message = e.message ?: "Could not attach the vision projector") }
            }
        }
    }

    /** Import an mmproj GGUF (its name must contain "mmproj") into app storage and attach it to [modelPath]. */
    fun importAndAttachMmproj(resolver: ContentResolver, uri: Uri, modelPath: String) {
        if (importJob?.isActive == true) {
            _uiState.update { it.copy(message = "Wait for the current import to finish before adding a vision projector.") }
            return
        }
        importJob = viewModelScope.launch {
            _uiState.update { it.copy(message = null, importProgress = ImportProgress("Vision projector", 0, 0)) }
            try {
                val projector = modelManager.importModel(resolver, uri) { copied, total ->
                    _uiState.update { it.copy(importProgress = ImportProgress("Vision projector", copied, total)) }
                }
                if (!ModelManager.isProjectorName(projector.name)) {
                    modelManager.delete(projector.path)
                    _uiState.update { it.copy(message = "${projector.name} does not look like a vision projector: its file name must contain \"mmproj\".") }
                } else {
                    modelManager.attachMmproj(modelPath, projector.path)
                    _uiState.update { it.copy(message = "Imported and attached ${projector.name}. It loads together with this model.") }
                }
                refresh()
            } catch (e: CancellationException) {
                _uiState.update { it.copy(message = "Import cancelled.") }
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(message = e.message ?: "Could not import the vision projector.") }
                refresh()
            } finally {
                _uiState.update { it.copy(importProgress = null) }
            }
        }
    }

    /** Attaches a LoRA adapter to a model (null detaches). Applied the next time that model loads. */
    fun attachLora(modelPath: String, loraPath: String?) {
        viewModelScope.launch {
            try {
                modelManager.attachLora(modelPath, loraPath)
                _uiState.update {
                    it.copy(message = if (loraPath == null) "Detached the LoRA adapter." else "Attached ${File(loraPath).name}. It applies the next time this model loads.")
                }
                refresh()
            } catch (e: Exception) {
                _uiState.update { it.copy(message = e.message ?: "Could not attach the LoRA adapter") }
            }
        }
    }

    fun setSamplingPresetForModel(modelPath: String, preset: com.bigmoe.onedge.core.SamplingPreset?) {
        viewModelScope.launch {
            modelManager.setSamplingPresetForModel(modelPath, preset)
            refresh()
        }
    }

    fun setSpecForModel(modelPath: String, spec: com.bigmoe.onedge.core.SpecSource?) {
        viewModelScope.launch {
            modelManager.setSpecForModel(modelPath, spec)
            refresh()
        }
    }

    val deviceRamBytes: Long get() = totalRamBytes()

    /** Whether this build and device have NPU prefill at all (the per-model NPU switch is only shown then). */
    val npuAvailable: Boolean get() = engineController.capabilities.npuPrefill

    fun setNpuForModel(modelPath: String, npu: Boolean?) {
        viewModelScope.launch {
            modelManager.setNpuForModel(modelPath, npu)
            refresh()
        }
    }

    fun setDiscardPctForModel(modelPath: String, pct: Int?) {
        viewModelScope.launch {
            modelManager.setDiscardPctForModel(modelPath, pct)
            refresh()
        }
    }

    fun setCacheMbForModel(modelPath: String, cacheMb: Int?) {
        viewModelScope.launch {
            modelManager.setCacheMbForModel(modelPath, cacheMb)
            refresh()
        }
    }

    /** Reads the model's tensor directory once (headers only) for the memory overview of its card. */
    fun ensureEstimate(modelPath: String) {
        if (_uiState.value.modelEstimates.containsKey(modelPath)) return
        viewModelScope.launch {
            val estimate = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { com.bigmoe.onedge.core.ModelMemoryEstimator.estimate(File(modelPath), totalRamBytes()) }.getOrNull()
            } ?: return@launch
            _uiState.update { it.copy(modelEstimates = it.modelEstimates + (modelPath to estimate)) }
        }
    }

    fun setKvCacheType(modelPath: String, type: com.bigmoe.onedge.core.KvCacheType) {
        viewModelScope.launch {
            modelManager.setKvCacheType(modelPath, type)
            refresh()
        }
    }

    fun setLoraScale(modelPath: String, scale: Float) {
        viewModelScope.launch {
            modelManager.setLoraScale(modelPath, scale)
            refresh()
        }
    }

    /** Copy a LoRA adapter GGUF into private storage, check its header, then attach it to [modelPath]. */
    fun importAndAttachLora(resolver: ContentResolver, uri: Uri, modelPath: String) {
        if (importJob?.isActive == true) {
            _uiState.update { it.copy(message = "Wait for the current import to finish before adding a LoRA adapter.") }
            return
        }
        importJob = viewModelScope.launch {
            _uiState.update { it.copy(message = null, importProgress = ImportProgress("LoRA adapter", 0, 0)) }
            try {
                val lora = modelManager.importAndAttachLora(resolver, uri, modelPath) { copied, total ->
                    _uiState.update { it.copy(importProgress = ImportProgress("LoRA adapter", copied, total)) }
                }
                _uiState.update { it.copy(message = "Imported and attached ${lora.name}. It applies the next time this model loads.") }
                refresh()
            } catch (e: CancellationException) {
                _uiState.update { it.copy(message = "LoRA import cancelled.") }
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(message = e.message ?: "Could not import the LoRA adapter.") }
                refresh()
            } finally {
                _uiState.update { it.copy(importProgress = null) }
            }
        }
    }

    /** Copy a separately selected MTP GGUF into private storage, validate it, then attach it to the chosen target model. */
    fun importAndAttachDraft(resolver: ContentResolver, uri: Uri, modelPath: String) {
        if (importJob?.isActive == true) {
            _uiState.update { it.copy(message = "Wait for the current import to finish before attaching an MTP draft.") }
            return
        }
        importJob = viewModelScope.launch {
            _uiState.update { it.copy(message = null, importProgress = ImportProgress("MTP draft", 0, 0)) }
            try {
                val draft = modelManager.importAndAttachDraft(resolver, uri, modelPath) { copied, total ->
                    _uiState.update { it.copy(importProgress = ImportProgress("MTP draft", copied, total)) }
                }
                _uiState.update { it.copy(message = "Imported and attached ${draft.name} as the separate MTP draft.") }
                refresh()
            } catch (e: CancellationException) {
                _uiState.update { it.copy(message = "MTP draft import cancelled.") }
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(message = e.message ?: "Could not import the MTP draft.") }
                refresh()
            } finally {
                _uiState.update { it.copy(importProgress = null) }
            }
        }
    }

    fun cancelImport() {
        importJob?.cancel()
    }

    // ---- External archive, cache and Downloads (ModelArchive) ---------------------------------------------

    /** One file job at a time, shown with the same progress bar and Cancel as an import. */
    private fun archiveJob(name: String, done: String, block: suspend (onProgress: (Long, Long) -> Unit) -> Unit) {
        if (importJob?.isActive == true) {
            _uiState.update { it.copy(message = "Another copy is still running.") }
            return
        }
        importJob = viewModelScope.launch {
            _uiState.update { it.copy(message = null, importProgress = ImportProgress(name, 0, 0)) }
            // Speed: the current rate over the last second for the progress line, the average for the closing message.
            // What was already there when the job began (a continued fetch) does not count as copied.
            val startedAt = System.nanoTime()
            var first = -1L
            var last = 0L
            var sampleAt = startedAt
            var sampleBytes = 0L
            var rate = 0L
            try {
                block { copied, total ->
                    val now = System.nanoTime()
                    if (first < 0) {
                        first = copied
                        sampleAt = now
                        sampleBytes = copied
                    } else if (now - sampleAt >= 1_000_000_000L) {
                        rate = ((copied - sampleBytes).toDouble() * 1e9 / (now - sampleAt)).toLong()
                        sampleAt = now
                        sampleBytes = copied
                    }
                    last = copied
                    _uiState.update { s -> s.copy(importProgress = ImportProgress(name, copied, total, rate)) }
                }
                val seconds = (System.nanoTime() - startedAt) / 1e9
                val moved = if (first < 0) 0L else last - first
                val stats = if (moved > 0 && seconds >= 1.0) {
                    " ${ModelManager.formatBytes(moved)} in ${(seconds / 60).toInt()}:${(seconds % 60).toInt().toString().padStart(2, '0')} min" +
                        " (${ModelManager.formatBytes((moved / seconds).toLong())}/s)."
                } else ""
                _uiState.update { it.copy(message = done + stats) }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(message = "Cancelled. What was copied so far is kept and continued the next time.") }
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(message = e.message ?: "The copy failed") }
            } finally {
                _uiState.update { it.copy(importProgress = null) }
                refresh()
            }
        }
    }

    fun addArchiveSource(name: String, path: String) {
        viewModelScope.launch {
            try {
                modelArchive?.addSource(name, path)
            } catch (e: Exception) {
                _uiState.update { it.copy(message = e.message ?: "Could not add the folder") }
            }
            refresh()
        }
    }

    /** Forgets a source; its files stay on the drive. */
    fun removeArchiveSource(path: String) {
        viewModelScope.launch { modelArchive?.removeSource(path); refresh() }
    }

    /** Archive -> a local copy in [into] (the app's storage = cache, or the model folder = kept), where it can be loaded. */
    fun fetchFromArchive(name: String, into: com.bigmoe.onedge.core.FetchTarget) {
        val archive = modelArchive ?: return
        val done = "Copied to the model folder."
        archiveJob(com.bigmoe.onedge.core.ModelArchive.displayName(name), done) { onProgress -> archive.fetch(name, into, onProgress) }
    }

    /** Uses an archive model where it lies, without a copy: it becomes a card like any file registered in place. */
    fun useArchiveModelInPlace(name: String) {
        val archive = modelArchive ?: return
        viewModelScope.launch {
            try {
                archive.useInPlace(name)
                _uiState.update { it.copy(message = "Added to the models below; it is read from the archive source.") }
            } catch (e: Exception) {
                _uiState.update { it.copy(message = e.message ?: "Could not use the file in place") }
            }
            refresh()
        }
    }

    /** Deletes the local copy at [path] of an archive model (only that file); what is set on its card stays. */
    fun clearCopy(path: String, force: Boolean = false) {
        val archive = modelArchive ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(pendingClearCache = null) }
            try {
                if (engineModelPath() == path) engineController.unload()
                archive.clearCopy(path, force)
                _uiState.update { it.copy(message = "Copy removed. The settings of this model are kept.") }
            } catch (e: com.bigmoe.onedge.core.ModelArchive.ArchiveNotVerified) {
                _uiState.update { it.copy(pendingClearCache = path to (e.message ?: "The archive copy could not be checked.")) }
            } catch (e: Exception) {
                _uiState.update { it.copy(message = e.message ?: "Could not remove the copy") }
            }
            refresh()
        }
    }

    fun dismissClearCache() { _uiState.update { it.copy(pendingClearCache = null) } }

    fun moveDownloadToModels(set: com.bigmoe.onedge.core.GgufSet) {
        val archive = modelArchive ?: return
        archiveJob(set.displayName, "Moved to the model folder.") { onProgress -> archive.moveToModelFolder(set, onProgress) }
    }

    fun moveDownloadToArchive(set: com.bigmoe.onedge.core.GgufSet) = chooseArchiveTarget(set, loadedPath = null)

    /** A model of the model folder goes to the archive (it leaves the list; fetch it from there to use it again). */
    fun moveModelToArchive(path: String) {
        val archive = modelArchive ?: return
        chooseArchiveTarget(archive.setOf(File(path)), loadedPath = path)
    }

    /** One connected source: move there. Several: ask which (the last used first). None: say so. */
    private fun chooseArchiveTarget(set: com.bigmoe.onedge.core.GgufSet, loadedPath: String?) {
        val archive = modelArchive ?: return
        viewModelScope.launch {
            val targets = archive.writableSources()
            when {
                targets.isEmpty() -> _uiState.update { it.copy(message = "No archive source is connected (or none can be written).") }
                targets.size == 1 -> moveToArchive(set, targets.first(), loadedPath)
                else -> _uiState.update { it.copy(pendingArchiveMove = PendingArchiveMove(set, targets, loadedPath)) }
            }
        }
    }

    fun confirmArchiveMove(source: com.bigmoe.onedge.core.ArchiveSource) {
        val pending = _uiState.value.pendingArchiveMove ?: return
        _uiState.update { it.copy(pendingArchiveMove = null) }
        moveToArchive(pending.set, source, pending.loadedPath)
    }

    /** Walks the app's storage and lists what uses it (Models page, bottom). */
    fun scanAppStorage() {
        val report = storageReport ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(appStorageScanning = true) }
            val items = runCatching { report.scan() }.getOrDefault(emptyList())
            _uiState.update { it.copy(appStorage = items, appStorageScanning = false) }
        }
    }

    fun deleteStorageItem(item: com.bigmoe.onedge.core.StorageItem) {
        val report = storageReport ?: return
        viewModelScope.launch {
            runCatching { report.delete(item) }
                .onFailure { e -> _uiState.update { it.copy(message = e.message ?: "Could not delete") } }
            refresh()
            scanAppStorage()
        }
    }

    fun dismissArchiveMove() { _uiState.update { it.copy(pendingArchiveMove = null) } }

    private fun moveToArchive(set: com.bigmoe.onedge.core.GgufSet, source: com.bigmoe.onedge.core.ArchiveSource, loadedPath: String?) {
        val archive = modelArchive ?: return
        archiveJob(set.displayName, "Moved to ${source.name}.") { onProgress ->
            if (loadedPath != null && engineModelPath() == loadedPath) engineController.unload()
            archive.moveToArchive(set, source.path, onProgress)
        }
    }

    private fun engineModelPath(): String? = when (val s = engineController.state.value) {
        is EngineState.Ready -> s.info.path
        is EngineState.Loading -> s.path
        else -> null
    }

    /** Step 1: run the pre-flight checks and ask for confirmation. */
    fun requestLoad(path: String) {
        if (ModelManager.isProjectorName(path)) {
            _uiState.update { it.copy(message = "A vision projector is loaded together with its text model; choose the text model to load it.") }
            return
        }
        viewModelScope.launch {
            val s = settingsDataStore.settingsFlow.first()
            // Split validation inspects only GGUF headers/tensor descriptors, but that still involves
            // file I/O; do not freeze Compose while checking a multi-shard model.
            val advice = withContext(Dispatchers.IO) {
                ModelManager.adviseLoad(File(path), totalRamBytes(), s.toLoadConfig())
            }
            // The projector attached to this model (Models page). A model loaded with one before attachments existed
            // keeps it: that choice becomes its attachment, once.
            var projector = modelManager.mmprojForModel(path)
            if (projector == null && s.lastModelPath == path && s.lastMmprojPath.isNotBlank() && File(s.lastMmprojPath).isFile) {
                projector = s.lastMmprojPath
                runCatching { modelManager.attachMmproj(path, projector) }
                refresh()
            }
            // Nothing to ask when the checks found nothing: load right away. Warnings or errors still show first.
            if (advice.canLoad && advice.errors.isEmpty() && advice.warnings.isEmpty()) {
                _uiState.update { it.copy(message = null) }
                loadNow(path, projector)
            } else {
                _uiState.update { it.copy(pendingLoad = PendingLoad(path, advice, projector), message = null) }
            }
        }
    }

    fun dismissPendingLoad() {
        _uiState.update { it.copy(pendingLoad = null) }
    }

    /** Step 2: load. The engine state (Loading / Ready / Failed) is observed through [engineState]. */
    fun confirmLoad() {
        val pending = _uiState.value.pendingLoad ?: return
        if (!pending.advice.canLoad) {
            dismissPendingLoad()
            return
        }
        _uiState.update { it.copy(pendingLoad = null) }
        loadNow(pending.path, pending.selectedProjector)
    }

    /** Reload the current model with the current settings (used after load-time settings changed). */
    /** Reload with the current settings AND the model's current attachments (projector, drafter, LoRA, KV cache). */
    fun reloadCurrent() {
        val ready = engineState.value as? EngineState.Ready ?: return
        viewModelScope.launch { loadNow(ready.info.path, modelManager.mmprojForModel(ready.info.path)) }
    }

    private fun loadNow(path: String, mmprojPath: String?, currentDraftPath: String? = null) {
        viewModelScope.launch {
            val s = settingsDataStore.settingsFlow.first()
            // Passed whenever attached: the engine controller uses it only when the mode for this model is MTP.
            val draftPath = currentDraftPath?.takeIf { File(it).isFile } ?: modelManager.draftForModel(path)
            val error = engineController.loadModel(path, s.toLoadConfig(), mmprojPath, draftPath)
            if (error == null) {
                settingsDataStore.updateLastModelPath(path)
                settingsDataStore.updateLastMmprojPath(mmprojPath.orEmpty())
                val warning = (engineState.value as? EngineState.Ready)?.info?.loadWarning
                _uiState.update { it.copy(message = warning) }
            } else if (error == com.bigmoe.onedge.core.EngineController.LOAD_CANCELLED) {
                _uiState.update { it.copy(message = "Load cancelled.") }
            } else if (mmprojPath != null) {
                // The projector may be the problem (wrong model, damaged file): try again as a text-only model.
                val textOnly = engineController.loadModel(path, s.toLoadConfig(), null, draftPath)
                if (textOnly == null) {
                    settingsDataStore.updateLastModelPath(path)
                    settingsDataStore.updateLastMmprojPath("")
                    val warning = (engineState.value as? EngineState.Ready)?.info?.loadWarning
                    _uiState.update { it.copy(message = "Loaded WITHOUT vision: the projector ${File(mmprojPath).name} failed ($error)." + (warning?.let { " $it" } ?: "")) }
                } else {
                    _uiState.update { it.copy(message = "$textOnly (with the projector: $error)") }
                }
            } else {
                _uiState.update { it.copy(message = error) }
            }
        }
    }

    /** Server model (OpenAI-compatible API): no memory advice, no projector. */
    fun loadRemote(path: String) {
        _uiState.update { it.copy(message = null) }
        loadNow(path, null)
    }

    fun unload() {
        viewModelScope.launch { engineController.unload() }
    }

    /** Stops the running model load; nothing is loaded afterwards. */
    fun cancelLoad() = engineController.cancelLoad()

    /** Models page → Model folder: a new folder (null/blank = off). */
    fun setModelFolder(path: String?) {
        viewModelScope.launch { modelManager.setModelFolder(path); refresh() }
    }

    fun resetModelFolder() {
        viewModelScope.launch { modelManager.resetModelFolder(); refresh() }
    }

    fun delete(path: String) {
        viewModelScope.launch {
            // A file listed from the model folder is the user's own file: never deleted from here.
            if (modelManager.isInModelFolderOnly(path)) {
                _uiState.update {
                    it.copy(message = "This file is in your model folder (${java.io.File(path).parent}). Delete or move it there to remove it from the list.")
                }
                return@launch
            }
            val loaded = (engineController.state.value as? EngineState.Ready)?.info?.path
            if (loaded == path) engineController.unload()
            val ok = modelManager.delete(path)
            _uiState.update { it.copy(message = if (ok) "Deleted" else "Could not delete the file") }
            refresh()
        }
    }

    fun rename(path: String, name: String) {
        viewModelScope.launch {
            try {
                val renamed = modelManager.rename(path, name)
                _uiState.update { it.copy(message = "Renamed to ${renamed.name}") }
                refresh()
            } catch (e: Exception) {
                _uiState.update { it.copy(message = e.message ?: "Rename failed") }
            }
        }
    }

    // MTP / "Guess ahead" settings. They moved here from Settings; same data-store calls, same limits.
    fun updateSpec(v: com.bigmoe.onedge.core.SpecSource) { viewModelScope.launch { settingsDataStore.updateSpec(v) } }
    fun updateDraftMax(v: Int) { viewModelScope.launch { settingsDataStore.updateDraftMax(v) } }
    fun updateMtpPMinPct(v: Int) { viewModelScope.launch { settingsDataStore.updateMtpPMinPct(v) } }
    fun updateSeparateMtpDraftEnabled(v: Boolean) { viewModelScope.launch { settingsDataStore.updateSeparateMtpDraftEnabled(v) } }
    fun updateNgramMinMatch(v: Int) { viewModelScope.launch { settingsDataStore.updateNgramMinMatch(v) } }
    fun updateNgramMaxMatch(v: Int) { viewModelScope.launch { settingsDataStore.updateNgramMaxMatch(v) } }

    fun setAutoLoad(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.updateAutoLoadLastModel(enabled) }
    }

    fun dismissMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
