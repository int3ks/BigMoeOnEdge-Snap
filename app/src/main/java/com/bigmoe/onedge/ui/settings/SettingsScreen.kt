package com.bigmoe.onedge.ui.settings

import com.bigmoe.onedge.core.t

import com.bigmoe.onedge.ui.theme.AppCard
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.bigmoe.onedge.core.DenseWeights
import com.bigmoe.onedge.core.DecisionPrefixCache
import com.bigmoe.onedge.core.EngineState
import com.bigmoe.onedge.core.SpecSource
import com.bigmoe.onedge.data.local.datastore.EngineSettings
import com.bigmoe.onedge.data.local.datastore.AppVisualStyle
import com.bigmoe.onedge.data.local.datastore.WebSearchMode
import com.bigmoe.onedge.data.local.datastore.loadConfigDiffers
import com.bigmoe.onedge.ui.theme.PalettePresets
import com.bigmoe.onedge.ui.theme.PaletteSpec
import com.bigmoe.onedge.ui.theme.PaletteStore
import com.bigmoe.onedge.ui.theme.AppTheme
import com.bigmoe.onedge.ui.theme.appSurfaceModifier
import kotlinx.coroutines.delay
import java.io.File

internal const val DEBOUNCE_MS = 500L

/** Same choice lists as the original BigMoeOnEdge Android app (AppSettings.*_CHOICES). */
private object Choices {
    // Finer where it matters: 250 MiB steps up to 2000, then 500 MiB steps up to 10000.
    val CACHE = listOf(0) + com.bigmoe.onedge.core.SettingChoices.CACHE_MB
    val IO = com.bigmoe.onedge.core.SettingChoices.IO_LANES
    val TOPK = listOf(0, 6, 4, 3, 2)
    val PREFETCH = listOf(0, 1, 2, 4)
    val DISCARD = com.bigmoe.onedge.core.SettingChoices.DISCARD_PCT
    val PREDICT_SPEC = listOf(0, 1, 2, 4)
    val ROUTE_AHEAD = listOf(0, 1, 2, 4)
    val DROP_COLD = listOf(0, 50, 75, 100)
    val SUBSTITUTE = listOf(0, 10, 15, 20, 30)
    val BATCH_WIDTH = com.bigmoe.onedge.core.SettingChoices.BATCH_WIDTH
    val NPU_MIN_TOKENS = com.bigmoe.onedge.core.SettingChoices.NPU_MIN_TOKENS
    val NPU_ROUTED_FULL_PCT = listOf(50, 70, 85, 95, 100)
    val IMAGE_DIM = listOf(256, 384, 512, 768, 1024, 1344)
    val VIDEO_FRAMES = listOf(1, 2, 4, 6, 8, 12, 16)
}

private fun denseLabel(d: DenseWeights) = when (d) {
    DenseWeights.MMAP -> "Mmap (baseline)"
    DenseWeights.WARMED -> "Warm at load"
    DenseWeights.ANONYMOUS -> "Anon (O_DIRECT)"
    DenseWeights.PINNED -> "Pinned (dma-buf)"
}

private fun denseBlurb(d: DenseWeights) = when (d) {
    DenseWeights.MMAP -> "Left to the kernel, faulted in as touched. The baseline."
    DenseWeights.WARMED -> "Paged in once at load, so the first tokens do not fault. Best when the model fits in RAM."
    DenseWeights.ANONYMOUS -> "Read into the app's own memory. Reclaim compresses them instead of dropping them, so a refault costs no flash read. The default."
    DenseWeights.PINNED -> "As Anon, but reclaim cannot touch it at all, not even to compress. Pays off over a long conversation."
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenModels: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onOpenAutoSettings: () -> Unit,
    onOpenPerfLog: () -> Unit = {},
    onOpenLog: () -> Unit,
    modifier: Modifier = Modifier
) {
    val loaded by viewModel.settingsState.collectAsState()
    val engine by viewModel.engineState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (AppTheme.visualStyle == AppVisualStyle.SOLID) MaterialTheme.colorScheme.surface else Color.Transparent
                ),
                title = { Text("SETTINGS", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold) },
                actions = { com.bigmoe.onedge.ui.navigation.MainNavButtons(com.bigmoe.onedge.ui.navigation.TopTab.SETTINGS) }
            )
        },
        modifier = modifier,
        containerColor = if (AppTheme.visualStyle == AppVisualStyle.SOLID) MaterialTheme.colorScheme.background else Color.Transparent
    ) { innerPadding ->
        val settings = loaded
        if (settings == null) {
            Column(Modifier.fillMaxSize().padding(innerPadding)) {}
        } else {
            SettingsContent(
                settings = settings,
                engine = engine,
                viewModel = viewModel,
                onOpenModels = onOpenModels,
                onOpenWorkspace = onOpenWorkspace,
                onOpenAutoSettings = onOpenAutoSettings,
                onOpenPerfLog = onOpenPerfLog,
                onOpenLog = onOpenLog,
                modifier = Modifier.fillMaxSize().padding(innerPadding)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsContent(
    settings: EngineSettings,
    engine: EngineState,
    viewModel: SettingsViewModel,
    onOpenModels: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onOpenAutoSettings: () -> Unit,
    onOpenPerfLog: () -> Unit = {},
    onOpenLog: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val readyInfo = (engine as? EngineState.Ready)?.info
    // Streaming settings only apply when the loaded model is a Mixture-of-Experts one (chosen automatically on load).
    val stream = readyInfo?.config?.mmapBaseline != true
    val cacheOn = stream && settings.cacheMb != 0

    // Text fields keep their own state, seeded ONCE from the stored value, and write back debounced.
    var braveKey by remember { mutableStateOf(settings.braveApiKey) }
    var searxUrl by remember { mutableStateOf(settings.searxngUrl) }
    var temp by remember { mutableFloatStateOf(settings.temperature) }
    var topP by remember { mutableFloatStateOf(settings.topP) }
    var topK by remember { mutableFloatStateOf(settings.topK.toFloat()) }
    var minP by remember { mutableFloatStateOf(settings.minP) }
    var repeatPenalty by remember { mutableFloatStateOf(settings.repeatPenalty) }
    var repeatLastN by remember { mutableFloatStateOf(settings.repeatLastN.toFloat()) }
    var frequencyPenalty by remember { mutableFloatStateOf(settings.frequencyPenalty) }
    var presencePenalty by remember { mutableFloatStateOf(settings.presencePenalty) }
    var seedText by remember { mutableStateOf(settings.seed.toString()) }
    var cacheFloorText by remember { mutableStateOf(settings.cacheFloorMb.toString()) }
    var contextText by remember { mutableStateOf(settings.contextLength.toString()) }
    val contextValid = ContextInput.parse(contextText) != null
    var performanceExpanded by remember { mutableStateOf(false) }
    var toolsMediaExpanded by remember { mutableStateOf(false) }
    var appearanceExpanded by remember { mutableStateOf(false) }
    var chatExpanded by remember { mutableStateOf(false) }
    var mediaExpanded by remember { mutableStateOf(false) }
    var toolsExpanded by remember { mutableStateOf(false) }
    var agentExpanded by remember { mutableStateOf(false) }
    var searchExpanded by remember { mutableStateOf(false) }
    var workspaceExpanded by remember { mutableStateOf(false) }
    var editingPalette by remember { mutableStateOf<PaletteSpec?>(null) }
    var editingIsNew by remember { mutableStateOf(false) }

    LaunchedEffect(braveKey) {
        if (braveKey != settings.braveApiKey) { delay(DEBOUNCE_MS); viewModel.updateBraveApiKey(braveKey) }
    }
    LaunchedEffect(searxUrl) {
        if (searxUrl != settings.searxngUrl) { delay(DEBOUNCE_MS); viewModel.updateSearxngUrl(searxUrl) }
    }
    LaunchedEffect(contextText) {
        val v = ContextInput.parse(contextText)
        if (v != null && v != settings.contextLength) { delay(DEBOUNCE_MS); viewModel.updateContextLength(v) }
    }
    LaunchedEffect(cacheFloorText) {
        val v = cacheFloorText.toIntOrNull()
        if (v != null && v != settings.cacheFloorMb) { delay(DEBOUNCE_MS); viewModel.updateCacheFloorMb(v) }
    }
    LaunchedEffect(seedText) {
        val v = seedText.toIntOrNull()
        if (v != null && v >= -1 && v != settings.seed) { delay(DEBOUNCE_MS); viewModel.updateSeed(v) }
    }

    Column(modifier = modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        AppUpdateCard()
        Spacer(Modifier.height(10.dp))
        SettingsBackupRow(viewModel)
        Spacer(Modifier.height(10.dp))
        // Settings are grouped in the requested order: appearance, model, engine, tools/media, diagnostics.
        SettingsFoldout(
            title = "Appearance", subtitle = "Theme palettes and custom colours", expanded = appearanceExpanded,
            onToggle = { appearanceExpanded = !appearanceExpanded }
        )
        androidx.compose.animation.AnimatedVisibility(visible = appearanceExpanded, enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(), exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // ------------------------------------------------------------ appearance
            LanguageSettings()
            val custom = PaletteStore.parse(settings.customPalettesJson)
            val selectedCustom = custom.firstOrNull { it.id == settings.themeId }
            Text("Surface style", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("Choose how major surfaces look. Gradient tints every surface from your palette. Glass uses translucent frosted panels with light-catching edges over soft colour glows.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(
                    AppVisualStyle.SOLID to "Solid colors",
                    AppVisualStyle.GRADIENT to "Gradient",
                    AppVisualStyle.LIQUID_GLASS to "Glass"
                ).forEach { (style, label) ->
                    if (settings.visualStyle == style) {
                        Button(onClick = { viewModel.updateVisualStyle(style) }, modifier = Modifier.weight(1f)) { Text(label, textAlign = TextAlign.Center) }
                    } else {
                        OutlinedButton(onClick = { viewModel.updateVisualStyle(style) }, modifier = Modifier.weight(1f)) { Text(label, textAlign = TextAlign.Center) }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            val recommendedIds = listOf("paper", "pearl", "mint", "graphite", "ocean")
            val recommended = recommendedIds.mapNotNull { PalettePresets.byId(it) }
            val otherPresets = PalettePresets.all.filterNot { p -> recommendedIds.contains(p.id) }
            Text("Recommended", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(6.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                recommended.forEach { palette ->
                    PaletteChip(
                        palette = palette, selected = settings.themeId == palette.id,
                        onClick = { viewModel.selectTheme(palette.id) },
                        modifier = Modifier.width(150.dp)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("More palettes", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(6.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                otherPresets.forEach { palette ->
                    PaletteChip(
                        palette = palette, selected = settings.themeId == palette.id,
                        onClick = { viewModel.selectTheme(palette.id) },
                        modifier = Modifier.width(150.dp)
                    )
                }
                custom.forEach { palette ->
                    PaletteChip(
                        palette = palette, selected = settings.themeId == palette.id,
                        onClick = { viewModel.selectTheme(palette.id) },
                        modifier = Modifier.width(150.dp)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    val base = PaletteStore.resolve(settings.themeId, custom, false)
                    editingPalette = base.copy(id = PaletteStore.newId(custom), name = "My palette", custom = true)
                    editingIsNew = true
                }) { Text("+ New custom palette") }
                if (selectedCustom != null) {
                    OutlinedButton(onClick = { editingPalette = selectedCustom; editingIsNew = false }) { Text("Edit selected") }
                }
            }
            Hint("Recommended: Paper, Pearl, Mint, Graphite and Ocean. Custom palettes stay saved on this device.")
            Divider()


            }
        }
        Spacer(Modifier.height(8.dp))

        SettingsFoldout(
            title = "Chats & OneDrive sync", subtitle = "OneDrive sync, system prompts, dictation, workspace", expanded = chatExpanded,
            onToggle = { chatExpanded = !chatExpanded }
        )
        androidx.compose.animation.AnimatedVisibility(visible = chatExpanded, enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(), exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // ------------------------------------------------------------ OneDrive chat sync
            OneDriveSyncSection()
            Divider()

            // ------------------------------------------------------------ chat (system prompts)
            Section("System prompts")
            SystemPromptsSection(settings = settings, viewModel = viewModel)
            Divider()
            Section("Dictation and read aloud")
            SwitchRow(
                "Online speech (Google)",
                "Off: only offline - dictation needs an on-device recognizer, read aloud uses offline voices. " +
                    "On: when the device has no on-device recognizer, dictation uses the normal one (Google; an installed " +
                    "offline language pack is preferred, otherwise the audio goes to Google), and read aloud prefers a " +
                    "network voice while the device is online." +
                    (com.bigmoe.onedge.speech.SpeechPrefs.dictationService.value?.let { " Dictation last used: $it." } ?: ""),
                com.bigmoe.onedge.speech.SpeechPrefs.allowOnline(context)
            ) { com.bigmoe.onedge.speech.SpeechPrefs.setAllowOnline(context, it) }
            Divider()

            // ------------------------------------------------------------ workspace (private or shared folder)
            WorkspaceLocationSection(viewModel = viewModel, onOpenWorkspace = onOpenWorkspace)
            }
        }
        Spacer(Modifier.height(8.dp))

        SettingsFoldout(
            title = "Engine performance",
            subtitle = "Expert streaming, cache, compute and context controls",
            expanded = performanceExpanded,
            onToggle = { performanceExpanded = !performanceExpanded }
        )
        Spacer(Modifier.height(8.dp))
        if (performanceExpanded) {
            // ------------------------------------------------------------ CPU · NPU & memory
            PerformanceGroup("CPU · NPU & memory", "NPU prompt prefill, CPU cores, context") {
            Hint("These options apply only when the APK includes the Hexagon backend. Prompt prefill can use the NPU; token-by-token decode remains on CPU.")
            val npuCombinationAllowed = !settings.rowStream
            // Keep a stale saved-on switch interactive so an unavailable NPU preference can be turned off;
            // all other HTP controls remain disabled until the runtime probe really opens HTP0.
            val npuToggleEnabled = (viewModel.npuPrefillAvailable && npuCombinationAllowed) || settings.npuPrefill
            val loadedModel = (engine as? EngineState.Ready)?.info
            SwitchRow(
                "NPU prefill (Snapdragon Hexagon)",
                when {
                    settings.npuPrefill && !viewModel.npuPrefillAvailable -> "This preference is saved as on, but HTP is unavailable. Turn this switch off to allow CPU-only model loading."
                    !npuCombinationAllowed -> "Unavailable while Row-stream tables are enabled. Turn that option off before enabling NPU prefill."
                    loadedModel?.npuPrefillReady == true && loadedModel.config.npuPrefill && settings.npuPrefill -> "Enabled for the loaded model: HTP0 opened during model load. Actual execution is confirmed only when logcat reports NPU prefill device_nodes greater than zero."
                    loadedModel?.npuPrefillReady == true && !settings.npuPrefill -> "The currently loaded model still has HTP prefill active; reload it to apply this saved-off preference."
                    settings.npuPrefill -> "Selected for the next model load. Prompt graphs may run on HTP; token-by-token decode remains on CPU."
                    else -> "Uses the HTP device for eligible wide prompt graphs. This changes only after the model is reloaded; execution must be confirmed from device-node telemetry."
                },
                checked = settings.npuPrefill,
                enabled = npuToggleEnabled
            ) {
                viewModel.updateNpuPrefill(it)
            }
            Hint(viewModel.npuPrefillStatus)
            ChoiceSetting(
                    "NPU prefill loader threads",
                    com.bigmoe.onedge.core.SettingChoices.NPU_LOADERS.map { it to it.toString() },
                    settings.npuLoaders,
                    enabled = npuToggleEnabled && settings.npuPrefill
                ) { viewModel.updateNpuLoaders(it) }
                // With Guess ahead the CPU verify batch is draftMax + 1 wide; only thresholds above it are offered.
                val npuMinChoices = Choices.NPU_MIN_TOKENS.filter {
                    it <= settings.toLoadConfig().nUbatch && it >= settings.toLoadConfig().npuMinTokensFloor
                }.ifEmpty { listOf(settings.toLoadConfig().npuMinTokensFloor) }
                ChoiceSetting(
                    "Minimum prompt tokens sent to NPU",
                    npuMinChoices.map { it to "$it tokens" },
                    (npuMinChoices.lastOrNull { it <= settings.npuMinTokens } ?: npuMinChoices.first()),
                    enabled = npuToggleEnabled && settings.npuPrefill
                ) { viewModel.updateNpuMinTokens(it) }
                SwitchRow(
                    "Routed expert reads during NPU prefill",
                    "Predict which experts a prompt graph needs and load those instead of reading every expert in the layer. Falls back to a full-layer read when the predicted routing is dense.",
                    settings.npuRoutedPrefill,
                    enabled = npuToggleEnabled && settings.npuPrefill
                ) { viewModel.updateNpuRoutedPrefill(it) }
                if (settings.npuRoutedPrefill) {
                    ChoiceSetting(
                        "Full-layer fallback threshold",
                        Choices.NPU_ROUTED_FULL_PCT.map { it to "$it% of experts" },
                        settings.npuRoutedFullPct,
                        enabled = npuToggleEnabled && settings.npuPrefill
                    ) { viewModel.updateNpuRoutedFullPct(it) }
                    Hint("At higher thresholds, more layers use routed reads; a lower threshold chooses a full-layer read sooner. This is a memory-I/O trade-off, not a quality setting.")
                }
            if (!viewModel.npuPrefillAvailable) {
                Hint("Loader, threshold and routed-read preferences do not activate hardware by themselves. They stay disabled until the runtime can open HTP0.")
            }

            Section("CPU")
            CpuCoresSetting(
                decodeMask = com.bigmoe.onedge.core.CpuTopology.effective(settings.cpuMask),
                prefillMask = com.bigmoe.onedge.core.CpuTopology.effective(settings.cpuMaskPrefill),
                allowed = remember { viewModel.allowedCpus },
                onDecode = viewModel::updateCpuMask,
                onPrefill = viewModel::updateCpuMaskPrefill
            )
            SwitchRow(
                "Auto: CPU cores by throttling",
                "Decode only, decided before each answer, no reload. The decode cores chosen above are the MINIMUM: a model opens with them and Auto adds cores on top. It follows Android's own throttling signals: the thermal headroom (100 % = the device throttles hard) and the thermal status. While the headroom is below 90 % and the throttle step is at most 1 (light), one more core, the fastest one not in use, is added; if the next answer (48 tokens or more) is slower than before, it is taken back and Auto waits 5 minutes before the next try. From 95 % headroom an added core goes again. When the device really throttles (status moderate or worse, or Honor's own step 3), the added cores go first and Auto waits 5 minutes before adding again; if it still throttles, Auto goes below the chosen cores (never below 2); they come back when it stops. The title bar shows the headroom (\"heat\") and the cores in use.",
                settings.autoCores
            ) { viewModel.updateAutoCores(it) }
            ChoiceSetting(
                "Batch width (ubatch)",
                Choices.BATCH_WIDTH.map { it to "$it tokens" },
                settings.batchWidth
            ) { viewModel.updateBatchWidth(it) }
            Hint("Upper bound for tokens computed in one graph while processing prompts; the engine caps this at the context size. Smaller values reduce reserved compute RAM and can leave more memory for expert cache; larger values can prefill faster but may cause memory pressure. Decode speed is generally unaffected.")
            Text("Context (tokens)", style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(
                value = contextText,
                onValueChange = { v -> contextText = ContextInput.sanitize(v) },
                singleLine = true,
                isError = !contextValid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Hint(
                if (contextValid) "Type any number, there is no limit. Prompt plus reply the session can hold. Also memory: the KV cache is sized for it at open, so a very large value needs a lot of RAM and the model may fail to load; lower it if that happens. Speed tip: a smaller context also leaves more RAM for the expert cache."
                else "Enter a whole number greater than 0. The last valid value (${settings.contextLength}) stays in use."
            )
            SwitchRow(
                "KV snapshots on disk (experimental)",
                "Saves a chat's context when you switch to another chat, reload or unload the model, or leave the app, and loads it back when you return: the conversation continues without prefilling it again. Stored in the app's own storage, oldest removed first above 6 GB. Tied to the model file, KV type, LoRA and draft setup.",
                settings.kvSnapshots
            ) { viewModel.updateKvSnapshots(it) }
            } // CPU · NPU group
            Spacer(Modifier.height(10.dp))

            // ------------------------------------------------------------ expert cache & RAM
            PerformanceGroup("Expert cache & RAM", "Cache size, Auto growth, droppable share, dense weights, mapping") {
            Hint("Fixed when a model opens: press Reload after changing them. Only the Auto cache moves while the model runs.")
            Spacer(Modifier.height(12.dp))
            ChoiceSetting(
                "Expert cache (MiB)",
                Choices.CACHE.map { it to (if (it == 0) "off" else "$it MiB") },
                selected = settings.cacheMb, enabled = stream
            ) { viewModel.updateCacheMb(it) }
            Hint("A resident expert costs no read, so a bigger cache means less waiting on flash, paid for in RAM. With Auto this is the minimum the cache grows from. A model's card can set its own. Speed tip: every reply shows its cache hit rate (\"cache NN%\") in its stats line; a low rate means flash is what slows you down.")
            if (settings.cacheMb in 1 until 1500) {
                SwitchRow(
                    "Allow a small expert cache (<1500 MiB)",
                    "Small caches may thrash and reduce performance. Enabled by default to preserve BigMoeOnEdge+'s existing behavior when one of the small cache sizes is selected.",
                    settings.forceCache,
                    enabled = stream
                ) { viewModel.updateForceCache(it) }
            }
            SwitchRow(
                "Auto: grow while RAM is free",
                "The model opens with the cache above. While more than the reserve below (plus the headroom) is free and none of the app lands in zram, the cache grows in steps (once it is full; step and pace are set below). When free RAM falls under the reserve or the app's zram grows, it shrinks again, also during an answer, never under the cache above. Everything it grew by is offered to Android: starting another app makes the system drop that part at once instead of compressing or closing apps.",
                settings.cacheGrow, enabled = cacheOn
            ) { viewModel.updateCacheGrow(it) }
            if (settings.cacheGrow) {
                OutlinedTextField(
                    value = cacheFloorText,
                    onValueChange = { cacheFloorText = it.filter(Char::isDigit) },
                    label = { Text("Auto: keep this much RAM free (MiB)") },
                    singleLine = true,
                    enabled = cacheOn,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                ChoiceSetting(
                    "Auto: growth step",
                    listOf(50, 100, 150, 250, 500).map { it to "$it MiB" },
                    selected = settings.cacheGrowStepMb, enabled = cacheOn
                ) { viewModel.updateCacheGrowStepMb(it) }
                ChoiceSetting(
                    "Auto: time between growth steps",
                    listOf(2, 4, 6, 8, 15).map { it to "$it s" },
                    selected = settings.cacheGrowEverySec, enabled = cacheOn
                ) { viewModel.updateCacheGrowEverySec(it) }
                ChoiceSetting(
                    "Auto: free RAM needed above the reserve to grow",
                    listOf(150, 300, 500, 750, 1000).map { it to "$it MiB" },
                    selected = settings.cacheGrowHeadroomMb, enabled = cacheOn
                ) { viewModel.updateCacheGrowHeadroomMb(it) }
                ChoiceSetting(
                    "Auto: least a shrink takes back",
                    listOf(100, 250, 500, 750).map { it to "$it MiB" },
                    selected = settings.cacheShrinkMinMb, enabled = cacheOn
                ) { viewModel.updateCacheShrinkMinMb(it) }
                ChoiceSetting(
                    "Auto: pause after a shrink",
                    listOf(5, 15, 30, 60).map { it to "$it s" },
                    selected = settings.cacheQuietSec, enabled = cacheOn
                ) { viewModel.updateCacheQuietSec(it) }
                Hint("These five apply at once, no reload. Faster growth (small step, short pace, low headroom) follows free RAM closely but moves memory more often; slower growth is calmer. A shrink takes back at least the amount above, more if free RAM is further under the reserve. The pause keeps the cache from growing right back into the pressure. The sampling pace is 2 s, so nothing reacts faster than that.")
            }
            ChoiceSetting(
                "Cold experts the system may drop",
                Choices.DISCARD.map { it to if (it == 0) "off" else "coldest $it%" },
                selected = settings.cacheDiscardPct, enabled = cacheOn
            ) { viewModel.updateCacheDiscardPct(it) }
            Hint("This share of the cache, the experts unused the longest, is offered to Android (MADV_FREE). The app does not decide when: the kernel takes offered pages when free RAM falls under its own thresholds, before it compresses anything into zram or closes an app, and for free. A dropped expert is read from flash again when needed. The rest is ordinary memory: Android can only move it into zram. The cache shrinks with what the kernel takes and grows back once it stops, but never under the share that is not offered. With Auto the share counts on the minimum; what Auto grew by is always offered. Off = ordinary memory.")
            ChoiceSetting(
                "Dense weights",
                DenseWeights.values().map { it to denseLabel(it) },
                settings.denseWeights, enabled = stream
            ) { viewModel.updateDenseWeights(it) }
            Hint(denseBlurb(settings.denseWeights))
            SwitchRow(
                "Stream row-gathered tables",
                "A dense table the model only reads a few ROWS from per token (the token embedding) does not need to be in RAM: only the rows are read, from flash. Lossless - the output is identical. On a model where no table qualifies this does nothing. Not used while NPU prefill is on for the model (the two do not combine): a model's card can switch its NPU off.",
                settings.rowStream, enabled = stream
            ) { viewModel.updateRowStream(it) }
            ChoiceSetting(
                "Row-stream resident window",
                listOf(16 to "16 MiB", 32 to "32 MiB", 64 to "64 MiB", 128 to "128 MiB", 256 to "256 MiB"),
                settings.rowStreamMb,
                enabled = stream && settings.rowStream
            ) { viewModel.updateRowStreamMb(it) }
            Hint("RAM window used to keep recently accessed rows. Larger windows reduce repeated reads but leave less RAM for experts and the OS.")
            SwitchRow(
                "Release the model mapping",
                "Once every weight has been copied into the app's own memory, the model file does not need to stay mapped. Lossless - the output is identical. Needs Dense weights on Anon or Pinned. Leave it off for a model with a lazily read n-gram table (Qwen3.8 Flash Next): that table is read through the mapping.",
                settings.releaseMmap,
                enabled = stream && (settings.denseWeights == DenseWeights.ANONYMOUS || settings.denseWeights == DenseWeights.PINNED)
            ) { viewModel.updateReleaseMmap(it) }
            Divider()
            } // expert cache & RAM group
            Spacer(Modifier.height(10.dp))

            // ------------------------------------------------------------ streaming (engine)
            PerformanceGroup("MoE streaming & I/O", "Flash reads, I/O lanes and overlap") {
            Hint("Engine settings are fixed when a model opens. After changing them, press Reload. MoE expert streaming is selected automatically for supported MoE models; dense models use the standard load path.")
            Spacer(Modifier.height(12.dp))
            ChoiceSetting("Parallel I/O lanes", Choices.IO.map { it to it.toString() }, settings.ioThreads, enabled = stream) {
                viewModel.updateIoThreads(it)
            }
            Hint("Expert reads in flight at once. Helps only until the flash saturates. Speed tip: Auto settings tests every value from 1 to 8 on your device and picks the fastest.")
            SwitchRow(
                "Direct I/O (O_DIRECT)",
                "Bypass the page cache, so the system keeps no second copy of what the cache already holds. Falls back where unsupported.",
                settings.oDirect, enabled = stream
            ) { viewModel.updateODirect(it) }
            SwitchRow(
                "I/O and compute overlap",
                "Enabled by default. Issue expert reads asynchronously while the CPU computes the current layer, hiding flash latency. It only runs for streamed MoE models in an APK built with the expert-ready hook." +
                    if (readyInfo != null && !readyInfo.overlapActive && settings.overlap && stream) " The loaded model reports overlap is not active." else "",
                settings.overlap, enabled = stream
            ) { viewModel.updateOverlap(it) }
            SwitchRow(
                "Start reads in two waves (experimental)",
                "Publishes the first projection's expert reads before queuing the remaining projections, allowing I/O lanes to start earlier. Experimental; requires expert streaming, cache, and overlap. Leave off if it is slower on your device.",
                settings.ioTwoWave,
                enabled = stream && cacheOn && settings.overlap
            ) { viewModel.updateIoTwoWave(it) }
            Divider()
            } // streaming group
            Spacer(Modifier.height(10.dp))

            // ------------------------------------------------------------ speed / quality
            PerformanceGroup("Quality & speed", "Active experts, Guess ahead, prefetch and routing") {
            ChoiceSetting(
                "Drop cold experts (% of even share)",
                Choices.DROP_COLD.map {
                    it to when (it) {
                        0 -> "off"
                        50 -> "50% (barely bites)"
                        75 -> "75% (recommended)"
                        100 -> "100% (fastest, roughest)"
                        else -> "$it%"
                    }
                },
                settings.dropColdPct, enabled = cacheOn
            ) { viewModel.updateDropColdPct(it) }
            Hint("Skips a routed expert only when it is a cache miss and the router barely wanted it, so quality is spent only where it buys a read. A resident expert always runs and the top one is never dropped. Changes the reply, and not the same way twice: it depends on what the cache held. Speed tip: for a big model that streams a lot from flash this is a strong lever, because reads dominate. Try 50% first, then 75%, and check the answers.")
            val topkNow = readyInfo?.nExpertUsed ?: 0
            if (settings.dropColdPct > 0 && topkNow in 1..4) {
                Text(
                    "This model routes very few experts per token, so the same share covers much more of the reply. Check the answers, or turn this off.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error
                )
            }
            ChoiceSetting("Active experts (top-k)", Choices.TOPK.map { it to if (it == 0) "model default" else "$it" }, settings.nExpertUsed) {
                viewModel.updateNExpertUsed(it)
            }
            Hint("Consult fewer experts per token than the model asks for. Cuts compute and reads together, and changes the reply. Speed tip: fewer experts means fewer flash reads per token. Lower it one step at a time (a model that routes 10 can try 6) and compare the answers with the model default.")

            Spacer(Modifier.height(8.dp))
            GuessAheadSettings(settings, viewModel)

            Spacer(Modifier.height(8.dp))
                Section("Prefetch and routing (experimental)")
                Hint("Guess ahead (MTP and n-gram): the mode per model on its card (Models page), the tuning above.")
                ChoiceSetting(
                    "Temporal prefetch (layers) (experimental)",
                    Choices.PREFETCH.map { it to if (it == 0) "off" else "$it" },
                    settings.prefetchLayers,
                    enabled = stream && cacheOn && !settings.predictPrefetch && settings.routeAhead == 0
                ) { viewModel.updatePrefetchLayers(it) }
                Hint("Bets a layer reuses the previous token's experts and reads them on idle lanes. Needs the cache.")
                SwitchRow(
                    "Predictive prefetch (experimental)",
                    "Runs the next layer's own router a layer early and prefetches what it names. More accurate than the bet above, and replaces it. Needs the cache.",
                    settings.predictPrefetch,
                    enabled = stream && cacheOn && settings.prefetchLayers == 0 && settings.routeAhead == 0
                ) { viewModel.updatePredictPrefetch(it) }
                if (settings.predictPrefetch) {
                    ChoiceSetting(
                        "Predicted misses to read ahead (experimental)",
                        Choices.PREDICT_SPEC.map { it to if (it == 0) "retention only" else "$it" },
                        settings.predictSpecMax, enabled = stream && cacheOn
                    ) { viewModel.updatePredictSpecMax(it) }
                    Hint("Retention only reads nothing and just protects what the prediction names, which is the safe setting.")
                }
                ChoiceSetting(
                    "Prefer cached experts (% of score range) (experimental)",
                    Choices.SUBSTITUTE.map { it to if (it == 0) "off" else "$it%" },
                    settings.substitutePct, enabled = stream && cacheOn
                ) { viewModel.updateSubstitutePct(it) }
                Hint("When two experts score close, picks the one already in RAM. Same number of experts, fewer flash reads, faster decode. Changes the reply; 15% is the measured sweet spot. Speed tip: it raises the cache hit rate without reading fewer experts, so try 10-15% before Drop cold experts or a lower top-k.")
                if (settings.substitutePct >= 20) {
                    Text(
                        "Past 15% the model degrades faster than its replies show. Judge it on answers you can check.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error
                    )
                }
                ChoiceSetting(
                    "Route-ahead (layers) (experimental)",
                    Choices.ROUTE_AHEAD.map { it to if (it == 0) "off" else "$it" },
                    settings.routeAhead,
                    enabled = stream && settings.prefetchLayers == 0 && !settings.predictPrefetch && settings.spec == SpecSource.OFF
                ) { viewModel.updateRouteAhead(it) }
                Hint("Commits each layer's routing that many layers early, so its reads start early and can never be wasted. Lossy: some slots route differently. Excludes the prefetchers and Guess ahead.")
            Divider()
            } // speed / quality group
            Spacer(Modifier.height(10.dp))

            // ------------------------------------------------------------ decoding (sampling)
            PerformanceGroup("Decoding", "Temperature, Top P/K, Min P, penalties and seed") {
            Spacer(Modifier.height(8.dp))
            Text("Preset")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.bigmoe.onedge.core.SamplingPreset.values().forEach { preset ->
                    OutlinedButton(onClick = {
                        temp = preset.temperature; topP = preset.topP; topK = preset.topK.toFloat(); minP = preset.minP
                        repeatPenalty = preset.repeatPenalty; repeatLastN = preset.repeatLastN.toFloat()
                        frequencyPenalty = preset.frequencyPenalty; presencePenalty = preset.presencePenalty
                        viewModel.applySamplingPreset(preset)
                    }) { Text(preset.label) }
                }
            }
            Hint("Fills the values below with a preset; adjust them afterwards as you like. A model can have its own preset on its card (Models page), which then replaces these values for that model.")
            Text("Temperature: ${"%.2f".format(java.util.Locale.US, temp)}")
            Slider(value = temp, onValueChange = { temp = it }, onValueChangeFinished = { viewModel.updateTemperature(temp) }, valueRange = 0f..2f)
            Hint("How random the choice of the next token is. 0 = always the most likely token (greedy, repeatable, good for code and tool calls). 0.6 to 0.8 = natural chat. Above 1 = more surprising, but more mistakes. Top P, Top K and Min P only matter above 0.")
            Text("Top P: ${"%.2f".format(java.util.Locale.US, topP)}")
            Slider(value = topP, onValueChange = { topP = it }, onValueChangeFinished = { viewModel.updateTopP(topP) }, valueRange = 0.05f..1f)
            Hint("Keeps only the most likely tokens whose probabilities add up to this share, the rest is never picked. 0.9 = the top 90 % of the probability. 1 = off. Lower = safer, more predictable wording.")
            Text("Top K: ${topK.toInt()}")
            Slider(value = topK, onValueChange = { topK = it }, onValueChangeFinished = { viewModel.updateTopK(topK.toInt()) }, valueRange = 0f..100f)
            Hint("Keeps only the K most likely tokens before drawing. 40 is common. 0 = off (no limit). A hard cap; Top P and Min P adapt to how sure the model is.")
            Text("Min P: ${if (minP <= 0f) "off" else "%.2f".format(java.util.Locale.US, minP)}")
            Slider(value = minP, onValueChange = { minP = Math.round(it * 100f) / 100f }, onValueChangeFinished = { viewModel.updateMinP(minP) }, valueRange = 0f..0.5f)
            Hint("Drops every token that is less likely than this fraction of the best token. 0.05 = a token needs at least 5 % of the favourite's probability. Cuts the nonsense tail when the model is sure, keeps choice when it is not. 0 = off.")
            Text("Repetition penalty: ${if (repeatPenalty == 1f) "off" else "%.2f".format(java.util.Locale.US, repeatPenalty)}")
            Slider(value = repeatPenalty, onValueChange = { repeatPenalty = Math.round(it * 20f) / 20f }, onValueChangeFinished = { viewModel.updateRepeatPenalty(repeatPenalty) }, valueRange = 1f..1.5f)
            Hint("Makes every token that already appeared in the repeat window less likely, by this factor. Against loops (the same sentence over and over). 1 = off, 1.05 to 1.15 is typical; higher bends wording and can break code, names and JSON.")
            Text("Repeat window: ${if (repeatLastN <= 0f) "off" else "${repeatLastN.toInt()} tokens"}")
            Slider(value = repeatLastN, onValueChange = { repeatLastN = (Math.round(it / 16f) * 16f).coerceIn(0f, 1024f) }, onValueChangeFinished = { viewModel.updateRepeatLastN(repeatLastN.toInt()) }, valueRange = 0f..1024f)
            Hint("How many of the most recent tokens the three penalties look at. 64 = roughly the last sentence or two. 0 = all penalties off.")
            Text("Frequency penalty: ${if (frequencyPenalty == 0f) "off" else "%.2f".format(java.util.Locale.US, frequencyPenalty)}")
            Slider(value = frequencyPenalty, onValueChange = { frequencyPenalty = Math.round(it * 20f) / 20f }, onValueChangeFinished = { viewModel.updateFrequencyPenalty(frequencyPenalty) }, valueRange = 0f..1.5f)
            Hint("Lowers a token more the more often it appeared in the window (each repeat counts). Against overused words. 0 = off, 0.1 to 0.5 is gentle.")
            Text("Presence penalty: ${if (presencePenalty == 0f) "off" else "%.2f".format(java.util.Locale.US, presencePenalty)}")
            Slider(value = presencePenalty, onValueChange = { presencePenalty = Math.round(it * 20f) / 20f }, onValueChangeFinished = { viewModel.updatePresencePenalty(presencePenalty) }, valueRange = 0f..1.5f)
            Hint("Lowers a token by a fixed amount once it appeared at all in the window, however often. Nudges towards new words and topics. 0 = off, 0.1 to 0.5 is gentle.")
            OutlinedTextField(
                value = seedText,
                onValueChange = { input -> seedText = input.filterIndexed { i, c -> c.isDigit() || (i == 0 && c == '-') } },
                label = { Text("Sampling seed (-1 = random)") },
                supportingText = { Text("Use -1 for a fresh random seed per run, or a non-negative number to make runs repeatable. Sampling changes apply to the next message, no reload needed.") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth()
            )
            Hint("Order in the engine: penalties, then Top K, Top P, Min P, then temperature and the draw. Sampling is sent with every chat message, so changes need no reload. The penalties also work at temperature 0, but the engine leaves them out while the model runs with Guess ahead.")
            Divider()
            } // compute group

        } else {
            Divider()
        }

        SettingsFoldout(
            title = "Tools, agent, web search, pictures & videos",
            subtitle = "Multimodal input, tool permissions, agent and Termux, online search",
            expanded = toolsMediaExpanded,
            onToggle = { toolsMediaExpanded = !toolsMediaExpanded }
        )
        androidx.compose.animation.AnimatedVisibility(visible = toolsMediaExpanded, enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(), exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SettingsFoldout(
            title = "Pictures & video", subtitle = "Image dimensions and sampled video frames", expanded = mediaExpanded,
            onToggle = { mediaExpanded = !mediaExpanded }
        )
        androidx.compose.animation.AnimatedVisibility(visible = mediaExpanded, enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(), exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // ------------------------------------------------------------ pictures/video
            ChoiceSetting(
                "Picture max dimension",
                Choices.IMAGE_DIM.map { it to "$it px" },
                settings.imageMaxDim
            ) { viewModel.updateImageMaxDim(it) }
            Hint("Higher dimensions preserve more detail but increase vision token and memory use. The active model must have an mmproj.")
            ChoiceSetting(
                "Video sampled frames",
                Choices.VIDEO_FRAMES.map { it to it.toString() },
                settings.videoFrames
            ) { viewModel.updateVideoFrames(it) }
            Hint("Frames are sampled evenly from the attached video. Images/video stay disabled in the chat until a vision projector is loaded.")
            Divider()

            }
        }
        Spacer(Modifier.height(8.dp))

        SettingsFoldout(
            title = "Tools", subtitle = "Permissions and tool-result behaviour", expanded = toolsExpanded,
            onToggle = { toolsExpanded = !toolsExpanded }
        )
        androidx.compose.animation.AnimatedVisibility(visible = toolsExpanded, enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(), exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // ------------------------------------------------------------ tool access
            Hint("Defaults for NEW chats. Every chat has its own switches under the wrench button in the chat; changing them there does not change these. What the AI may use on its own when it needs it: anything that changes or deletes a file, and every Termux command, asks you first unless Approval below is set to allow everything.")
            SwitchRow(
                "Files",
                "Create folders and files, read, search, edit, rename, delete, zip. Everything stays in this chat's files.",
                settings.toolsFilesEnabled
            ) { viewModel.updateToolsFilesEnabled(it) }
            SwitchRow(
                "Code",
                "Search and analyse source files, and run JavaScript in an offline sandbox to compute or test.",
                settings.toolsCodeEnabled
            ) { viewModel.updateToolsCodeEnabled(it) }
            SwitchRow(
                "Utilities",
                "Offline helpers: date and time, calculator, unit converter, random numbers, battery / storage / memory of this phone.",
                settings.toolsUtilitiesEnabled
            ) { viewModel.updateToolsUtilitiesEnabled(it) }
            SwitchRow(
                "Help",
                "Lets the model look up how this app and its engine work (settings, expert cache, tools, NPU, API server) in the documentation, change log and setting texts bundled in the app, offline. Ask it things like \"how does the cold experts drop work?\". The model searches with English keywords and answers in your language; it works best with larger models that call tools reliably.",
                settings.toolsHelpEnabled
            ) { viewModel.updateToolsHelpEnabled(it) }
            SwitchRow(
                "Wiki",
                "Your own knowledge: import .md or .txt files or a .zip (a Notion export, notes, how-tos) into BigMoe/wiki below, or copy them there yourself (subfolders are fine), and the model searches them offline, before any web search. German and English both work. Only the best four passages reach the model, so a big folder costs no extra prompt time; keep it focused, though, unrelated files crowd out the right passages.",
                settings.toolsWikiEnabled
            ) { viewModel.updateToolsWikiEnabled(it) }
            if (settings.toolsWikiEnabled) WikiFolderInfo()
            SwitchRow(
                "Ask user",
                "Lets the model ask you a question in the middle of an answer when it needs your decision (which way, which file, which style): a card with 2 to 4 answer buttons and a field for your own answer. The answer waits for you; Dismiss lets the model go on without it. While it is on, the web is never used for a message before you were asked at least once: if the model has not asked anything yet, the app asks whether it may search or open the page. Also under the wrench button in the chat.",
                settings.toolsAskUserEnabled
            ) { viewModel.updateToolsAskUserEnabled(it) }
            SwitchRow(
                "Native UI components",
                "Lets the AI answer with compact in-chat visuals when your request calls for one (compare, pros and cons, timeline, steps, key numbers, slides): comparison tables, navigable slide cards, lists, metrics, badges, progress bars, timelines, expandable sections, selectable cards and action buttons. Rendered directly with Jetpack Compose from a small JSON block — no HTML, CSS, JavaScript or WebView. Off by default: it adds about 450 tokens to the system prompt. Also under the wrench button in the chat.",
                settings.interactiveUiEnabled
            ) { viewModel.updateInteractiveUiEnabled(it) }
            SwitchRow(
                "External tools",
                "Lets the AI call the HTTP tools and MCP servers you add below. Each call can ask you first. Off by default. Also under the wrench button in the chat.",
                settings.toolsExternalEnabled
            ) { viewModel.updateToolsExternalEnabled(it) }
            ExternalToolsSection(settings.externalToolsJson) { viewModel.updateExternalToolsJson(it) }
            Hint("This setting uses native Android UI components only. It does not generate or execute HTML/CSS/JavaScript. Interactive Artifacts are a separate feature for standalone HTML, SVG or Mermaid previews.")
            ChoiceSetting(
                "Longest tool result",
                listOf(
                    1500 to "1,500 characters (fastest)",
                    2500 to "2,500 characters",
                    3500 to "3,500 characters (default)",
                    6000 to "6,000 characters (slowest)"
                ),
                selected = settings.toolResultChars
            ) { viewModel.updateToolResultChars(it) }
            Hint(
                "The model reads everything a tool returns before it can continue, at the speed of prompt processing " +
                    "(tens of tokens per second on a phone). A shorter limit makes each tool round much faster. " +
                    "When a result is cut, the model is told how much was left so it can ask for the rest."
            )
            SwitchRow(
                "Think after tool results",
                "Off (default): once the model has a tool result it answers directly, which saves minutes on a slow device. " +
                    "On: it reasons again after every result.",
                settings.thinkAfterTools
            ) { viewModel.updateThinkAfterTools(it) }
            Hint("Web search, image search, and web-page reading are controlled by the globe button in the chat. These tools are off by default.")
            Divider()

            }
        }
        Spacer(Modifier.height(8.dp))

        SettingsFoldout(
            title = "Agent & Termux", subtitle = "Tool rounds, approval, grammar-checked calls, Termux commands", expanded = agentExpanded,
            onToggle = { agentExpanded = !agentExpanded }
        )
        androidx.compose.animation.AnimatedVisibility(visible = agentExpanded, enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(), exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            AgentAndTermuxSection(settings = settings, viewModel = viewModel)
            Divider()

            }
        }
        Spacer(Modifier.height(8.dp))

        SettingsFoldout(
            title = "Web search", subtitle = "Search provider and automatic-search behaviour", expanded = searchExpanded,
            onToggle = { searchExpanded = !searchExpanded }
        )
        androidx.compose.animation.AnimatedVisibility(visible = searchExpanded, enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(), exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // ------------------------------------------------------------ web search
            ChoiceSetting(
                "When to search",
                listOf(
                    WebSearchMode.ALWAYS to "Every message (most reliable)",
                    WebSearchMode.AUTO to "Only when the model asks for it"
                ),
                selected = settings.webSearchMode
            ) { viewModel.updateWebSearchMode(it) }
            Hint("Web search itself is switched on and off with the globe button in the chat.")
            SwitchRow(
                "Allow image search",
                "Lets the model use image_search while web search is enabled. Turn off to keep image-search calls out of the model's available tools.",
                settings.imageSearchEnabled
            ) { viewModel.updateImageSearchEnabled(it) }
            SwitchRow(
                "Search images after web search",
                "After the app finishes a web search, automatically fetch a few related images and include their preview cards in the conversation. Adds network time and tool-result tokens; requires Allow image search.",
                settings.autoImageSearchAfterWebSearch,
                enabled = settings.imageSearchEnabled
            ) { viewModel.updateAutoImageSearchAfterWebSearch(it) }
            Hint(
                if (settings.webSearchMode == WebSearchMode.ALWAYS)
                    "Every message you send is searched first; the results are given to the model together with your message. Works with any model."
                else
                    "The model is told it may search and decides by itself. Small models often never do; switch to \"Every message\" if nothing happens."
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = braveKey,
                onValueChange = { braveKey = it },
                label = { Text("Brave Search API key (optional)") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = searxUrl,
                onValueChange = { searxUrl = it },
                label = { Text("SearXNG URL (optional)") },
                placeholder = { Text("https://searx.example.org") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )
            Hint("No key needed: without a Brave key or SearXNG address the app uses DuckDuckGo, then Wikipedia. A SearXNG address must be https:// and allow the JSON format.")
            Divider()

            }
        }
        Spacer(Modifier.height(8.dp))

            }
        }
        Spacer(Modifier.height(8.dp))

        SettingsFoldout(
            title = "Diagnostics", subtitle = "Auto settings, performance log, metrics, log, local API server", expanded = workspaceExpanded,
            onToggle = { workspaceExpanded = !workspaceExpanded }
        )
        androidx.compose.animation.AnimatedVisibility(visible = workspaceExpanded, enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(), exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // ------------------------------------------------------------ auto settings, performance log
            AutoSettingsEntry(onClick = onOpenAutoSettings)
            Spacer(Modifier.height(8.dp))
            AutoSettingsEntry(
                onClick = onOpenPerfLog,
                title = "Performance log",
                text = "Every load, answer and crash with its settings, speed, RAM and heat: summary, export for Claude, and a model's settings advice"
            )
            Divider()

            // ------------------------------------------------------------ diagnostics
            Section("Diagnostics")
            Hint("Log: what llama.cpp and the engine print while loading and generating (timings, cache, I/O, errors). Off until you switch \"Record log\" on there. Filter it, copy it or share it as a file.")
            OutlinedButton(onClick = onOpenLog) { Text("Open log") }
            Divider()

            Section("Capabilities")
            Hint("Implemented: file attachments and reading, workspace/artifacts, file/code/utility/web tools, image/video attachment plumbing with mmproj support, thinking and web toggles. Voice input is not part of this project.")
            Divider()

            // ------------------------------------------------------------ local API server (last, collapsed by default)
            ApiServerSection(settings = settings, readyInfo = readyInfo, viewModel = viewModel)
            }
        }
        Spacer(Modifier.height(8.dp))

        Spacer(Modifier.height(24.dp))
    }

    editingPalette?.let { palette ->
        PaletteEditorDialog(
            initial = palette,
            onSave = { viewModel.saveCustomPalette(it); editingPalette = null },
            onDelete = if (editingIsNew) null else ({ viewModel.deleteCustomPalette(palette.id); editingPalette = null }),
            onDismiss = { editingPalette = null }
        )
    }
}

@Composable
private fun PerformanceGroup(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit
) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        base = MaterialTheme.colorScheme.surfaceContainerLow,
        outline = true
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = {
                Text(t(title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(t(subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(2.dp))
                content()
            }
        )
    }
}

/** Export all settings to a file and import them again (paths and API keys stay out of the file). */
@Composable
private fun SettingsBackupRow(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val message by viewModel.backupMessage.collectAsState()
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) viewModel.exportSettings(context.contentResolver, uri) }
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.importSettings(context.contentResolver, uri) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Settings file", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { exportLauncher.launch("bigmoeonedge2-settings.json") }) { Text("Export") }
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("Import") }
        }
        Text(
            "Saves every setting to a file, or reads one back (also from another device). The file has no model paths and no API keys.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        message?.let { m ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = viewModel::dismissBackupMessage) { Text("OK") }
            }
        }
    }
}

@Composable
private fun AutoSettingsEntry(
    onClick: () -> Unit,
    title: String = "Auto settings",
    text: String = "Measures the best threads, expert cache, I/O lanes, prompt batch width and NPU settings for this device with a model you pick"
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "Open $title",
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun SettingsFoldout(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    val styled = AppTheme.visualStyle != AppVisualStyle.SOLID
    Surface(
        modifier = Modifier.fillMaxWidth()
            .then(if (styled) appSurfaceModifier(shape, MaterialTheme.colorScheme.surfaceContainerLow) else Modifier)
            .clickable(onClick = onToggle),
        shape = shape,
        color = if (styled) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (styled) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(t(title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(t(subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse performance settings" else "Expand performance settings",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}


@Composable
internal fun Section(text: String) {
    Text(text = t(text), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(8.dp))
}

@Composable
internal fun Hint(text: String) {
    Text(text = t(text), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun Divider() {
    Spacer(Modifier.height(20.dp))
    HorizontalDivider()
    Spacer(Modifier.height(20.dp))
}

@Composable
internal fun SwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(t(label), style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            Hint(description)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
internal fun <T> ChoiceSetting(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    enabled: Boolean = true,
    onSelect: (T) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == selected }?.second?.let { t(it) } ?: selected.toString()
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            t(label), style = MaterialTheme.typography.labelMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
        androidx.compose.foundation.layout.Box {
            OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(current, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (value, text) ->
                    DropdownMenuItem(text = { Text(t(text)) }, onClick = { open = false; onSelect(value) })
                }
            }
        }
    }
}
