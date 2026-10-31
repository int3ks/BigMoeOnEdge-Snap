package com.bigmoe.onedge.ui.models

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import java.io.File
import androidx.compose.ui.unit.dp
import com.bigmoe.onedge.core.EngineState
import com.bigmoe.onedge.core.ModelFile
import com.bigmoe.onedge.core.ModelManager
import com.bigmoe.onedge.data.local.datastore.EngineSettings
import com.bigmoe.onedge.data.local.datastore.loadConfigDiffers

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelScreen(
    viewModel: ModelViewModel,
    modifier: Modifier = Modifier
) {
    val ui by viewModel.uiState.collectAsState()
    val engine by viewModel.engineState.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val context = LocalContext.current
    var sharedPathAccessGranted by remember { mutableStateOf(hasSharedPathAccess(context)) }
    var showAdvancedPath by remember { mutableStateOf(false) }
    var targetIncompleteImportId by remember { mutableStateOf<String?>(null) }
    var targetMtpModelPath by remember { mutableStateOf<String?>(null) }
    var targetLoraModelPath by remember { mutableStateOf<String?>(null) }
    var targetMmprojModelPath by remember { mutableStateOf<String?>(null) }

    // MANAGE_EXTERNAL_STORAGE is a special Settings grant, not a normal runtime prompt.
    // When the user returns, retry registration automatically so the path is not copied.
    val allFilesAccessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        sharedPathAccessGranted = hasSharedPathAccess(context)
        if (sharedPathAccessGranted) {
            viewModel.registerExistingPath()
        } else {
            viewModel.showMessage("Storage access was not enabled. Try Add GGUF from storage again after granting access, or use Import GGUF file(s) (copy).")
        }
    }
    val legacyStoragePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        sharedPathAccessGranted = granted
        if (granted) viewModel.registerExistingPath()
        else viewModel.showMessage("Storage permission was not granted. Use Import GGUF file(s) (copy), or grant access and try Add GGUF from storage again.")
    }

    // Default path: select a GGUF in Android's picker and register its real shared-storage path.
    // This avoids making a second multi-GB copy. The native engine needs a filesystem path, not content://.
    val noCopyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val path = resolveSharedStorageDocumentPath(context, uri)
            if (path == null) {
                viewModel.showMessage(
                    "This picker location does not expose a direct filesystem path. Browse to Internal storage in the picker, " +
                        "select the .gguf there, or use Import & copy for cloud/provider files."
                )
            } else if (!path.endsWith(".gguf", ignoreCase = true)) {
                viewModel.showMessage("Choose a .gguf file (model, MTP draft, or mmproj). No file was copied.")
            } else {
                viewModel.setExistingPathInput(path)
                if (!isSharedAbsolutePath(path) || sharedPathAccessGranted) {
                    viewModel.registerExistingPath()
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val appSettings = Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                    try {
                        allFilesAccessLauncher.launch(appSettings)
                    } catch (_: Exception) {
                        allFilesAccessLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    legacyStoragePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                } else {
                    viewModel.registerExistingPath()
                }
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val targetId = targetIncompleteImportId
        targetIncompleteImportId = null
        if (uris.isNotEmpty()) viewModel.importModels(context.contentResolver, uris, targetId)
    }

    // A sidecar is chosen independently from ordinary models. Copy it privately so the native loader
    // receives a stable filesystem path even when the original lives behind a SAF/cloud provider URI.
    val mtpPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val modelPath = targetMtpModelPath
        targetMtpModelPath = null
        if (uri != null && modelPath != null) {
            viewModel.importAndAttachDraft(context.contentResolver, uri, modelPath)
        }
    }
    val mmprojPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val modelPath = targetMmprojModelPath
        targetMmprojModelPath = null
        if (uri != null && modelPath != null) {
            viewModel.importAndAttachMmproj(context.contentResolver, uri, modelPath)
        }
    }
    // Same for a LoRA adapter: copied into app storage, checked by its GGUF header, attached to the model.
    val loraPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val modelPath = targetLoraModelPath
        targetLoraModelPath = null
        if (uri != null && modelPath != null) {
            viewModel.importAndAttachLora(context.contentResolver, uri, modelPath)
        }
    }

    // Model folder: pick a folder in Android's picker; its real path is stored and every GGUF in it is listed.
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val path = runCatching { pathForExternalStorageDocumentId(context, DocumentsContract.getTreeDocumentId(uri)) }.getOrNull()
            if (path == null) viewModel.showMessage("This location has no direct file path. Pick a folder on Internal storage or an SD card.")
            else viewModel.setModelFolder(path)
        }
    }
    val folderAccessLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        sharedPathAccessGranted = hasSharedPathAccess(context)
        viewModel.refresh()
    }
    // New files in the model folder show up whenever the page is opened.
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("MODELS", style = MaterialTheme.typography.headlineMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold)
                }
            )
        },
        modifier = modifier
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                // The loaded model's own settings (Models page) changed since it was loaded: KV cache, LoRA, projector,
                // drafter (only while Guess ahead is MTP). Compared with what the load asked for, so a fallback the
                // engine had to take does not keep asking for a reload.
                val loadedInfo = (engine as? EngineState.Ready)?.info
                val addOnsChanged = loadedInfo?.let { info ->
                    val req = info.requested
                    val p = info.path
                    (ui.modelKvTypes[p] ?: com.bigmoe.onedge.core.KvCacheType.F16) != req.kvCacheType ||
                        ui.modelLoras[p] != req.lora ||
                        ui.modelMmprojs[p] != req.mmprojPath ||
                        (settings.spec == com.bigmoe.onedge.core.SpecSource.MTP && ui.modelDrafts[p] != req.draftPath)
                } == true
                // Loading, loaded and failed are shown in the model's own card below. This card is only for a state
                // no card in the list belongs to (a model whose file is no longer listed, a failure without a path).
                val enginePath = engineModelPath(engine)
                val listed = enginePath != null && ui.models.any { it.path == enginePath && !ModelManager.isProjectorName(it.name) }
                if (engine !is EngineState.Unloaded && !listed) {
                    StatusCard(engine, settings, addOnsChanged, onUnload = viewModel::unload, onReload = viewModel::reloadCurrent)
                }
            }

            item(key = "model_folder") {
                ModelFolderCard(
                    folder = ui.modelFolder,
                    files = ui.modelFolderFiles,
                    readable = ui.modelFolderReadable,
                    needsAccess = ui.modelFolder?.let { isSharedAbsolutePath(it) } == true && !sharedPathAccessGranted,
                    onChoose = { folderPicker.launch(null) },
                    onOff = { viewModel.setModelFolder(null) },
                    onDefault = viewModel::resetModelFolder,
                    onRefresh = viewModel::refresh,
                    onGrantAccess = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            try {
                                folderAccessLauncher.launch(
                                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                                )
                            } catch (_: Exception) {
                                folderAccessLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                            }
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            legacyStoragePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                        }
                    }
                )
            }

            item { SupportedModelsCard() }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Add a model / mmproj", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Choose a GGUF from Internal storage to add it in place. The model stays in its original folder, " +
                                "so BigMoeOnEdge does not create another multi-GB copy. A storage-access permission may be " +
                                "requested the first time. Cloud/provider files may need the copy option below.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Button(
                            onClick = { picker.launch(arrayOf("*/*")) },
                            enabled = ui.importProgress == null,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Import GGUF file(s) (copy)") }
                        OutlinedButton(
                            onClick = { noCopyPicker.launch(arrayOf("*/*")) },
                            enabled = ui.importProgress == null,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Add GGUF from storage (no copy) may be slower") }
                        TextButton(onClick = { showAdvancedPath = !showAdvancedPath }) {
                            Text(if (showAdvancedPath) "Hide advanced path entry" else "Advanced: enter a path manually")
                        }
                        if (showAdvancedPath) {
                            Text(
                                "Use this only if the picker cannot expose the original filesystem path. Split GGUFs must be registered from the first shard.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            OutlinedTextField(
                                value = ui.existingPathInput,
                                onValueChange = viewModel::setExistingPathInput,
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Existing GGUF path (no copy)") },
                                placeholder = { Text("/storage/emulated/0/Download/model-00001-of-00012.gguf") },
                                singleLine = true
                            )
                            val needsSharedStoragePermission = isSharedAbsolutePath(ui.existingPathInput) && !sharedPathAccessGranted
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    if (!isSharedAbsolutePath(ui.existingPathInput) || sharedPathAccessGranted) {
                                        viewModel.registerExistingPath()
                                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                        val appSettings = Intent(
                                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                        try {
                                            allFilesAccessLauncher.launch(appSettings)
                                        } catch (_: Exception) {
                                            allFilesAccessLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                                        }
                                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                        legacyStoragePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                                    } else {
                                        viewModel.registerExistingPath()
                                    }
                                }) {
                                    Text(if (needsSharedStoragePermission) "Grant path access" else "Register path")
                                }
                                OutlinedButton(onClick = viewModel::refresh) { Text("Refresh") }
                            }
                            if (needsSharedStoragePermission) {
                                Text(
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                                        "Android requires Allow access to manage all files for direct paths. Registration retries when you return."
                                    else
                                        "Allow file read access when Android asks. The file is not copied.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                        val progress = ui.importProgress
                        if (progress != null) {
                            if (progress.totalBytes > 0) {
                                LinearProgressIndicator(
                                    progress = { (progress.copiedBytes.toFloat() / progress.totalBytes).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    "${ModelManager.formatBytes(progress.copiedBytes)} / ${ModelManager.formatBytes(progress.totalBytes)}",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                Text(ModelManager.formatBytes(progress.copiedBytes), style = MaterialTheme.typography.labelSmall)
                            }
                            OutlinedButton(onClick = viewModel::cancelImport) { Text("Cancel import") }
                        }
                    }
                }
            }

            ui.message?.let { msg ->
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(msg, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = viewModel::dismissMessage) { Text("OK") }
                        }
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Load the last model on app start", modifier = Modifier.weight(1f))
                    Switch(checked = settings.autoLoadLastModel, onCheckedChange = viewModel::setAutoLoad)
                }
            }

            item(key = "guess_ahead") { GuessAheadCard(settings, viewModel) }

            val projectors = ui.models.filter { ModelManager.isProjectorName(it.name) }
            if (ui.incompleteImports.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Incomplete split models", style = MaterialTheme.typography.titleMedium)
                        Text("These imports are saved privately and survive app restarts. They cannot be loaded until every shard passes validation.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(ui.incompleteImports, key = { "incomplete:${it.id}" }) { pending ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(pending.displayName, style = MaterialTheme.typography.titleSmall)
                            Text("${pending.presentShardIndices.size}/${pending.expectedShardCount} shards imported • ${ModelManager.formatBytes(pending.totalSizeBytes)}", style = MaterialTheme.typography.bodySmall)
                            Text("Missing shard indices: " + pending.missingShardIndices.joinToString { it.toString().padStart(5, '0') }.ifBlank { "none" }, style = MaterialTheme.typography.bodySmall)
                            Text("Status: ${pending.status}", style = MaterialTheme.typography.labelMedium, color = if (pending.validationMessage != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
                            pending.validationMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        targetIncompleteImportId = pending.id
                                        picker.launch(arrayOf("*/*"))
                                    },
                                    enabled = ui.importProgress == null && pending.missingShardIndices.isNotEmpty() && pending.status != "Validation failed",
                                    modifier = Modifier.weight(1f)
                                ) { Text("Add missing files") }
                                OutlinedButton(
                                    onClick = { viewModel.removeIncompleteImport(pending.id) },
                                    enabled = ui.importProgress == null
                                ) { Text("Remove") }
                            }
                        }
                    }
                }
            }
            item(key = "models_header") { SectionHeader("Models", null) }
            if (ui.models.isEmpty() && ui.incompleteImports.isEmpty()) {
                item { Text("No models yet.", color = MaterialTheme.colorScheme.secondary) }
            }
            items(ui.models.filterNot { ModelManager.isProjectorName(it.name) }, key = { it.path }) { model ->
                val loadedInfo = (engine as? EngineState.Ready)?.info?.takeIf { it.path == model.path }
                val addOnsChanged = loadedInfo?.let { info ->
                    val req = info.requested
                    (ui.modelKvTypes[model.path] ?: com.bigmoe.onedge.core.KvCacheType.F16) != req.kvCacheType ||
                        ui.modelLoras[model.path] != req.lora ||
                        ui.modelMmprojs[model.path] != req.mmprojPath ||
                        (settings.spec == com.bigmoe.onedge.core.SpecSource.MTP && ui.modelDrafts[model.path] != req.draftPath)
                } == true
                ModelRow(
                    model = model,
                    isLoaded = loadedInfo != null,
                    busy = engine is EngineState.Loading,
                    engine = engine.takeIf { engineModelPath(it) == model.path },
                    addOnsChanged = addOnsChanged,
                    onUnload = viewModel::unload,
                    onReload = viewModel::reloadCurrent,
                    onLoad = { viewModel.requestLoad(model.path) },
                    onDelete = { viewModel.delete(model.path) },
                    drafts = ui.drafts,
                    attachedDraftPath = ui.modelDrafts[model.path],
                    onAttachDraft = { viewModel.attachDraft(model.path, it) },
                    onPickDraft = {
                        targetMtpModelPath = model.path
                        mtpPicker.launch(arrayOf("application/octet-stream", "*/*"))
                    },
                    onRename = { viewModel.rename(model.path, it) },
                    loras = ui.loras,
                    attachedLora = ui.modelLoras[model.path],
                    onAttachLora = { viewModel.attachLora(model.path, it) },
                    onPickLora = {
                        targetLoraModelPath = model.path
                        loraPicker.launch(arrayOf("application/octet-stream", "*/*"))
                    },
                    onLoraScale = { viewModel.setLoraScale(model.path, it) },
                    projectors = projectors,
                    attachedMmproj = ui.modelMmprojs[model.path],
                    onAttachMmproj = { viewModel.attachMmproj(model.path, it) },
                    onPickMmproj = {
                        targetMmprojModelPath = model.path
                        mmprojPicker.launch(arrayOf("application/octet-stream", "*/*"))
                    },
                    kvType = ui.modelKvTypes[model.path] ?: com.bigmoe.onedge.core.KvCacheType.F16,
                    loadedKvType = (engine as? EngineState.Ready)?.info?.takeIf { it.path == model.path }?.config?.kvCacheType,
                    onKvType = { viewModel.setKvCacheType(model.path, it) },
                    settings = settings
                )
            }
            // Auxiliary files below the models, each kind in its own list: attach them to a model in its card above.
            if (ui.drafts.isNotEmpty()) {
                item(key = "drafts_header") { SectionHeader("MTP drafters", "Attach one to a model above; used when Guess ahead is MTP.") }
                items(ui.drafts, key = { "draft:${it.path}" }) { AuxFileCard(it) { viewModel.delete(it.path) } }
            }
            if (ui.loras.isNotEmpty()) {
                item(key = "loras_header") { SectionHeader("LoRA adapters", "Attach one to a model above; applied when that model loads.") }
                items(ui.loras, key = { "lora:${it.path}" }) { AuxFileCard(it) { viewModel.delete(it.path) } }
            }
            if (projectors.isNotEmpty()) {
                item(key = "mmproj_header") { SectionHeader("Vision projectors (mmproj)", "Attach one to a model above; it loads together with that model.") }
                items(projectors, key = { "mmproj:${it.path}" }) { AuxFileCard(it) { viewModel.delete(it.path) } }
            }
            // keeps the last row clear of the floating navigation bar
            item(key = "bottom_spacer") { androidx.compose.foundation.layout.Spacer(Modifier.height(112.dp)) }
        }
    }

    ui.pendingLoad?.let { pending -> PendingLoadDialog(viewModel, pending) }
}

/** Shown before a load only when the pre-flight checks warn about something. Shared by the Models page and the chat's model picker. */
@Composable
internal fun PendingLoadDialog(viewModel: ModelViewModel, pending: PendingLoad) {
    AlertDialog(
        onDismissRequest = viewModel::dismissPendingLoad,
        title = { Text(if (pending.advice.canLoad) "Load model?" else "Cannot load") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                pending.advice.errors.forEach { Text("• $it", color = MaterialTheme.colorScheme.error) }
                pending.advice.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                if (pending.advice.canLoad) {
                    Text(
                        "Vision projector: " + (pending.selectedProjector?.substringAfterLast('/') ?: "none (text only)"),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Loading can take a minute or more and uses a lot of memory. Keep the app open.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            if (pending.advice.canLoad) TextButton(onClick = viewModel::confirmLoad) { Text("Load") }
            else TextButton(onClick = viewModel::dismissPendingLoad) { Text("OK") }
        },
        dismissButton = {
            if (pending.advice.canLoad) TextButton(onClick = viewModel::dismissPendingLoad) { Text("Cancel") }
        }
    )
}

@Composable
private fun SectionHeader(title: String, subtitle: String?) {
    Column(Modifier.padding(top = 6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** One MTP drafter, LoRA adapter or projector file in its list, with its size and a Remove button. */
@Composable
private fun AuxFileCard(file: ModelFile, onRemove: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(file.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${ModelManager.formatBytes(file.sizeBytes)} • " +
                        if (file.inAppStorage) "copy in app storage" else "in ${file.path.substringBeforeLast('/')}",
                    style = MaterialTheme.typography.labelSmall
                )
            }
            TextButton(onClick = onRemove) { Text("Remove") }
        }
    }
}

@Composable
private fun SupportedModelsCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Any MoE model will work.", style = MaterialTheme.typography.titleMedium)
            Text(
                "Even regular (dense) models can work: they are detected automatically and loaded the ordinary way.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun StatusCard(
    engine: EngineState,
    settings: com.bigmoe.onedge.data.local.datastore.EngineSettings,
    addOnsChanged: Boolean,
    onUnload: () -> Unit,
    onReload: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (engine is EngineState.Ready) Text("Loaded: ${engine.info.name}", style = MaterialTheme.typography.titleMedium)
            if (engine is EngineState.Loading) Text("Loading ${engine.path.substringAfterLast('/')}…")
            ModelStatusSection(engine, settings, addOnsChanged, onUnload, onReload)
        }
    }
}

/** Which model the engine state is about (loading, loaded or failed), or null. */
private fun engineModelPath(engine: EngineState): String? = when (engine) {
    is EngineState.Loading -> engine.path
    is EngineState.Ready -> engine.info.path
    is EngineState.Failed -> engine.path
    EngineState.Unloaded -> null
}

/**
 * The engine's state for one model: a real progress bar while it loads (the step the engine reports and how far
 * it is within it), what was loaded with Reload / Unload, or why the load failed. Shown inside the model's card.
 */
@Composable
private fun ModelStatusSection(
    engine: EngineState,
    settings: com.bigmoe.onedge.data.local.datastore.EngineSettings,
    addOnsChanged: Boolean,
    onUnload: () -> Unit,
    onReload: () -> Unit
) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (engine) {
                EngineState.Unloaded -> Text("No model loaded")
                is EngineState.Loading -> {
                    var now by remember { mutableStateOf(System.currentTimeMillis()) }
                    androidx.compose.runtime.LaunchedEffect(engine.startedAtMs) {
                        while (true) {
                            now = System.currentTimeMillis()
                            kotlinx.coroutines.delay(500)
                        }
                    }
                    val elapsed = ((now - engine.startedAtMs) / 1000).coerceAtLeast(0)
                    val step = engine.step
                    // Steps that are one call (finding the experts, the NPU) have no fraction to show: an
                    // indeterminate bar there, a real one for reading the model and the weights.
                    val measured = step == com.bigmoe.onedge.core.LoadStep.MODEL ||
                        step == com.bigmoe.onedge.core.LoadStep.DRAFT ||
                        step == com.bigmoe.onedge.core.LoadStep.WEIGHTS
                    Text(
                        step.label + (if (measured) " • ${(engine.fraction * 100).toInt()} %" else "…"),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (measured) {
                        LinearProgressIndicator(progress = { engine.fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Text(
                        "%d:%02d elapsed".format(elapsed / 60, elapsed % 60) + " • steps: model, experts, weights" +
                            (if (settings.npuPrefill) ", NPU" else ""),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                is EngineState.Failed -> {
                    Text("Load failed", color = MaterialTheme.colorScheme.error)
                    Text(engine.message, style = MaterialTheme.typography.bodySmall)
                }
                is EngineState.Ready -> {
                    val info = engine.info
                    info.draftModelPath?.let { Text("Separate MTP draft: ${it.substringAfterLast('/')}", style = MaterialTheme.typography.bodySmall) }
                    info.lora?.let {
                        Text(
                            "LoRA adapter: ${it.path.substringAfterLast('/')} • strength " + String.format(java.util.Locale.US, "%.2f", it.scale),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    info.loadWarning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    Text(
                        if (info.mmprojPath.isNullOrBlank()) "Vision: inactive (no mmproj)"
                        else "Vision: active • ${info.mmprojPath.substringAfterLast('/')}"
                    )
                    Text(
                        "arch ${info.arch} • context ${info.nCtx} • ${info.nExpertUsed} experts/token • " +
                            "loaded in ${"%.1f".format(java.util.Locale.US, info.loadSeconds)} s",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        if (info.config.mmapBaseline) "Regular (non-MoE) model: ordinary load, no expert streaming"
                        else "Expert streaming ON • I/O–compute overlap: " + if (info.overlapActive) "active" else "not active",
                        style = MaterialTheme.typography.labelSmall
                    )
                    if (info.thinkControl == "none") {
                        Text(
                            "This model has no switch to turn thinking off.",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    val settingsChanged = loadConfigDiffers(info.requested.config ?: info.config, settings)
                    if (settingsChanged || addOnsChanged) {
                        Text(
                            listOfNotNull(
                                if (settingsChanged) "settings (context, sampling, cache, threads, guess ahead)" else null,
                                if (addOnsChanged) "this model's KV cache, LoRA, projector or drafter" else null
                            ).joinToString(" and ", prefix = "Changed since this model was loaded: ", postfix = ". They apply after a reload."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (settingsChanged || addOnsChanged) Button(onClick = onReload) { Text("Reload model") }
                        OutlinedButton(onClick = onUnload) { Text("Unload") }
                    }
                }
            }
        }
}

@Composable
private fun ModelRow(
    model: ModelFile,
    isLoaded: Boolean,
    busy: Boolean,
    engine: EngineState?,          // the engine's state when it is about THIS model (loading, loaded, failed), else null
    addOnsChanged: Boolean,
    onUnload: () -> Unit,
    onReload: () -> Unit,
    onLoad: () -> Unit,
    onDelete: () -> Unit,
    drafts: List<ModelFile>,
    attachedDraftPath: String?,
    onAttachDraft: (String?) -> Unit,
    onPickDraft: () -> Unit,
    onRename: (String) -> Unit,
    loras: List<ModelFile>,
    attachedLora: com.bigmoe.onedge.core.LoraChoice?,
    onAttachLora: (String?) -> Unit,
    onPickLora: () -> Unit,
    onLoraScale: (Float) -> Unit,
    projectors: List<ModelFile>,
    attachedMmproj: String?,
    onAttachMmproj: (String?) -> Unit,
    onPickMmproj: () -> Unit,
    kvType: com.bigmoe.onedge.core.KvCacheType,
    loadedKvType: com.bigmoe.onedge.core.KvCacheType?,
    onKvType: (com.bigmoe.onedge.core.KvCacheType) -> Unit,
    settings: EngineSettings
) {
    // Collapsed by default so a long list stays short; the loaded model opens. Tap the name to toggle.
    var expanded by androidx.compose.runtime.saveable.rememberSaveable(model.path) { mutableStateOf(isLoaded) }
    // The card opens by itself when its model starts loading, is loaded or fails: that is where its status is.
    val engineKind = engine?.let { it::class }
    androidx.compose.runtime.LaunchedEffect(engineKind) { if (engineKind != null) expanded = true }
    var renameDialog by remember { mutableStateOf(false) }
    var renameText by remember(model.path, model.displayName) { mutableStateOf(model.displayName) }
    val attachedName = drafts.firstOrNull { it.path == attachedDraftPath }?.name
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable { expanded = !expanded }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (expanded) "Collapse" else "Expand",
                            modifier = Modifier.size(20.dp)
                        )
                        Text(model.displayName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 4.dp))
                    }
                    Text("${ModelManager.formatBytes(model.sizeBytes)} • " + if (model.inAppStorage) "App storage" else "Registered path (no copy)", style = MaterialTheme.typography.labelSmall)
                    if (!expanded) {
                        // What is attached, in one line, so the collapsed card still says how the model will load.
                        val parts = listOfNotNull(
                            attachedName?.let { "MTP" },
                            attachedMmproj?.let { "Vision" },
                            attachedLora?.let { "LoRA" },
                            kvType.takeIf { it != com.bigmoe.onedge.core.KvCacheType.F16 }?.let { "KV ${it.label}" }
                        )
                        if (parts.isNotEmpty()) {
                            Text(parts.joinToString(" • "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                if (isLoaded) {
                    Text("Loaded", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                } else if (engine is EngineState.Loading) {
                    Text("Loading…", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                } else {
                    TextButton(onClick = onLoad, enabled = !busy) { Text("Load") }
                }
                // Rename renames the model itself, so it lives here and not beside the drafter.
                IconButton(onClick = { renameDialog = true }) {
                    Icon(Icons.Default.Edit, contentDescription = "Rename model")
                }
                IconButton(onClick = onDelete, enabled = !busy) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove model")
                }
            }
            // Guess-ahead settings apply to every model (card above the list); only the drafter file is per model. It can
            // always be attached: with Guess ahead off it simply waits until MTP is switched on.
            if (expanded) {
                if (engine != null) {
                    ModelStatusSection(engine, settings, addOnsChanged, onUnload, onReload)
                    androidx.compose.material3.HorizontalDivider()
                }
                DrafterRow(settings, drafts, attachedName, onAttachDraft, onPickDraft)
                MmprojRow(projectors, attachedMmproj, isLoaded, onAttachMmproj, onPickMmproj)
                LoraRow(loras, attachedLora, isLoaded, onAttachLora, onPickLora, onLoraScale)
                KvCacheRow(kvType, loadedKvType, onKvType)
                if (!model.inAppStorage) Text("This file stays in its original folder; removing it here only unregisters it.", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    if (renameDialog) {
        AlertDialog(
            onDismissRequest = { renameDialog = false },
            title = { Text("Rename model") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true, label = { Text("New name") })
                    if (model.name.contains(Regex("-\\d{5}-of-\\d{5}\\.gguf$", RegexOption.IGNORE_CASE))) {
                        Text("This changes only the model display name. The original shard filenames stay unchanged so llama.cpp can discover every part.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { renameDialog = false; onRename(renameText) }) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { renameDialog = false }) { Text("Cancel") } }
        )
    }
}

/**
 * Resolve only Android's real external-storage DocumentsProvider URIs to the backing file path.
 * Arbitrary cloud providers and Downloads virtual documents intentionally return null: their
 * content:// URI cannot safely be passed to the native GGUF loader as a filesystem path.
 */
private fun resolveSharedStorageDocumentPath(context: android.content.Context, uri: Uri): String? = runCatching {
    if (!DocumentsContract.isDocumentUri(context, uri) ||
        uri.authority != "com.android.externalstorage.documents") return@runCatching null
    pathForExternalStorageDocumentId(context, DocumentsContract.getDocumentId(uri))
}.getOrNull()

/**
 * "primary:Models/qwen" (a document or tree id of the external-storage provider) -> /storage/emulated/0/Models/qwen.
 * An id for the volume root ("primary:") gives the volume itself. null when there is no direct path.
 */
private fun pathForExternalStorageDocumentId(context: android.content.Context, documentId: String): String? = runCatching {
    val colon = documentId.indexOf(':')
    if (colon <= 0) return@runCatching null

    val volumeId = documentId.substring(0, colon)
    val relativePath = documentId.substring(colon + 1).replace('\\', '/')
    if (relativePath.startsWith('/') || relativePath.split('/').any { it == ".." }) return@runCatching null

    val base = if (volumeId.equals("primary", ignoreCase = true)) {
        Environment.getExternalStorageDirectory()
    } else {
        // External-volume file roots usually look like /storage/<UUID>/Android/data/<package>/files.
        context.getExternalFilesDirs(null).filterNotNull().firstNotNullOfOrNull { appDir ->
            val normalized = appDir.absolutePath.replace('\\', '/')
            val marker = "/Android/data/${context.packageName}/"
            val at = normalized.indexOf(marker)
            if (at > 0 && normalized.substring(0, at).substringAfterLast('/') == volumeId) {
                File(normalized.substring(0, at))
            } else null
        } ?: return@runCatching null
    }.canonicalFile

    val selected = (if (relativePath.isEmpty()) base else File(base, relativePath)).canonicalFile
    if (selected.path != base.path && !selected.path.startsWith(base.path + File.separator)) return@runCatching null
    selected.path
}.getOrNull()

/** Models page, top: the folder whose GGUFs are listed automatically, in place (no copy, no registering). */
@Composable
private fun ModelFolderCard(
    folder: String?,
    files: Int,
    readable: Boolean,
    needsAccess: Boolean,
    onChoose: () -> Unit,
    onOff: () -> Unit,
    onDefault: () -> Unit,
    onRefresh: () -> Unit,
    onGrantAccess: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Model folder", style = MaterialTheme.typography.titleMedium)
            val status = when {
                folder == null -> "Off. Pick a folder and every GGUF in it (and two levels of subfolders) shows up below, " +
                    "used in place: nothing is copied."
                needsAccess -> "$folder\nNeeds access to all files to read the folder."
                !readable -> "$folder\nThe folder does not exist or cannot be read. Create it, or pick another one."
                else -> "$folder\n$files GGUF file${if (files == 1) "" else "s"} found. New files show up when this page is opened. " +
                    "To remove one from the list, delete or move it in the folder."
            }
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (needsAccess) Button(onClick = onGrantAccess) { Text("Grant access") }
                OutlinedButton(onClick = onChoose) { Text(if (folder == null) "Choose folder" else "Change") }
                if (folder != null) {
                    TextButton(onClick = onRefresh) { Text("Refresh") }
                    TextButton(onClick = onOff) { Text("Turn off") }
                } else {
                    TextButton(onClick = onDefault) { Text("Use Internal storage/Models") }
                }
            }
        }
    }
}

private fun hasSharedPathAccess(context: android.content.Context): Boolean = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    else -> true
}

/** Only absolute paths in Android shared/external storage need the special storage grant. */
private fun isSharedAbsolutePath(rawPath: String): Boolean {
    val path = rawPath.trim().replace('\\', '/')
    return path.startsWith("/storage/") || path == "/sdcard" || path.startsWith("/sdcard/")
}

