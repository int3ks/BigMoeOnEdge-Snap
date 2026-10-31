package com.bigmoe.onedge.ui.chat

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bigmoe.onedge.core.EngineState
import com.bigmoe.onedge.ui.history.HistoryViewModel
import com.bigmoe.onedge.ui.home.ChatHistoryPanel
import com.bigmoe.onedge.ui.models.ModelPickerButton
import com.bigmoe.onedge.ui.models.ModelViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import com.bigmoe.onedge.data.local.entity.MessageRole
import com.bigmoe.onedge.workspace.FileKinds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    historyViewModel: HistoryViewModel,
    onNewChat: () -> Unit,
    onOpenChat: (String) -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToModels: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onOpenFile: (String) -> Unit,
    modelViewModel: ModelViewModel,
    wideMode: Boolean = false,
    historyPaneVisible: Boolean = true,
    onToggleHistoryPane: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val latestUiState = rememberUpdatedState(uiState)
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    // Copies [text] verbatim and says so.
    val copyToClipboard: (String) -> Unit = { text ->
        clipboard.setText(AnnotatedString(text))
        android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.flushDraft() }
    }
    val listState = rememberLazyListState()
    val chatsDrawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    var showFilesSheet by remember { mutableStateOf(false) }
    // Editing a sent message happens in the input bar (not in a dialog: there the text field's copy/paste menu did not
    // show on some devices). The unsent draft stays untouched and comes back after the edit.
    var editingMessage by remember { mutableStateOf<ChatMessageUiModel?>(null) }
    var editedText by remember { mutableStateOf("") }
    LaunchedEffect(uiState.currentSession?.id) { editingMessage = null } // another chat: no edit carries over

    // The live bubble shows while a response is in progress: reasoning-only counts too (a reasoning model
    // can think for a while before the first answer token), and so does a running tool.
    val showLiveBubble = uiState.isGenerating
    val itemCount = uiState.messages.size + if (showLiveBubble) 1 else 0

    // Build web-source associations once per message-list update. Doing indexOfFirst/subList
    // inside each lazy row made source discovery quadratic and repeated it during recomposition.
    val sourcesByAssistantId = remember(uiState.messages) {
        val result = HashMap<String, List<String>>()
        var currentTurnSources = linkedSetOf<String>()
        uiState.messages.forEach { item ->
            when (item.role) {
                MessageRole.USER -> currentTurnSources = linkedSetOf()
                MessageRole.TOOL -> currentTurnSources.addAll(extractWebSources(item.text).map { it.first })
                MessageRole.ASSISTANT -> result[item.id] = currentTurnSources.toList()
                MessageRole.SYSTEM, MessageRole.CONTEXT -> Unit
            }
        }
        result
    }

    // "Follow" = the list sticks to the newest text while the model writes. The moment the person drags the list
    // up to read something, following stops (the model keeps writing below); the arrow button or reaching the
    // bottom again turns it back on. Programmatic scrolling never goes through nested scroll, so only real
    // finger movement switches it off.
    var follow by remember { mutableStateOf(true) }
    val atBottom by remember { derivedStateOf { !listState.canScrollForward } }
    val stopFollowing = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // finger moving down = the content moves down = the person is looking at EARLIER text
                if (available.y > 0f) follow = false
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(atBottom) {
        if (atBottom) follow = true
    }

    // Coalesce bursty token/reasoning updates into a bounded-rate autoscroll. Scrolling to an
    // enormous offset for every token made the UI do unnecessary layout work while generation ran.
    LaunchedEffect(listState, follow, itemCount) {
        if (follow && itemCount > 0) {
            snapshotFlow {
                    val latest = latestUiState.value
                    Triple(latest.messages.size, latest.liveText.length, latest.liveReasoning.length)
                }
                .conflate()
                .collect {
                    if (follow && itemCount > 0) {
                        listState.scrollToItem(itemCount - 1)
                        listState.scrollBy(1_000_000f) // a tall last item: show its END, not its top
                    }
                    delay(32) // at most about 30 follow-scroll passes per second
                }
        }
    }

    ModalNavigationDrawer(
        modifier = modifier,
        drawerState = chatsDrawerState,
        gesturesEnabled = !wideMode,
        drawerContent = {
            ModalDrawerSheet {
                ChatHistoryPanel(
                    viewModel = historyViewModel,
                    selectedSessionId = uiState.currentSession?.id,
                    onNewChat = {
                        drawerScope.launch {
                            chatsDrawerState.close()
                            onNewChat()
                        }
                    },
                    onOpenChat = { id ->
                        drawerScope.launch {
                            chatsDrawerState.close()
                            onOpenChat(id)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    ) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(uiState.currentSession?.title ?: "New chat", maxLines = 1)
                        // One model: the loaded one (it answers the next message), else the last one this chat used.
                        val modelName = (uiState.engineState as? EngineState.Ready)?.info?.name
                            ?: uiState.currentSession?.modelNames?.lastOrNull()
                        if (modelName != null) {
                            Text(
                                text = modelName,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (!wideMode) {
                        IconButton(onClick = { drawerScope.launch { chatsDrawerState.open() } }) {
                            Icon(imageVector = Icons.Default.Menu, contentDescription = "Open chats")
                        }
                    } else {
                        IconButton(onClick = onToggleHistoryPane) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = if (historyPaneVisible) "Hide chat history pane" else "Show chat history pane"
                            )
                        }
                    }
                },
                actions = {
                    // Always-visible escape hatch back to the main Chats/Home page.
                    // This is especially useful on tablets where the chat is a permanent split pane.
                    IconButton(onClick = onNavigateHome) {
                        Icon(imageVector = Icons.Default.Home, contentDescription = "Home")
                    }
                    IconButton(onClick = { showFilesSheet = true }) {
                        Icon(imageVector = Icons.Default.FolderOpen, contentDescription = "Chat files")
                    }
                    // Opens a small list of your models (load / unload) instead of jumping to the Models page.
                    ModelPickerButton(
                        viewModel = modelViewModel,
                        isGenerating = uiState.isGenerating,
                        onManageModels = onNavigateToModels
                    )
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(imageVector = Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
        ) {
            EngineBanner(state = uiState.engineState, onOpenModels = onNavigateToModels)
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (uiState.messages.isEmpty() && !showLiveBubble) {
                    EmptyChatView(onSuggestionSelected = { prompt -> viewModel.sendMessage(prompt) })
                } else {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().nestedScroll(stopFollowing)) {
                        items(items = uiState.messages, key = { it.id }) { message ->
                            if (message.role == MessageRole.TOOL) {
                                if (ImageSearchResultParser.parse(message.text) != null) {
                                    ImageSearchResultsCard(body = message.text)
                                } else {
                                    ToolResultCard(body = message.text)
                                }
                            } else {
                                val messageAttachments = uiState.attachments.filter { it.messageId == message.id }
                                val turnTools = if (message.role == MessageRole.ASSISTANT) {
                                    sourcesByAssistantId[message.id].orEmpty()
                                } else emptyList()
                                ChatMessageBubble(
                                    text = message.text,
                                    isUser = message.role == MessageRole.USER,
                                    additionalSources = turnTools,
                                    reasoning = message.reasoning,
                                    toolCalls = message.toolCalls,
                                    artifacts = message.artifacts,
                                    onOpenArtifact = viewModel::openArtifact,
                                    stats = formatStats(
                                        message.tokensPerSecond,
                                        message.prefillMs,
                                        message.generatedTokens,
                                        message.cacheHitPct,
                                        message.generationElapsedMs,
                                        message.contextUsed,
                                        message.contextTotal
                                    ),
                                    note = message.note,
                                    onOpenCodeArtifact = viewModel::saveCodeAsArtifact,
                                    // Long-press: user message -> Edit + Copy, assistant message -> Copy.
                                    onEdit = if (message.role == MessageRole.USER && !uiState.isGenerating) {
                                        { editingMessage = message; editedText = message.text }
                                    } else null,
                                    // Long-press / right-click: answer this turn again without editing it first.
                                    onRegenerate = if ((message.role == MessageRole.USER || message.role == MessageRole.ASSISTANT) && !uiState.isGenerating) {
                                        { follow = true; viewModel.regenerate(message.id) }
                                    } else null,
                                    // The complete message exactly as stored: no telemetry, labels or renderer fences.
                                    onCopy = if (message.rawContent.isNotEmpty()) {
                                        { copyToClipboard(message.rawContent) }
                                    } else null
                                )
                                if (message.role == MessageRole.USER && messageAttachments.isNotEmpty()) {
                                    AttachmentMessageChips(messageAttachments, onOpenFile)
                                }
                                if (message.role == MessageRole.ASSISTANT) {
                                    ArtifactMessageChips(uiState.artifacts.filter { it.messageId == message.id && it.language != FileKinds.FOLDER }, onOpenFile)
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                        }
                        if (showLiveBubble) {
                            item(key = "live_bubble") {
                                if (uiState.liveText.isEmpty() && uiState.liveReasoning.isEmpty()) {
                                    LiveStatusRow(
                                        uiState.liveStatus ?: "Reading…",
                                        if (uiState.pendingConfirmation != null) null else if (uiState.toolRunning) formatToolTelemetry(uiState.toolElapsedMs, uiState.toolLimitMs, uiState.toolQuietMs) else formatStats(
                                            uiState.liveTokensPerSecond, null, uiState.generationTokens, null,
                                            uiState.generationElapsedMs, uiState.contextUsed.takeIf { it > 0 }, uiState.contextTotal
                                        ),
                                        detail = uiState.liveDetail,
                                        onCancel = if (uiState.toolRunning) viewModel::cancelTool else null,
                                        output = uiState.liveOutput,
                                        cancelling = uiState.toolCancelling,
                                        approval = uiState.pendingConfirmation,
                                        onApprove = { viewModel.resolveConfirmation(it) }
                                    )
                                } else {
                                    ChatMessageBubble(
                                        text = uiState.liveText,
                                        isUser = false,
                                        isStreaming = uiState.liveText.isNotEmpty() || uiState.liveStatus == null,
                                        reasoning = uiState.liveReasoning.ifEmpty { null },
                                        isReasoningStreaming = uiState.liveText.isEmpty(),
                                        stats = formatStats(
                                            uiState.liveTokensPerSecond, null, uiState.generationTokens, null,
                                            uiState.generationElapsedMs, uiState.contextUsed.takeIf { it > 0 }, uiState.contextTotal
                                        ),
                                        onOpenCodeArtifact = viewModel::saveCodeAsArtifact
                                    )
                                    uiState.liveStatus?.let {
                                        LiveStatusRow(
                                            it,
                                            if (uiState.pendingConfirmation != null) null else if (uiState.toolRunning) formatToolTelemetry(uiState.toolElapsedMs, uiState.toolLimitMs, uiState.toolQuietMs) else formatStats(
                                                uiState.liveTokensPerSecond, null, uiState.generationTokens, null,
                                                uiState.generationElapsedMs, uiState.contextUsed.takeIf { it > 0 }, uiState.contextTotal
                                            ),
                                            detail = uiState.liveDetail,
                                            onCancel = if (uiState.toolRunning) viewModel::cancelTool else null,
                                            output = uiState.liveOutput,
                                            cancelling = uiState.toolCancelling,
                                            approval = uiState.pendingConfirmation,
                                            onApprove = { viewModel.resolveConfirmation(it) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (!follow && itemCount > 0) {
                    SmallFloatingActionButton(
                        onClick = { follow = true },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 12.dp)
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Jump to the newest text")
                    }
                }
            }

            uiState.notice?.let { NoticeBar(text = it, onDismiss = { viewModel.dismissNotice() }) }

            val editing = editingMessage
            if (editing != null) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp)
                ) {
                    Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Editing your message. Sending it replaces the replies after it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { editingMessage = null }) { Text("Cancel") }
                    }
                }
            }
            ChatInputBar(
                textValue = if (editing != null) editedText else uiState.inputDraft,
                onTextChanged = { if (editing != null) editedText = it else viewModel.setInputDraft(it) },
                onSendClicked = {
                    if (editing != null) {
                        if (editedText.isNotBlank() && !uiState.isGenerating) {
                            follow = true
                            viewModel.editUserMessage(editing.id, editedText)
                            editingMessage = null
                        }
                    } else if ((uiState.inputDraft.isNotBlank() || uiState.pendingAttachments.isNotEmpty()) && !uiState.isGenerating) {
                        follow = true
                        viewModel.sendMessage(uiState.inputDraft)
                    }
                },
                onStopClicked = { viewModel.stopGeneration() },
                isGenerating = uiState.isGenerating,
                isWebSearchEnabled = uiState.settings.isWebSearchEnabled,
                onToggleWebSearch = { viewModel.toggleWebSearch() },
                isThinkingEnabled = uiState.settings.thinkingEnabled,
                thinkingSwitchable = uiState.thinkingSwitchable,
                onToggleThinking = { viewModel.toggleThinking() },
                visionActive = uiState.visionActive,
                pendingAttachments = uiState.pendingAttachments,
                onAddAttachment = { uri, kind -> viewModel.addAttachment(context.contentResolver, uri, kind) },
                onRemoveAttachment = viewModel::removePendingAttachment,
                toolsFilesEnabled = uiState.settings.toolsFilesEnabled,
                toolsCodeEnabled = uiState.settings.toolsCodeEnabled,
                toolsUtilitiesEnabled = uiState.settings.toolsUtilitiesEnabled,
                toolsTermuxEnabled = uiState.settings.toolsTermuxEnabled,
                onToggleToolFiles = viewModel::toggleToolFiles,
                onToggleToolCode = viewModel::toggleToolCode,
                onToggleToolUtilities = viewModel::toggleToolUtilities,
                onToggleToolTermux = viewModel::toggleToolTermux,
                chooseFromOptions = uiState.chooseFromOptions,
                choiceOptions = uiState.choiceOptions,
                onToggleChooseFromOptions = viewModel::toggleChooseFromOptions,
                onChoiceOptionsChanged = viewModel::setChoiceOptions,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
    }

    LaunchedEffect(uiState.openFilePath) {
        uiState.openFilePath?.let { path ->
            onOpenFile(path)
            viewModel.consumeOpenFile()
        }
    }

    if (showFilesSheet) {
        ChatFilesSheet(
            artifacts = uiState.artifacts,
            attachments = uiState.attachments,
            onOpenFile = { path -> showFilesSheet = false; onOpenFile(path) },
            onDismiss = { showFilesSheet = false }
        )
    }

    uiState.pendingConfirmation?.let { request ->
        AlertDialog(
            onDismissRequest = { viewModel.resolveConfirmation(false) },
            title = { Text(request.title) },
            text = {
                Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(request.message)
                    request.diff?.let { DiffPreview(it) }
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.resolveConfirmation(true) }) { Text(request.approveText) } },
            dismissButton = { TextButton(onClick = { viewModel.resolveConfirmation(false) }) { Text(request.rejectText) } }
        )
    }
}

@Composable
private fun DiffPreview(diff: String) {
    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        diff.lineSequence().forEach { line ->
            val color = when {
                line.startsWith("+") && !line.startsWith("+++") -> Color(0xFF3AA76D)
                line.startsWith("-") && !line.startsWith("---") -> Color(0xFFD45B5B)
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = color)
        }
    }
}

@Composable
private fun AttachmentMessageChips(attachments: List<com.bigmoe.onedge.data.repository.AttachmentDomainModel>, onOpenFile: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        attachments.forEach { a ->
            Text(
                "${a.kind.name.lowercase().replaceFirstChar { it.uppercase() }}: ${a.name}",
                modifier = Modifier.padding(vertical = 3.dp).clickable(onClick = { onOpenFile(a.path) }),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun ArtifactMessageChips(artifacts: List<com.bigmoe.onedge.data.repository.ArtifactDomainModel>, onOpenFile: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        artifacts.forEach { a ->
            Text(
                "Artifact: ${a.title}",
                modifier = Modifier.padding(vertical = 3.dp).clickable(onClick = { onOpenFile(a.path) }),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun EngineBanner(state: EngineState, onOpenModels: () -> Unit) {
    when (state) {
        is EngineState.Ready -> Unit
        is EngineState.Loading -> BannerRow(
            text = "Loading model… " + when (state.step) {
                com.bigmoe.onedge.core.LoadStep.NONE -> "this can take a minute."
                com.bigmoe.onedge.core.LoadStep.MODEL, com.bigmoe.onedge.core.LoadStep.DRAFT,
                com.bigmoe.onedge.core.LoadStep.WEIGHTS -> "${state.step.label} • ${(state.fraction * 100).toInt()} %"
                else -> "${state.step.label}…"
            },
            action = null,
            onAction = {},
            showSpinner = true
        )
        is EngineState.Failed -> BannerRow(
            text = "Model failed to load: ${state.message.take(160)}",
            action = "Models",
            onAction = onOpenModels
        )
        EngineState.Unloaded -> BannerRow(
            text = "No model loaded • Tap the model icon in the top-right to load one.",
            action = null,
            onAction = onOpenModels
        )
    }
}

@Composable
private fun BannerRow(
    text: String,
    action: String?,
    onAction: () -> Unit,
    showSpinner: Boolean = false
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (showSpinner) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f)
            )
            if (action != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun NoticeBar(text: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
            androidx.compose.foundation.text.selection.SelectionContainer(Modifier.weight(1f)) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            IconButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(text)) }) {
                Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "Copy")
            }
            IconButton(onClick = onDismiss) {
                Icon(imageVector = Icons.Default.Close, contentDescription = "Dismiss")
            }
        }
    }
}

@Composable
private fun LiveStatusRow(
    text: String,
    telemetry: String? = null,
    detail: String? = null,
    onCancel: (() -> Unit)? = null,
    /** A running command's output so far: folded to its last line ([detail]), unfolds to all of it. */
    output: String? = null,
    cancelling: Boolean = false,
    /** A tool waits for the user's approval: shown right here with Accept / Reject (the dialog may be out of sight). */
    approval: com.bigmoe.onedge.tools.ConfirmationRequest? = null,
    onApprove: (Boolean) -> Unit = {}
) {
    var expanded by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SlowBlinkDot()
            Text(
                text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary,
                maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (!telemetry.isNullOrBlank()) {
                Text("• $telemetry", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            }
            if (approval != null) {
                androidx.compose.material3.Button(
                    onClick = { onApprove(true) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.height(30.dp)
                ) { Text(approval.approveText, style = MaterialTheme.typography.labelMedium) }
                androidx.compose.material3.OutlinedButton(
                    onClick = { onApprove(false) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.height(30.dp)
                ) { Text(approval.rejectText, style = MaterialTheme.typography.labelMedium) }
            } else if (onCancel != null) {
                androidx.compose.material3.OutlinedButton(
                    onClick = onCancel,
                    enabled = !cancelling,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.height(30.dp)
                ) { Text(if (cancelling) "Stopping…" else "Cancel", style = MaterialTheme.typography.labelMedium) }
            }
        }
        val mono = androidx.compose.ui.text.font.FontFamily.Monospace
        if (approval != null) {
            // What is to be approved, e.g. "$ pip install -q fpdf2 / Folder: workspace".
            Text(
                approval.title + "\n" + approval.message,
                style = MaterialTheme.typography.labelSmall, fontFamily = mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 6, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
            )
        } else if (!output.isNullOrBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .padding(start = 16.dp, top = 2.dp)
                    .clickable { expanded = !expanded }
            ) {
                Text(
                    if (expanded) "▾ Output" else "▸ Output",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary
                )
                if (expanded) {
                    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                    Text(
                        "Copy",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { clipboard.setText(androidx.compose.ui.text.AnnotatedString(output.trimEnd())) }
                    )
                }
                if (!expanded && !detail.isNullOrBlank()) {
                    Text(
                        detail, style = MaterialTheme.typography.labelSmall, fontFamily = mono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
            if (expanded) {
                val scroll = androidx.compose.foundation.rememberScrollState()
                // Follows the end like a terminal: after each new chunk, once it is laid out, scroll to the bottom.
                androidx.compose.runtime.LaunchedEffect(output) {
                    androidx.compose.runtime.withFrameNanos { }
                    scroll.scrollTo(scroll.maxValue)
                }
                androidx.compose.material3.Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp)
                ) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(
                            output.trimEnd(),
                            style = MaterialTheme.typography.labelSmall, fontFamily = mono,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .heightIn(max = 280.dp)
                                .verticalScroll(scroll)
                                .padding(8.dp)
                        )
                    }
                }
            }
        } else if (!detail.isNullOrBlank()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 16.dp, top = 2.dp)
            )
        }
    }
}

/** "3 min 05 s / 30 min • no output for 2 min" for a running tool. */
internal fun formatToolTelemetry(elapsedMs: Long, limitMs: Long?, quietMs: Long): String {
    val b = StringBuilder(formatToolElapsed(elapsedMs))
    if (limitMs != null && limitMs > 0) {
        b.append(" / ").append(if (limitMs % 60_000L == 0L) "${limitMs / 60_000L} min" else formatToolElapsed(limitMs))
    }
    if (quietMs >= 60_000L) b.append(" • no output for ").append(quietMs / 60_000L).append(" min")
    return b.toString()
}

/** "12 s" / "3 min 05 s" for a running tool. */
internal fun formatToolElapsed(ms: Long): String {
    val s = ms / 1000
    return if (s < 60) "$s s" else "${s / 60} min ${(s % 60).toString().padStart(2, '0')} s"
}
