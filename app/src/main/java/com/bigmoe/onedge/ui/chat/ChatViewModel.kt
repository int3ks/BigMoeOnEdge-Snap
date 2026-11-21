package com.bigmoe.onedge.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bigmoe.onedge.core.ConversationEdit
import com.bigmoe.onedge.core.AgentEvent
import com.bigmoe.onedge.core.AgentRequest
import com.bigmoe.onedge.core.DecisionRequest
import com.bigmoe.onedge.core.ImageData
import com.bigmoe.onedge.core.EngineController
import com.bigmoe.onedge.core.EngineState
import com.bigmoe.onedge.core.GenerationManager
import com.bigmoe.onedge.core.GenerationPhase
import com.bigmoe.onedge.core.HistoryMessage
import com.bigmoe.onedge.core.MessageSink
import com.bigmoe.onedge.core.NewMessage
import com.bigmoe.onedge.core.MemoryWatch
import com.bigmoe.onedge.core.RunInfo
import com.bigmoe.onedge.core.PerfLog
import com.bigmoe.onedge.data.repository.TurnVariants
import kotlinx.coroutines.flow.combine
import com.bigmoe.onedge.data.local.datastore.EngineSettings
import com.bigmoe.onedge.data.local.datastore.EngineSettingsDataStore
import com.bigmoe.onedge.attachments.AttachmentException
import com.bigmoe.onedge.attachments.AttachmentLimits
import com.bigmoe.onedge.attachments.AttachmentManager
import com.bigmoe.onedge.attachments.PendingAttachment
import com.bigmoe.onedge.data.local.entity.AttachmentKind
import com.bigmoe.onedge.data.local.entity.MessageRole
import com.bigmoe.onedge.data.repository.ArtifactDomainModel
import com.bigmoe.onedge.data.repository.ArtifactRepository
import com.bigmoe.onedge.data.repository.AttachmentDomainModel
import com.bigmoe.onedge.tools.ConfirmationRequest
import com.bigmoe.onedge.tools.ToolManager
import com.bigmoe.onedge.workspace.AttachmentContext
import com.bigmoe.onedge.workspace.FileKinds
import com.bigmoe.onedge.workspace.Workspace
import com.bigmoe.onedge.data.repository.ChatMessageDomainModel
import com.bigmoe.onedge.data.repository.ChatRepository
import com.bigmoe.onedge.data.repository.ChatSessionDomainModel
import com.bigmoe.onedge.parser.ArtifactLanguages
import com.bigmoe.onedge.parser.ArtifactModel
import com.bigmoe.onedge.parser.MessageContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatUiState(
    val currentSession: ChatSessionDomainModel? = null,
    val messages: List<ChatMessageUiModel> = emptyList(),
    val settings: EngineSettings = EngineSettings(),
    val engineState: EngineState = EngineState.Unloaded,
    /** Links shared into the chat, shown as chips above the message box and appended in full when sent. */
    val pendingLinks: List<String> = emptyList(),
    /** False when the loaded model always reasons (no way to turn thinking off). */
    val thinkingSwitchable: Boolean = true,
    /**
     * The app has ONE generation slot shared by every chat, so this is true while ANY turn runs, even one in a
     * chat that is not open. It locks the composer. Use [isGeneratingHere] to decide whether THIS chat shows a
     * live reply bubble / spinner.
     */
    val isGenerating: Boolean = false,
    /** True while a submitted turn is being prepared but the generation engine has not started yet. */
    val isPreparingMessage: Boolean = false,
    /** Session the running generation belongs to (null = idle, or a new chat that has no row yet). */
    val generatingSessionId: String? = null,
    /** Live timing of the running turn (phase, speeds); empty when this chat is not generating. */
    val liveTiming: LiveTiming = LiveTiming(),
    /** The open chat when the turn being prepared was submitted (null = a new chat). */
    val preparingSessionId: String? = null,
    val generationElapsedMs: Long = 0L,
    val generationTokens: Int = 0,
    /** Live decode rate of the turn being generated (tokens since the first one / time since then). */
    val liveTokensPerSecond: Double? = null,
    val contextUsed: Int = 0,
    val contextTotal: Int = 0,
    /** Answer text of the message being generated (tool-call blocks already removed). */
    val liveText: String = "",
    val liveReasoning: String = "",
    /** e.g. "Running web_search…" while a tool executes. */
    val liveStatus: String? = null,
    /** Second status line from a running tool (last output line of a Termux command). */
    val liveDetail: String? = null,
    /** A tool is running right now: show its elapsed time and a cancel button. */
    val toolRunning: Boolean = false,
    val toolElapsedMs: Long = 0L,
    /** The running command's output so far (shown folded), its time limit and how long it has printed nothing. */
    val liveOutput: String? = null,
    val toolLimitMs: Long? = null,
    val toolQuietMs: Long = 0L,
    /** Cancel was pressed and the tool is being stopped. */
    val toolCancelling: Boolean = false,
    val inputDraft: String = "",
    val chooseFromOptions: Boolean = false,
    val choiceOptions: String = "",
    /** A visible problem or hint (no model, malformed tool call, engine error). */
    val notice: String? = null,
    val selectedArtifact: ArtifactModel? = null,
    /** Files / pictures / videos picked but not sent yet. */
    val pendingAttachments: List<PendingAttachment> = emptyList(),
    /** Set while a tool waits for the user's approval (edit with diff, delete, overwrite). */
    val pendingConfirmation: ConfirmationRequest? = null,
    /** Attachments and AI-made files of the open chat (the screen groups them under their messages). */
    val attachments: List<AttachmentDomainModel> = emptyList(),
    val artifacts: List<ArtifactDomainModel> = emptyList(),
    /** One-shot: a workspace path the screen should open in the file viewer, then clear. */
    val openFilePath: String? = null
) {
    val visionActive: Boolean get() = (engineState as? EngineState.Ready)?.info?.visionActive == true

    /**
     * True only when the running (or being-prepared) turn belongs to the chat that is open. The live reply
     * bubble and its spinner key off this, not off [isGenerating]: opening another chat while one streams must
     * not show an empty spinner there.
     */
    val isGeneratingHere: Boolean
        get() {
            val here = currentSession?.id
            if (generatingSessionId != null) {
                // A new chat's row is created on its first message; until it is observed the id is not here yet.
                return generatingSessionId == here || (here == null && isPreparingMessage && preparingSessionId == null)
            }
            // Not streaming yet: the turn being prepared belongs to the chat it was submitted from
            // (a brand-new chat has no id until its first message is stored).
            return isPreparingMessage && (preparingSessionId == null || preparingSessionId == here)
        }
}

/** What the live status line shows; a copy of the relevant parts of GenerationManager.State. */
data class LiveTiming(
    val phase: GenerationPhase = GenerationPhase.PROMPT,
    val phaseStartedAt: Long = 0L,
    val toolNames: List<String> = emptyList(),
    val prefillTokens: Int = 0,
    val prefillMs: Long = 0L,
    val decodeTokensPerSecond: Double = 0.0,
    val thinkingMs: Long? = null,
    val toolMs: Long? = null
) {
    companion object {
        fun from(g: GenerationManager.State) = LiveTiming(
            g.phase, g.phaseStartedAt, g.toolNames, g.prefillTokens, g.prefillMs,
            g.liveTokensPerSecond ?: 0.0, g.thinkingMs, g.toolMs
        )
    }
}

data class ChatMessageUiModel(
    val id: String,
    val role: MessageRole,
    /** Text to show (tool calls stripped for assistant messages; tool result body for TOOL messages). */
    val text: String,
    val reasoning: String?,
    val toolCalls: List<String>,
    val artifacts: List<ArtifactModel>,
    val timestamp: Long,
    val tokensPerSecond: Double?,
    val prefillMs: Long?,
    val generatedTokens: Int?,
    val cacheHitPct: Double?,
    val generationElapsedMs: Long?,
    val contextUsed: Int?,
    val contextTotal: Int?,
    val note: String?,
    val prefillTokens: Int? = null,
    val thinkingMs: Long? = null,
    val toolMs: Long? = null,
    /**
     * The message exactly as stored, for Copy: no tool-call stripping, no telemetry, no UI labels, no renderer
     * fences. (`text` above is what the bubble shows.)
     */
    val rawContent: String = "",
    /** Assistant messages: the model and settings the answer was made with; null for older answers. */
    val runInfo: RunInfo? = null,
    /** Set on the last assistant message of each turn: its answers (Regenerate) and which one is shown. */
    val variant: VariantInfo? = null,
    /** True when this message belongs to an earlier answer shown for comparison (not part of the live chat). */
    val archived: Boolean = false
)

/** The answers of one turn: [shown] is on screen, [active] is the one the chat continues from (slots, 0-based). */
data class VariantInfo(
    val userMessageId: String,
    val shown: Int,
    val active: Int,
    val total: Int,
    /** No user message after this turn: switching makes the shown answer the live one. */
    val isLast: Boolean
)

private data class QueuedChatInput(
    val text: String,
    val attachments: List<PendingAttachment>,
    val chooseFromOptions: Boolean,
    val choiceOptions: String
)

class ChatViewModel(
    private val repository: ChatRepository,
    private val settingsDataStore: EngineSettingsDataStore,
    private val engineController: EngineController,
    private val toolManager: ToolManager,
    private val artifactRepository: ArtifactRepository,
    private val attachmentManager: AttachmentManager,
    private val workspace: Workspace,
    private val composerStore: com.bigmoe.onedge.data.local.datastore.ChatComposerDataStore,
    private val generationManager: GenerationManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())

    /** Per user message: the answer slot shown instead of the live one (Regenerate variants, view only). */
    private val _viewedVariants = MutableStateFlow<Map<String, Int>>(emptyMap())

    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var sessionJobs: List<Job> = emptyList()
    private var composerSaveJob: Job? = null
    private var composerLoadJob: Job? = null
    private var composerSwitchJob: Job? = null
    private val queueLock = Any()
    private val queuedInputs = java.util.ArrayDeque<QueuedChatInput>()
    private var queuePumpJob: Job? = null

    /** The app-wide settings; [chatToggles] (the open chat's own switches, if it has any) are laid over them. */
    private var globalSettings = com.bigmoe.onedge.data.local.datastore.EngineSettings()
    private var chatToggles: com.bigmoe.onedge.data.local.datastore.ChatToggles? = null
    /** The chat being opened: the session row arrives a moment later, its switches must not wait for it. */
    @Volatile private var openingComposerKey: String = ChatDraftState.NEW_CHAT_KEY

    /** uiState.settings is what the open chat uses: the app-wide settings with the chat's own switches. */
    private fun publishSettings() {
        val effective = chatToggles?.applyTo(globalSettings) ?: globalSettings
        _uiState.update { it.copy(settings = effective) }
    }

    /**
     * A switch under the input changed: it is saved with this chat only (a new chat keeps it once it is sent). The
     * switches in Settings are the defaults for new chats and are not changed from here.
     */
    private fun updateToggles(change: (com.bigmoe.onedge.data.local.datastore.ChatToggles) -> com.bigmoe.onedge.data.local.datastore.ChatToggles) {
        val key = currentComposerKey()
        val before = chatToggles ?: com.bigmoe.onedge.data.local.datastore.ChatToggles.from(globalSettings)
        val next = change(before)
        chatToggles = next
        publishSettings()
        viewModelScope.launch {
            composerStore.saveToggles(key, next)
        }
    }

    init {
        settingsDataStore.settingsFlow
            .onEach { s -> globalSettings = s; publishSettings() }
            .launchIn(viewModelScope)
        engineController.state
            .onEach { s ->
                val locked = (s as? EngineState.Ready)?.info?.thinkControl == "none"
                _uiState.update { it.copy(engineState = s, thinkingSwitchable = !locked) }
                if (s is EngineState.Ready) pumpQueuedInputs()
            }
            .launchIn(viewModelScope)
        generationManager.state
            .onEach { g ->
                val sameChat = g.sessionId != null && g.sessionId == _uiState.value.currentSession?.id
                _uiState.update {
                    it.copy(
                        // The app owns one shared generation slot across chats; keep the composer locked
                        // while any turn runs, even if the user switched to a different saved chat.
                        isGenerating = it.isPreparingMessage || g.active,
                        generatingSessionId = if (g.active) g.sessionId else null,
                        liveTiming = if (sameChat && g.active) LiveTiming.from(g) else if (sameChat) it.liveTiming else LiveTiming(),
                        generationElapsedMs = if (sameChat) g.elapsedMs else 0L,
                        generationTokens = if (sameChat) g.generatedTokens else 0,
                        liveTokensPerSecond = if (sameChat && g.active) g.liveTokensPerSecond else null,
                        contextUsed = if (sameChat) g.contextUsed else 0,
                        contextTotal = if (sameChat) g.contextTotal else 0,
                        liveText = if (sameChat && g.active) g.liveText else if (sameChat) it.liveText else "",
                        liveReasoning = if (sameChat && g.active) g.liveReasoning else if (sameChat) it.liveReasoning else "",
                        liveStatus = if (sameChat && g.active) g.liveStatus else if (sameChat) it.liveStatus else null,
                        liveDetail = if (sameChat && g.active) g.liveDetail else null,
                        toolRunning = sameChat && g.active && g.toolRunning,
                        toolElapsedMs = if (sameChat && g.active) g.toolElapsedMs else 0L,
                        liveOutput = if (sameChat && g.active) g.liveOutput else null,
                        toolLimitMs = if (sameChat && g.active) g.toolLimitMs else null,
                        toolQuietMs = if (sameChat && g.active) g.toolQuietMs else 0L,
                        toolCancelling = sameChat && g.active && g.toolCancelling,
                        notice = if (sameChat) g.notice ?: it.notice else it.notice,
                        pendingConfirmation = if (sameChat) g.pendingConfirmation else null
                    )
                }
            }
            .launchIn(viewModelScope)
        loadComposer(ChatDraftState.NEW_CHAT_KEY)
    }

    /** Opens a saved chat (History). No-op when it is already the open chat. */
    fun loadSession(sessionId: String) {
        if (_uiState.value.currentSession?.id == sessionId) return
        composerSwitchJob?.cancel()
        openingComposerKey = sessionId
        composerSwitchJob = viewModelScope.launch {
            flushComposerNow()
            observeSession(sessionId)
            loadComposerNow(sessionId)
        }
    }

    /** Starts an empty chat; the session row is created lazily on the first message. */
    fun newChat() {
        openingComposerKey = ChatDraftState.NEW_CHAT_KEY
        composerSwitchJob?.cancel()
        composerSwitchJob = viewModelScope.launch {
            flushComposerNow()
            sessionJobs.forEach { it.cancel() }
            sessionJobs = emptyList()
            _uiState.update {
                it.copy(
                    currentSession = null, messages = emptyList(), liveText = "", liveReasoning = "",
                    liveStatus = null, notice = null, selectedArtifact = null,
                    inputDraft = "", chooseFromOptions = false, choiceOptions = "",
                    pendingAttachments = emptyList(), pendingLinks = emptyList(), attachments = emptyList(), artifacts = emptyList()
                )
            }
            loadComposerNow(ChatDraftState.NEW_CHAT_KEY)
        }
    }

    private fun observeSession(sessionId: String) {
        // Cancel the previous collectors first: otherwise every visit to History leaks two more.
        sessionJobs.forEach { it.cancel() }
        _uiState.update {
            // The live reply belongs to the chat it was streaming in: never carry it into the chat being opened.
            it.copy(
                messages = emptyList(), notice = null, selectedArtifact = null, pendingAttachments = emptyList(), pendingLinks = emptyList(),
                attachments = emptyList(), artifacts = emptyList(), liveText = "", liveReasoning = "", liveStatus = null
            )
        }
        val sessionFlow = repository.observeSessionById(sessionId)
            .onEach { s ->
                val g = generationManager.state.value
                val same = s?.id != null && s.id == g.sessionId
                _uiState.update { it.copy(
                    currentSession = s,
                    // Same meaning as in the generation collector above (any chat running). Setting it to
                    // "this chat only" here made the flag flip back and forth whenever another chat streamed.
                    isGenerating = it.isPreparingMessage || g.active,
                    generatingSessionId = if (g.active) g.sessionId else null,
                    liveTiming = if (same && g.active) LiveTiming.from(g) else if (same) it.liveTiming else LiveTiming(),
                    generationElapsedMs = if (same) g.elapsedMs else 0L,
                    generationTokens = if (same) g.generatedTokens else 0,
                    liveTokensPerSecond = if (same && g.active) g.liveTokensPerSecond else null,
                    contextUsed = if (same) g.contextUsed else 0,
                    contextTotal = if (same) g.contextTotal else 0,
                    liveText = if (same && g.active) g.liveText else if (same) it.liveText else "",
                    liveReasoning = if (same && g.active) g.liveReasoning else if (same) it.liveReasoning else "",
                    liveStatus = if (same && g.active) g.liveStatus else if (same) it.liveStatus else null,
                    liveDetail = if (same && g.active) g.liveDetail else null,
                    toolRunning = same && g.active && g.toolRunning,
                    toolElapsedMs = if (same && g.active) g.toolElapsedMs else 0L,
                    liveOutput = if (same && g.active) g.liveOutput else null,
                    toolLimitMs = if (same && g.active) g.toolLimitMs else null,
                    toolQuietMs = if (same && g.active) g.toolQuietMs else 0L,
                    toolCancelling = same && g.active && g.toolCancelling,
                    pendingConfirmation = if (same) g.pendingConfirmation else null
                ) }
            }
            .launchIn(viewModelScope)
        _viewedVariants.value = emptyMap()
        val messagesFlow = repository.observeMessagesForSession(sessionId)
            .combine(_viewedVariants) { list, viewed -> buildMessageList(sessionId, list, viewed) }
            .onEach { list -> _uiState.update { it.copy(messages = list) } }
            .launchIn(viewModelScope)
        val artifactsFlow = artifactRepository.observe(sessionId)
            .onEach { list -> _uiState.update { it.copy(artifacts = list) } }
            .launchIn(viewModelScope)
        val attachmentsFlow = repository.observeAttachments(sessionId)
            .onEach { list -> _uiState.update { it.copy(attachments = list) } }
            .launchIn(viewModelScope)
        sessionJobs = listOf(sessionFlow, messagesFlow, artifactsFlow, attachmentsFlow)
    }

    fun sendMessage(promptText: String) = submitMessage(promptText, null)

    /** A new chat that starts with [prompt] (Performance log -> Ask a model); queued while the model loads. */
    fun newChatAndSend(prompt: String) {
        newChat()
        val opening = composerSwitchJob
        viewModelScope.launch {
            opening?.join()
            sendMessage(prompt)
        }
    }

    /** Writes the settings an answer suggested (a `bmoe-settings` block) after the user tapped Apply. */
    fun applySuggestedSettings(changes: List<com.bigmoe.onedge.core.SettingsAdvice.Change>) {
        viewModelScope.launch {
            com.bigmoe.onedge.core.SettingsAdvice.apply(settingsDataStore, changes)
            _uiState.update { it.copy(notice = "Applied ${changes.size} setting(s). They take effect at the next model load.") }
        }
    }

    /**
     * Submits a message. A non-null completion is used by the loading queue to serialize queued turns;
     * it completes only after that turn has finished (or has been rejected with a visible notice).
     */
    private fun submitMessage(promptText: String, completion: CompletableDeferred<Unit>?) {
        // Link chips (shared links) go into the prompt in full, after the typed text.
        val links = _uiState.value.pendingLinks
        val text = if (links.isEmpty()) promptText.trim()
        else (promptText.trim() + "\n\n" + links.joinToString("\n")).trim()
        val state = _uiState.value
        val pending = state.pendingAttachments
        if ((text.isEmpty() && pending.isEmpty()) || (state.isGenerating && completion == null)) {
            completion?.complete(Unit)
            return
        }
        if (generationManager.state.value.active) {
            if (completion != null) {
                // A race can occur if another caller starts generation after the queue worker checked it.
                synchronized(queueLock) {
                    queuedInputs.addFirst(QueuedChatInput(text, pending.toList(), state.chooseFromOptions, state.choiceOptions))
                }
                completion.complete(Unit)
            } else {
                _uiState.update { it.copy(notice = "Another chat is currently generating.") }
            }
            return
        }
        if (state.engineState is EngineState.Loading) {
            val queued = QueuedChatInput(text, pending.toList(), state.chooseFromOptions, state.choiceOptions)
            val count = synchronized(queueLock) { queuedInputs.addFirstIfFromWorker(queued, completion != null); queuedInputs.size }
            val composerKey = state.currentSession?.id ?: ChatDraftState.NEW_CHAT_KEY
            _uiState.update {
                it.copy(
                    inputDraft = "", pendingAttachments = emptyList(), pendingLinks = emptyList(),
                    notice = if (count == 1) "Message queued. It will be sent automatically when the model finishes loading." else "$count messages queued. They will be sent in order when the model finishes loading."
                )
            }
            viewModelScope.launch { composerStore.clearSession(composerKey) }
            completion?.complete(Unit)
            return
        }
        if (state.engineState !is EngineState.Ready) {
            _uiState.update { it.copy(notice = NOTICE_NO_MODEL) }
            completion?.complete(Unit)
            return
        }

        if (state.chooseFromOptions) {
            sendChoice(text, state.choiceOptions, completion)
            return
        }

        if (pending.any { it.kind != AttachmentKind.FILE } && !state.visionActive) {
            _uiState.update { it.copy(notice = NOTICE_NO_VISION) }
            completion?.complete(Unit)
            return
        }

        val settings = state.settings
        val userText = text.ifEmpty { "Please look at the attached ${if (pending.any { it.kind != AttachmentKind.FILE }) "media" else "file(s)"}." }
        val composerKey = state.currentSession?.id ?: ChatDraftState.NEW_CHAT_KEY
        _uiState.update {
            it.copy(isGenerating = true, isPreparingMessage = true, preparingSessionId = it.currentSession?.id, liveText = "", liveReasoning = "", liveStatus = null, notice = null)
        }
        viewModelScope.launch {
            try {
                var session = _uiState.value.currentSession
                val history: List<HistoryMessage>
                if (session == null) {
                    val modelPath = (engineController.state.value as? EngineState.Ready)?.info?.path
                    session = repository.createNewSession(titleFrom(userText), modelPath)
                    observeSession(session.id)
                    history = emptyList()
                } else {
                    history = repository.getMessages(session.id).map { HistoryMessage(it.id, it.role, it.content) }
                    if (ChatRepository.isDefaultTitle(session.title) && history.isEmpty()) {
                        repository.updateSessionTitle(session.id, titleFrom(userText))
                    }
                }
                val sessionId = session.id
                val modelName = (engineController.state.value as? EngineState.Ready)?.info?.name.orEmpty()
                if (modelName.isNotBlank()) repository.addModelName(sessionId, modelName)
                val userId = repository.addMessage(sessionId, NewMessage(MessageRole.USER, userText))
                composerStore.clearSession(composerKey)
                if (composerKey != sessionId) {
                    composerStore.clearSession(ChatDraftState.NEW_CHAT_KEY)
                    composerStore.moveToggles(composerKey, sessionId)
                }
                _uiState.update { it.copy(inputDraft = "", pendingAttachments = emptyList(), pendingLinks = emptyList()) }

                var contextText = ""
                var images: List<ImageData> = emptyList()
                if (pending.isNotEmpty()) {
                    for (a in pending) repository.addAttachment(sessionId, userId, a.kind, a.name, a.mime, a.path, a.sizeBytes)
                    try {
                        val prepared = prepareAttachments(pending, userText, settings)
                        images = prepared.images
                        contextText = prepared.contextText
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _uiState.update { it.copy(isGenerating = false, isPreparingMessage = false, notice = "Could not read an attachment: ${e.message ?: e.javaClass.simpleName}") }
                        return@launch
                    }
                    if (contextText.isNotBlank()) repository.addMessage(sessionId, NewMessage(MessageRole.CONTEXT, contextText))
                }
                val sink = RunSink(sessionId, settings, userText.length)
                // start() sets the engine state synchronously before launching its worker. Keep the preparing
                // flag until that state is active so the composer cannot accidentally enable a second send.
                val started = generationManager.start(
                    session,
                    AgentRequest(sessionId, history, userText, settings, contextText, images),
                    sink
                )
                if (!started) sink.stop()
                _uiState.update { it.copy(isPreparingMessage = false, isGenerating = started || it.isGenerating) }
                if (!started) {
                    _uiState.update { it.copy(notice = "Another generation is already running.", isGenerating = false) }
                } else if (completion != null) {
                    // start() publishes active synchronously. If a very short turn has already finished,
                    // the current state is inactive and we must not wait for a second active transition.
                    val startedState = generationManager.state.value
                    if (startedState.sessionId == sessionId && startedState.active) {
                        generationManager.state.first { it.sessionId == sessionId && !it.active }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _uiState.update { it.copy(notice = "Unexpected error: ${t.message ?: t.javaClass.simpleName}") }
            } finally {
                _uiState.update { it.copy(isPreparingMessage = false, isGenerating = generationManager.state.value.active) }
                completion?.complete(Unit)
            }
        }
    }

    // If a worker-owned message races with a model state transition, put it at the front so FIFO order remains stable.
    private fun java.util.ArrayDeque<QueuedChatInput>.addFirstIfFromWorker(
        item: QueuedChatInput,
        fromWorker: Boolean
    ) {
        if (fromWorker) addFirst(item) else addLast(item)
    }

    /** Holds submitted messages while the model is loading, then sends them FIFO once a session is ready. */
    private fun pumpQueuedInputs() {
        if (queuePumpJob?.isActive == true) return
        if (synchronized(queueLock) { queuedInputs.isEmpty() }) return
        queuePumpJob = viewModelScope.launch {
            try {
                while (true) {
                    val next = synchronized(queueLock) {
                        if (queuedInputs.isEmpty()) null else queuedInputs.removeFirst()
                    } ?: break
                    // Keep waiting across a failed/unloaded state; the queued message is not discarded.
                    if (engineController.state.value !is EngineState.Ready) {
                        engineController.state.first { it is EngineState.Ready }
                    }
                    // Wait for the mirrored UI state too; it is the snapshot submitMessage reads.
                    if (_uiState.value.engineState !is EngineState.Ready) {
                        _uiState.first { it.engineState is EngineState.Ready }
                    }
                    if (generationManager.state.value.active) {
                        generationManager.state.first { !it.active }
                    }
                    _uiState.update {
                        it.copy(
                            inputDraft = next.text,
                            pendingAttachments = next.attachments,
                            chooseFromOptions = next.chooseFromOptions,
                            choiceOptions = next.choiceOptions,
                            notice = null
                        )
                    }
                    val completed = CompletableDeferred<Unit>()
                    submitMessage(next.text, completed)
                    completed.await()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                queuePumpJob = null
                if (synchronized(queueLock) { queuedInputs.isNotEmpty() } && engineController.state.value is EngineState.Ready) {
                    pumpQueuedInputs()
                }
            }
        }
    }

    private fun sendChoice(questionText: String, rawOptions: String, completion: CompletableDeferred<Unit>? = null) {
        val question = questionText.trim()
        val options = rawOptions.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(26).toList()
        if (question.isEmpty()) { completion?.complete(Unit); return }
        if (options.size < 2) {
            _uiState.update { it.copy(notice = "Give at least two options, one per line.") }
            completion?.complete(Unit)
            return
        }
        if (_uiState.value.pendingAttachments.isNotEmpty()) {
            _uiState.update { it.copy(notice = "Choose mode is text-only; remove attachments first.") }
            completion?.complete(Unit)
            return
        }
        val settings = _uiState.value.settings
        if (generationManager.state.value.active) {
            _uiState.update { it.copy(isGenerating = false, isPreparingMessage = false, notice = "Another chat is currently generating.") }
            completion?.complete(Unit)
            return
        }
        val composerKey = _uiState.value.currentSession?.id ?: ChatDraftState.NEW_CHAT_KEY
        _uiState.update { it.copy(isGenerating = true, isPreparingMessage = true, preparingSessionId = it.currentSession?.id, liveText = "", liveReasoning = "", liveStatus = "Choosing…", notice = null) }
        viewModelScope.launch {
            try {
                var session = _uiState.value.currentSession
                if (session == null) {
                    val modelPath = (engineController.state.value as? EngineState.Ready)?.info?.path
                    session = repository.createNewSession(titleFrom(question), modelPath)
                    observeSession(session.id)
                }
                val history = repository.getMessages(session.id)
                    .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
                val prefix = buildString {
                    if (settings.systemPrompt.isNotBlank()) append(settings.systemPrompt.trim()).append("\n\n")
                    if (history.isNotEmpty()) {
                        append("Conversation so far:\n")
                        history.forEach { m -> append(if (m.role == MessageRole.USER) "User" else "Assistant").append(": ").append(m.content).append("\n") }
                        append("\n")
                    }
                }
                val suffix = buildString {
                    append(question).append("\n\nOptions:\n")
                    options.forEachIndexed { i, option -> append(('A'.code + i).toChar()).append(") ").append(option).append('\n') }
                    append("Answer with the letter of one option only.")
                }
                val result = engineController.decide(DecisionRequest(prefix, suffix, options, reusePrefix = true))
                if (!result.ok) { _uiState.update { it.copy(notice = result.error.ifBlank { "Choose failed." }) }; return@launch }
                val userText = buildString { append(question).append('\n'); options.forEachIndexed { i, option -> append(('A'.code + i).toChar()).append(") ").append(option).append('\n') } }.trimEnd()
                repository.addMessage(session.id, NewMessage(MessageRole.USER, userText))
                val body = buildString {
                    append("Choice result\n")
                    result.scores.forEach { score -> append(score.label).append(") ").append(score.text).append(" — ").append(String.format(java.util.Locale.US, "%.1f%%", score.probability * 100.0)).append('\n') }
                    if (result.best in result.scores.indices) append("\nSelected: ").append(result.scores[result.best].label)
                }
                repository.addMessage(session.id, NewMessage(MessageRole.ASSISTANT, body, note = result.metrics))
                composerStore.clearSession(composerKey)
                if (composerKey != session.id) {
                    composerStore.clearSession(ChatDraftState.NEW_CHAT_KEY)
                    composerStore.moveToggles(composerKey, session.id)
                }
                _uiState.update { it.copy(inputDraft = "", pendingAttachments = emptyList(), pendingLinks = emptyList(), isGenerating = false, isPreparingMessage = false) }
            } catch (e: CancellationException) { throw e }
            catch (t: Throwable) { _uiState.update { it.copy(isGenerating = false, isPreparingMessage = false, notice = "Unexpected error: ${t.message ?: t.javaClass.simpleName}") } }
            finally {
                _uiState.update { it.copy(isGenerating = false, isPreparingMessage = false) }
                completion?.complete(Unit)
            }
        }
    }

    // ---------------------------------------------------------------- attachments

    /** Copies a picked document / picture / video into the workspace and queues it for the next message. */
    fun addAttachment(resolver: android.content.ContentResolver, uri: android.net.Uri, kind: AttachmentKind) {
        if (kind != AttachmentKind.FILE && !_uiState.value.visionActive) {
            _uiState.update { it.copy(notice = NOTICE_NO_VISION) }
            return
        }
        val key = currentComposerKey()
        viewModelScope.launch {
            try {
                val a = attachmentManager.import(resolver, uri, kind)
                val existing = composerStore.getPending(key)
                val next = existing + a
                composerStore.savePending(key, next)
                if (currentComposerKey() == key) {
                    _uiState.update { it.copy(pendingAttachments = next) }
                }
                // Attached, but a big document makes everything slower: say so right away.
                if (a.kind == AttachmentKind.FILE) {
                    AttachmentLimits.warningFor(a.name, a.sizeBytes, com.bigmoe.onedge.workspace.ArchiveText.isArchiveName(a.name))
                        ?.let { w -> _uiState.update { it.copy(notice = w) } }
                }
            } catch (e: AttachmentException) {
                _uiState.update { it.copy(notice = e.message) }
            } catch (e: Exception) {
                _uiState.update { it.copy(notice = "Could not attach the file: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    fun removePendingAttachment(id: String) {
        val next = _uiState.value.pendingAttachments.filter { it.id != id }
        _uiState.update { it.copy(pendingAttachments = next) }
        viewModelScope.launch { composerStore.savePending(currentComposerKey(), next) }
    }

    /**
     * Something shared from another app (share sheet): opens [sessionId] (null = a new chat), adds [text] to the
     * draft and attaches [files]. Nothing is sent. Pictures and videos wait until a model is loaded, because only a
     * model with a vision projector takes them (the usual notice says so otherwise).
     */
    fun receiveShare(
        sessionId: String?,
        text: String?,
        files: List<Pair<android.net.Uri, AttachmentKind>>,
        resolver: android.content.ContentResolver,
        links: List<String> = emptyList()
    ) {
        if (sessionId == null) newChat() else loadSession(sessionId)
        val opening = composerSwitchJob
        viewModelScope.launch {
            opening?.join()
            if (links.isNotEmpty()) _uiState.update { it.copy(pendingLinks = (it.pendingLinks + links).distinct()) }
            if (!text.isNullOrBlank()) {
                val draft = _uiState.value.inputDraft
                setInputDraft(if (draft.isBlank()) text else draft.trimEnd() + "\n\n" + text)
            }
            files.filter { it.second == AttachmentKind.FILE }.forEach { (uri, kind) -> addAttachment(resolver, uri, kind) }
            val media = files.filter { it.second != AttachmentKind.FILE }
            if (media.isNotEmpty()) {
                if (_uiState.value.engineState !is EngineState.Ready) {
                    _uiState.update { it.copy(notice = "The shared pictures are attached when the model has loaded.") }
                }
                val ready = kotlinx.coroutines.withTimeoutOrNull(10 * 60_000L) {
                    _uiState.first { it.engineState is EngineState.Ready }
                }
                if (ready != null) media.forEach { (uri, kind) -> addAttachment(resolver, uri, kind) }
            }
        }
    }

    fun removePendingLink(url: String) {
        _uiState.update { it.copy(pendingLinks = it.pendingLinks - url) }
    }

    fun setInputDraft(text: String) {
        val key = currentComposerKey()
        _uiState.update { it.copy(inputDraft = text) }
        composerSaveJob?.cancel()
        composerSaveJob = viewModelScope.launch {
            delay(400)
            composerStore.saveDraft(key, text)
        }
    }

    fun flushDraft() {
        composerSaveJob?.cancel()
        composerSaveJob = null
        val s = _uiState.value
        viewModelScope.launch {
            composerStore.saveDraft(s.currentSession?.id ?: ChatDraftState.NEW_CHAT_KEY, s.inputDraft)
            composerStore.savePending(s.currentSession?.id ?: ChatDraftState.NEW_CHAT_KEY, s.pendingAttachments)
        }
    }

    private suspend fun flushComposerNow() {
        composerSaveJob?.cancel()
        composerSaveJob = null
        val s = _uiState.value
        val key = s.currentSession?.id ?: ChatDraftState.NEW_CHAT_KEY
        composerStore.saveDraft(key, s.inputDraft)
        composerStore.savePending(key, s.pendingAttachments)
    }

    fun toggleChooseFromOptions() {
        _uiState.update { it.copy(chooseFromOptions = !it.chooseFromOptions) }
    }

    fun setChoiceOptions(text: String) {
        _uiState.update { it.copy(choiceOptions = text) }
    }

    private fun currentComposerKey(): String =
        _uiState.value.currentSession?.id ?: ChatDraftState.NEW_CHAT_KEY

    private fun flushComposer() {
        composerSaveJob?.cancel()
        composerSaveJob = null
        val s = _uiState.value
        val key = s.currentSession?.id ?: ChatDraftState.NEW_CHAT_KEY
        viewModelScope.launch {
            composerStore.saveDraft(key, s.inputDraft)
            composerStore.savePending(key, s.pendingAttachments)
        }
    }

    private fun loadComposer(key: String) {
        composerLoadJob?.cancel()
        composerLoadJob = viewModelScope.launch { loadComposerNow(key) }
    }

    private suspend fun loadComposerNow(key: String) {
        val storedPending = composerStore.getPending(key)
        val pending = storedPending.filter { runCatching { workspace.exists(it.path) }.getOrDefault(false) }
        val draft = composerStore.getDraft(key)
        var toggles = composerStore.getToggles(key, globalSettings)
        if (toggles == null && key != ChatDraftState.NEW_CHAT_KEY) {
            // A chat from before per-chat switches: it keeps what is set now, later changes elsewhere do not move it.
            toggles = com.bigmoe.onedge.data.local.datastore.ChatToggles.from(globalSettings)
            composerStore.saveToggles(key, toggles)
        }
        if (openingComposerKey == key) {
            chatToggles = toggles
            publishSettings()
        }
        if (currentComposerKey() == key) {
            _uiState.update { it.copy(inputDraft = draft, pendingAttachments = pending) }
            if (pending.size != storedPending.size) composerStore.savePending(key, pending)
        }
    }

    override fun onCleared() {
        flushDraft()
        composerLoadJob?.cancel()
        sessionJobs.forEach { it.cancel() }
        super.onCleared()
    }

    // ---------------------------------------------------------------- tools, files

    /** Answer of the approval dialog. */
    fun resolveConfirmation(approved: Boolean) {
        generationManager.resolveConfirmation(approved)
    }

    // The switches under the input belong to the open chat (see updateToggles); Settings sets the defaults.
    fun toggleToolFiles() = updateToggles { it.copy(files = !it.files) }

    fun toggleToolCode() = updateToggles { it.copy(code = !it.code) }

    fun toggleToolUtilities() = updateToggles { it.copy(utilities = !it.utilities) }

    fun toggleToolTermux() = updateToggles { it.copy(termux = !it.termux) }

    /**
     * Picks the system prompt of the open chat. Only the chat's own switches change: the model stays loaded, the next
     * message just starts with the other prompt (the engine re-reads the conversation once).
     */
    fun selectSystemPrompt(id: String) = updateToggles { it.copy(promptId = id) }

    fun toggleToolExternal() {
        val v = !_uiState.value.settings.toolsExternalEnabled
        viewModelScope.launch { settingsDataStore.updateToolsExternalEnabled(v) }
    }

    fun toggleNativeUi() {
        val v = !_uiState.value.settings.interactiveUiEnabled
        viewModelScope.launch { settingsDataStore.updateInteractiveUiEnabled(v) }
    }

    /** Files already made from a given text in this run (key = chat + text), so a second tap reuses the same file. */
    private val savedArtifactPaths = HashMap<String, String>()

    /**
     * Saves [content] as a workspace file for the chat, or returns the file that already holds it. Tapping the same
     * artifact chip or "Open as artifact" again must open the same file instead of making "name (1)", "name (2)" ...
     */
    private suspend fun saveOrReuseArtifact(sessionId: String?, stem: String, ext: String, content: String, title: String?): String =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val key = (sessionId ?: "") + ":" + content.length + ":" + content.hashCode()
            // 1. made earlier from this very text and still there (even if the person edited it since)
            val remembered = savedArtifactPaths[key]
            if (remembered != null && runCatching { workspace.exists(remembered) }.getOrDefault(false)) {
                if (sessionId != null) artifactRepository.register(sessionId, "", remembered, title)
                return@withContext remembered
            }
            // 2. a file of this chat that holds exactly this text (e.g. after the app was restarted)
            val same = pickExistingArtifact(_uiState.value.artifacts.filter { it.language != FileKinds.FOLDER }.map { it.path }, content) { p ->
                try { workspace.readText(p) } catch (e: Exception) { null }
            }
            if (same != null) {
                savedArtifactPaths[key] = same
                if (sessionId != null) artifactRepository.register(sessionId, "", same, title)
                return@withContext same
            }
            val rel = workspace.uniquePath("artifacts/$stem.$ext")
            workspace.writeText(rel, content, overwrite = false)
            if (sessionId != null) artifactRepository.register(sessionId, "", rel, title)
            savedArtifactPaths[key] = rel
            rel
        }

    /** Opens an inline artifact by first persisting it to the chat workspace (once per distinct content). */
    fun openArtifact(artifact: ArtifactModel) {
        viewModelScope.launch {
            try {
                val sessionId = _uiState.value.currentSession?.id
                    ?: repository.createNewSession(ChatRepository.DEFAULT_TITLE, (_uiState.value.engineState as? EngineState.Ready)?.info?.path).also { observeSession(it.id) }.id
                // The extension follows the artifact's language, so the viewer highlights it the same way.
                val ext = when (artifact.type) {
                    com.bigmoe.onedge.parser.ArtifactType.HTML -> "html"
                    com.bigmoe.onedge.parser.ArtifactType.SVG -> "svg"
                    com.bigmoe.onedge.parser.ArtifactType.MERMAID -> "mmd"
                    com.bigmoe.onedge.parser.ArtifactType.CODE -> extensionFor(artifact.language)
                }
                val stem = artifact.fileName?.let { FileKinds.safeName(it, "artifact").substringBeforeLast('.', "") }
                    ?.takeIf { it.isNotBlank() } ?: "artifact"
                val rel = saveOrReuseArtifact(sessionId, stem, ext, artifact.content, artifact.title)
                _uiState.update { it.copy(openFilePath = rel) }
            } catch (e: Exception) {
                _uiState.update { it.copy(notice = "Could not save the artifact: ${e.message}") }
            }
        }
    }

    /** "Open as artifact" on a code block: saves it as a file in the workspace (once) and opens the viewer. */
    fun saveCodeAsArtifact(language: String, code: String) {
        val sessionId = _uiState.value.currentSession?.id
        viewModelScope.launch {
            try {
                val rel = saveOrReuseArtifact(sessionId, "artifact", extensionFor(language), code, null)
                _uiState.update { it.copy(openFilePath = rel) }
            } catch (e: Exception) {
                _uiState.update { it.copy(notice = "Could not save the code: ${e.message}") }
            }
        }
    }

    fun consumeOpenFile() {
        _uiState.update { it.copy(openFilePath = null) }
    }

    private fun onAgentEvent(ev: AgentEvent) {
        when (ev) {
            is AgentEvent.Text -> _uiState.update { it.copy(liveText = it.liveText + ev.delta, liveStatus = null) }
            is AgentEvent.Reasoning -> _uiState.update { it.copy(liveReasoning = it.liveReasoning + ev.delta) }
            is AgentEvent.ToolStarted -> _uiState.update {
                it.copy(liveStatus = if (ev.name == "web_search") statusForTool(ev.name, ev.arguments) else toolManager.describeCall(ev.name, ev.arguments) + "…")
            }
            is AgentEvent.Notice -> _uiState.update { it.copy(notice = ev.text) }
            is AgentEvent.ToolProgress -> _uiState.update { it.copy(liveStatus = ev.text.substringBefore('\n')) }
            is AgentEvent.ToolWriting -> _uiState.update { it.copy(liveStatus = ev.label) }
            is AgentEvent.ToolOutput -> Unit
            AgentEvent.GenerationStarted, is AgentEvent.GenerationProgress -> Unit
            is AgentEvent.ToolsFinished, is AgentEvent.RoundStats -> Unit // timings live in GenerationManager.State
            // The saved message is now in the list below; start a clean live bubble for the next round.
            is AgentEvent.MessageSaved -> _uiState.update { it.copy(liveText = "", liveReasoning = "") }
            is AgentEvent.Artifact -> Unit // shown as a chip once the message is saved
            AgentEvent.Done -> Unit
        }
    }

    fun stopGeneration() {
        generationManager.stop() // Also refuses any tool approval currently waiting.
    }

    /** Cancel button next to a running tool (e.g. a hanging Termux command): ends the tool, the agent goes on. */
    fun cancelTool() {
        generationManager.cancelTool()
    }

    /** Context text and pictures for a turn's attachments (files read, media decoded). Throws when one cannot be read. */
    private class PreparedAttachments(val contextText: String, val images: List<ImageData>)

    private suspend fun prepareAttachments(
        items: List<PendingAttachment>,
        userText: String,
        settings: EngineSettings
    ): PreparedAttachments {
        if (items.isEmpty()) return PreparedAttachments("", emptyList())
        val files = items.filter { it.kind == AttachmentKind.FILE }
            .map { AttachmentContext.TextFile(it.name, attachmentManager.readText(it)) }
        val prepared = attachmentManager.prepareMedia(
            items.filter { it.kind != AttachmentKind.FILE }, settings.imageMaxDim, settings.videoFrames
        )
        val contextText = AttachmentContext.build(files, prepared.notes, userText, settings.contextLength)
        return PreparedAttachments(contextText, prepared.images)
    }

    private var editInFlight = false

    /**
     * Edit & regenerate. Replaces the text of the user message [messageId] in place, removes everything that was
     * stored after it (the answers, tool results and attached-file context built on the old wording), rebuilds
     * the conversation from the history BEFORE it and runs the same generation pipeline a normal send uses
     * ([GenerationManager] -> AgentController -> EngineController): same streaming, thinking, tools, web search,
     * attachments, telemetry and persistence. No second session or engine is created.
     *
     * Everything the new turn needs (the message's attachments are read again) is prepared before the chat is
     * changed, so a failure leaves the chat exactly as it was.
     */
    /**
     * Regenerate without editing: answers the turn again from the same user message. On an assistant message that is
     * the user message right before it. Same path as Edit & regenerate with the text unchanged.
     */
    fun regenerate(messageId: String) {
        val messages = _uiState.value.messages
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0) return
        val user = messages.subList(0, index + 1).lastOrNull { it.role == MessageRole.USER }
        if (user == null) {
            _uiState.update { it.copy(notice = "There is no message to answer again.") }
            return
        }
        editUserMessage(user.id, user.rawContent.ifEmpty { user.text })
    }

    fun editUserMessage(messageId: String, newText: String) {
        val state = _uiState.value
        val session = state.currentSession ?: return
        val clean = newText.trim()
        if (clean.isEmpty()) {
            _uiState.update { it.copy(notice = "Message cannot be empty.") }
            return
        }
        if (editInFlight || state.isGenerating || generationManager.state.value.active) {
            _uiState.update { it.copy(notice = "Stop the current generation before editing a message.") }
            return
        }
        if (state.engineState !is EngineState.Ready) {
            _uiState.update { it.copy(notice = NOTICE_NO_MODEL) }
            return
        }
        val settings = state.settings
        val visionActive = state.visionActive
        editInFlight = true
        _uiState.update {
            it.copy(
                isGenerating = true, isPreparingMessage = true, preparingSessionId = it.currentSession?.id,
                liveText = "", liveReasoning = "", liveStatus = null, notice = null
            )
        }
        viewModelScope.launch {
            var started = false
            try {
                val stored = repository.getMessages(session.id).map { HistoryMessage(it.id, it.role, it.content) }
                val plan = ConversationEdit.plan(stored, messageId)
                if (plan == null) {
                    _uiState.update { it.copy(notice = "That message can no longer be edited.") }
                    return@launch
                }
                val attachments = repository.getAttachmentsForMessage(session.id, messageId).map {
                    PendingAttachment(it.id, it.kind, it.name, it.path, it.mime, it.sizeBytes)
                }
                if (attachments.any { it.kind != AttachmentKind.FILE } && !visionActive) {
                    _uiState.update { it.copy(notice = NOTICE_NO_VISION) }
                    return@launch
                }
                val prepared = try {
                    prepareAttachments(attachments, clean, settings)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _uiState.update { it.copy(notice = "Could not read an attachment: ${e.message ?: e.javaClass.simpleName}") }
                    return@launch
                }
                // Same text = Regenerate: keep the answer being replaced so it can be compared with the new one.
                val keepAnswer = clean == plan.previousText.trim()
                if (!repository.rewriteUserMessage(session.id, messageId, clean, plan.removeIds, keepAnswer)) {
                    _uiState.update { it.copy(notice = "That message can no longer be edited.") }
                    return@launch
                }
                if (plan.isFirstUserMessage && session.title == titleFrom(plan.previousText)) {
                    repository.updateSessionTitle(session.id, titleFrom(clean))
                }
                // The engine still holds the old conversation (and the answers just removed): replay from the new history.
                engineController.invalidateState()
                val modelName = (engineController.state.value as? EngineState.Ready)?.info?.name.orEmpty()
                if (modelName.isNotBlank()) repository.addModelName(session.id, modelName)
                if (prepared.contextText.isNotBlank()) {
                    repository.addMessage(session.id, NewMessage(MessageRole.CONTEXT, prepared.contextText))
                }
                _viewedVariants.update { it - messageId }
                val sink = RunSink(session.id, settings, clean.length)
                started = generationManager.start(
                    session,
                    AgentRequest(session.id, plan.history, clean, settings, prepared.contextText, prepared.images),
                    sink
                )
                if (!started) sink.stop()
                if (!started) _uiState.update { it.copy(notice = "Another generation is already running.") }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _uiState.update { it.copy(notice = "Unexpected error: ${t.message ?: t.javaClass.simpleName}") }
            } finally {
                editInFlight = false
                _uiState.update { it.copy(isPreparingMessage = false, isGenerating = generationManager.state.value.active) }
            }
        }
    }

    fun dismissNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    /** The globe button in the input bar. */
    fun toggleWebSearch() = updateToggles { it.copy(webSearch = !it.webSearch) }

    /** The brain button in the input bar; feeds GenerateRequest.think of the next message. */
    fun toggleThinking() {
        if (!_uiState.value.thinkingSwitchable) return
        updateToggles { it.copy(thinking = !it.thinking) }
    }

    fun selectArtifact(artifact: ArtifactModel?) {
        _uiState.update { it.copy(selectedArtifact = artifact) }
    }

    fun dismissArtifactDrawer() {
        _uiState.update { it.copy(selectedArtifact = null) }
    }

    /**
     * Shows answer [slot] of the turn [info]. On the last turn of the chat (and with nothing generating) the shown
     * answer becomes the live one, so the chat continues from it; further up it is only shown, the chat below keeps
     * building on the active answer.
     */
    fun showVariant(info: VariantInfo, slot: Int) {
        if (slot < 0 || slot >= info.total) return
        val session = _uiState.value.currentSession ?: return
        val busy = _uiState.value.isGenerating || generationManager.state.value.active || editInFlight
        if (info.isLast && !busy && slot != info.active) {
            viewModelScope.launch {
                if (repository.activateVariant(session.id, info.userMessageId, slot)) {
                    _viewedVariants.update { it - info.userMessageId }
                    // The engine holds the answer that was live before: replay from the stored chat next turn.
                    engineController.invalidateState()
                } else {
                    _viewedVariants.update { it + (info.userMessageId to slot) }
                }
            }
            return
        }
        _viewedVariants.update { if (slot == info.active) it - info.userMessageId else it + (info.userMessageId to slot) }
    }

    /** Stored messages -> bubbles: CONTEXT hidden, a turn shown as the answer picked with the arrows, variant bars set. */
    private fun buildMessageList(
        sessionId: String,
        list: List<ChatMessageDomainModel>,
        viewed: Map<String, Int>
    ): List<ChatMessageUiModel> {
        val out = ArrayList<ChatMessageUiModel>(list.size)
        var i = 0
        while (i < list.size) {
            val m = list[i]
            if (m.role != MessageRole.USER) {
                if (m.role != MessageRole.CONTEXT) out += m.toUiModel()
                i++
                continue
            }
            out += m.toUiModel()
            var end = i + 1
            while (end < list.size && list[end].role != MessageRole.USER) end++
            val live = list.subList(i + 1, end)
            // No variants yet: one answer (slot 0); the bar still shows which model wrote it.
            val variants = TurnVariants.decode(m.variantsJson) ?: TurnVariants(active = 0, archived = emptyList())
            val wanted = viewed[m.id]
            val archivedTurn = if (wanted != null && wanted != variants.active) repository.archivedTurn(sessionId, m.variantsJson, wanted) else null
            val shown = if (archivedTurn != null) wanted!! else variants.active
            val turn = (archivedTurn ?: live).filter { it.role != MessageRole.CONTEXT }
                .map { it.toUiModel().copy(archived = archivedTurn != null) }
            val lastAssistant = turn.indexOfLast { it.role == MessageRole.ASSISTANT }
            val info = VariantInfo(m.id, shown, variants.active, variants.total, isLast = end == list.size)
            turn.forEachIndexed { k, u -> out += if (k == lastAssistant) u.copy(variant = info) else u }
            i = end
        }
        return out
    }

    /**
     * Saves the messages of a turn with the model and settings it ran with ([RunInfo]) and keeps the lowest free
     * RAM seen while the generation runs (MemoryWatch samples, no extra polling).
     */
    private inner class RunSink(
        private val sessionId: String,
        settings: EngineSettings,
        private val promptChars: Int
    ) : MessageSink {
        private val model = (engineController.state.value as? EngineState.Ready)?.info
        private val base = model?.let { RunInfo.from(it, settings) }
        private val heatStart = PerfLog.thermalNow()
        // Threads follow the CPU cores chosen in Settings, live (not the load-time value in cfg).
        private val decodeMask = com.bigmoe.onedge.core.CpuTopology.effective(settings.cpuMask)
        private val prefillMask = com.bigmoe.onedge.core.CpuTopology.effective(settings.cpuMaskPrefill)
        @Volatile private var minFree = Long.MAX_VALUE
        @Volatile private var maxSwap = -1L
        private val watch: Job = viewModelScope.launch {
            MemoryWatch.now.collect { n ->
                if (n != null && n.freeMb < minFree) minFree = n.freeMb
                if (n != null && n.swapMb > maxSwap) maxSwap = n.swapMb
            }
        }
        private val stopper: Job = viewModelScope.launch {
            generationManager.state.first { it.active }
            generationManager.state.first { !it.active }
            watch.cancel()
        }

        fun stop() {
            watch.cancel()
            stopper.cancel()
        }

        override suspend fun save(message: NewMessage): String {
            val minFreeMb = minFree.takeIf { it != Long.MAX_VALUE }
            val info = if (message.role == MessageRole.ASSISTANT && base != null) {
                RunInfo.withPreset(base, engineController.samplingPresetFor(base.path))
                    .copy(
                        minFreeMb = minFreeMb,
                        maxSwapMb = maxSwap.takeIf { it >= 0 },
                        majfltPerToken = engineController.lastRunJson()?.let { j ->
                            runCatching { org.json.JSONObject(j).optDouble("majflt_per_token", Double.NaN) }.getOrNull()
                        }?.takeIf { !it.isNaN() }
                    ).toJson()
            } else null
            val stats = message.stats
            if (message.role == MessageRole.ASSISTANT && model != null && stats != null && stats.nGenerated > 0) {
                PerfLog.recordTurn(model.name, org.json.JSONObject().apply {
                    put("path", model.path)
                    put("npuReady", model.npuPrefillReady)
                    put("promptChars", promptChars)
                    put("prefillTokens", stats.nPrompt)
                    put("prefillSeconds", stats.prefillSeconds)
                    put("ctxUsed", stats.nPast)
                    put("nCtx", model.nCtx)
                    put("generated", stats.nGenerated)
                    put("decodeTps", stats.tokensPerSecond)
                    if (stats.cacheHitPct >= 0) put("cacheHitPct", stats.cacheHitPct)
                    message.thinkingMs?.let { put("thinkingMs", it) }
                    message.toolMs?.let { put("toolMs", it) }
                    minFreeMb?.let { put("minFreeMb", it) }
                    if (maxSwap >= 0) put("maxSwapMb", maxSwap)
                    message.note?.let { put("note", it.take(200)) }
                    heatStart.put(this, "start")
                    PerfLog.thermalNow().put(this, "end")
                    put("decodeThreads", java.lang.Long.bitCount(decodeMask))
                    put("prefillThreads", java.lang.Long.bitCount(prefillMask))
                    put("decodeCores", com.bigmoe.onedge.core.SettingsAdvice.coreList(decodeMask))
                    put("prefillCores", com.bigmoe.onedge.core.SettingsAdvice.coreList(prefillMask))
                    engineController.lastRunJson()?.let { j -> runCatching { put("engine", org.json.JSONObject(j)) } }
                    put("cfg", PerfLog.configJson(model.config))
                })
            }
            return repository.addMessage(sessionId, message.copy(runInfo = info))
        }
    }

    private fun ChatMessageDomainModel.toUiModel(): ChatMessageUiModel {
        val isAssistant = role == MessageRole.ASSISTANT
        return ChatMessageUiModel(
            id = id,
            role = role,
            text = when (role) {
                MessageRole.ASSISTANT -> MessageContent.displayText(content)
                MessageRole.TOOL -> MessageContent.toolResultBody(content)
                else -> content
            },
            reasoning = reasoning,
            toolCalls = if (isAssistant) MessageContent.toolCallLabels(content) { n, a -> toolManager.describeCall(n, a) } else emptyList(),
            artifacts = if (isAssistant) MessageContent.artifacts(id, content) else emptyList(),
            timestamp = timestamp,
            tokensPerSecond = tokensPerSecond,
            prefillMs = prefillMs,
            generatedTokens = generatedTokens,
            cacheHitPct = cacheHitPct,
            generationElapsedMs = generationElapsedMs,
            contextUsed = contextUsed,
            contextTotal = contextTotal,
            note = note,
            prefillTokens = prefillTokens,
            thinkingMs = thinkingMs,
            toolMs = toolMs,
            rawContent = content,
            runInfo = if (isAssistant) RunInfo.parse(runInfo) else null
        )
    }

    companion object {
        /** "Searching the web for “cats”…" for the search tool, "Running x…" otherwise. */
        fun statusForTool(name: String, argumentsJson: String): String {
            if (name != "web_search") return "Running $name…"
            val q = runCatching { org.json.JSONObject(argumentsJson).optString("query") }.getOrNull().orEmpty().trim()
            return if (q.isEmpty()) "Searching the web…" else "Searching the web for \u201C${q.take(60)}\u201D…"
        }

        const val NOTICE_NO_VISION =
            "The loaded model cannot see pictures or video. Load a vision model together with its mmproj file (Models tab)."

        /** The first of [paths] whose text equals [content] (trailing line breaks ignored); null when none does. */
        internal fun pickExistingArtifact(paths: List<String>, content: String, readText: (String) -> String?): String? {
            val want = content.trimEnd('\n', '\r')
            for (p in paths) {
                val text = readText(p) ?: continue
                if (text.trimEnd('\n', '\r') == want) return p
            }
            return null
        }

        /** File extension for saving code of [language] (any alias: js, c#, golang ...); "txt" when unknown. */
        fun extensionFor(language: String): String = ArtifactLanguages.extensionFor(language)

        const val NOTICE_NO_MODEL = "No model is loaded. Open the model screen and load a model first."

        fun titleFrom(prompt: String): String {
            val oneLine = prompt.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            return if (oneLine.length <= 40) oneLine.ifEmpty { ChatRepository.DEFAULT_TITLE } else oneLine.take(40).trimEnd() + "…"
        }
    }
}
