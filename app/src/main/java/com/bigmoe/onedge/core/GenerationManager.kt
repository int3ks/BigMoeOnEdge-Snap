package com.bigmoe.onedge.core

import android.content.Context
import com.bigmoe.onedge.data.repository.ChatRepository
import com.bigmoe.onedge.data.repository.ChatSessionDomainModel
import com.bigmoe.onedge.tools.ConfirmationHandler
import com.bigmoe.onedge.tools.ConfirmationRequest
import com.bigmoe.onedge.tools.ToolManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Application-owned owner of the single active agent generation. */
class GenerationManager(
    private val context: Context,
    private val repository: ChatRepository,
    private val agentController: AgentController,
    private val engineController: EngineController,
    private val toolManager: ToolManager
) {
    data class State(
        val sessionId: String? = null,
        val chatTitle: String = "",
        val active: Boolean = false,
        val startedAt: Long? = null,
        val elapsedMs: Long = 0L,
        val generatedTokens: Int = 0,
        val contextUsed: Int = 0,
        val contextTotal: Int = 0,
        val liveText: String = "",
        val liveReasoning: String = "",
        val liveStatus: String? = null,
        val notice: String? = null,
        val pendingConfirmation: ConfirmationRequest? = null,
        /** When the first generated token was seen and the count then: the live rate starts there, after the prefill. */
        val decodeStartAt: Long? = null,
        val decodeStartTokens: Int = 0,
        /** Live decode rate while generating, null until a second token has arrived. */
        val liveTokensPerSecond: Double? = null,
        /** A tool is running: the chat shows a cancel button and [toolElapsedMs]. */
        val toolRunning: Boolean = false,
        val toolStartedAt: Long? = null,
        val toolElapsedMs: Long = 0L,
        /** Second status line from the running tool, e.g. the last line a Termux command printed. */
        val liveDetail: String? = null,
        /** The running command's whole output so far (its end); the chat shows it folded. */
        val liveOutput: String? = null,
        /** The running tool's time limit, shown next to the elapsed time; null = none. */
        val toolLimitMs: Long? = null,
        /** When the running tool last printed something (or started); gives "no output for ...". */
        val lastOutputAt: Long? = null,
        val toolQuietMs: Long = 0L,
        /** Cancel was pressed; the chat shows "Stopping…" until the tool has ended. */
        val toolCancelling: Boolean = false
    ) {
        /** The new token count, with the live rate measured from the first generated token. */
        fun withTokens(tokens: Int, now: Long = System.currentTimeMillis()): State {
            val n = maxOf(generatedTokens, tokens)
            if (n <= 0) return this
            val startAt = decodeStartAt ?: now
            val startTokens = if (decodeStartAt == null) n else decodeStartTokens
            val ms = now - startAt
            val rate = if (n > startTokens && ms > 0) (n - startTokens) * 1000.0 / ms else liveTokensPerSecond
            return copy(generatedTokens = n, decodeStartAt = startAt, decodeStartTokens = startTokens, liveTokensPerSecond = rate)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var generationJob: Job? = null
    private var clockJob: Job? = null
    private var persistenceJob: Job? = null
    private var terminalState: String = "FINISHED"
    private var lastAssistantMessageId: String? = null

    private var confirmation: CompletableDeferred<Boolean>? = null

    init {
        toolManager.confirmationHandler = ConfirmationHandler { request ->
            val answer = CompletableDeferred<Boolean>()
            confirmation = answer
            // The status row says that it waits for the user (with Accept / Reject right there), instead of looking
            // like a running command; the tool's clock starts once it is approved.
            val runningLabel = _state.value.liveStatus
            update { copy(pendingConfirmation = request, liveStatus = "Waiting for your approval…", liveDetail = null) }
            var approved = false
            try { answer.await().also { approved = it } } finally {
                confirmation = null
                update {
                    copy(
                        pendingConfirmation = null,
                        liveStatus = if (approved && !toolCancelling) runningLabel else liveStatus,
                        toolStartedAt = if (approved) System.currentTimeMillis() else toolStartedAt,
                        toolElapsedMs = if (approved) 0L else toolElapsedMs,
                        lastOutputAt = if (approved) System.currentTimeMillis() else lastOutputAt
                    )
                }
            }
        }
        scope.launch(Dispatchers.IO) { repository.clearTransientGenerationStates() }
    }

    fun start(session: ChatSessionDomainModel, request: AgentRequest, sink: MessageSink): Boolean {
        if (generationJob?.isActive == true) return false
        val started = System.currentTimeMillis()
        val total = (engineController.state.value as? EngineState.Ready)?.info?.nCtx ?: 0
        terminalState = "FINISHED"
        lastAssistantMessageId = null
        _state.value = State(session.id, session.title, true, null, contextTotal = total)
        generationJob = scope.launch {
            GenerationForegroundService.start(context, session.id)
            // Batch streaming UI snapshots to roughly 30 Hz. Text was previously concatenated and
            // published once per token, forcing repeated copies of the whole response and extra redraws.
            val pendingText = StringBuilder()
            val pendingReasoning = StringBuilder()
            var pendingProgress: AgentEvent.GenerationProgress? = null
            var lastUiFlushNanos = System.nanoTime()
            fun flushPending() {
                val textChunk = pendingText.toString()
                val reasoningChunk = pendingReasoning.toString()
                val progress = pendingProgress
                pendingText.setLength(0)
                pendingReasoning.setLength(0)
                pendingProgress = null
                if (textChunk.isNotEmpty() || reasoningChunk.isNotEmpty() || progress != null) {
                    update {
                        copy(
                            liveText = liveText + textChunk,
                            liveReasoning = liveReasoning + reasoningChunk,
                            liveStatus = if (textChunk.isNotEmpty()) null else liveStatus,
                            contextUsed = progress?.contextUsed ?: contextUsed
                        ).let { s -> progress?.let { s.withTokens(it.generatedTokens) } ?: s }
                    }
                }
                lastUiFlushNanos = System.nanoTime()
            }
            fun flushIfDue() {
                if (System.nanoTime() - lastUiFlushNanos >= 33_000_000L) flushPending()
            }
            try {
                try {
                    agentController.runAgentLoop(request, sink).collect { event ->
                        when (event) {
                            is AgentEvent.Text -> {
                                pendingText.append(event.delta)
                                flushIfDue()
                            }
                            is AgentEvent.Reasoning -> {
                                pendingReasoning.append(event.delta)
                                flushIfDue()
                            }
                            is AgentEvent.GenerationProgress -> {
                                pendingProgress = event
                                flushIfDue()
                            }
                            else -> {
                                // Keep status, tool and persistence boundaries ordered after all pending text.
                                flushPending()
                                when (event) {
                                    AgentEvent.GenerationStarted -> update { copy(startedAt = startedAt ?: System.currentTimeMillis()) }
                                    is AgentEvent.ToolStarted -> update {
                                        val now = System.currentTimeMillis()
                                        copy(
                                            liveStatus = toolManager.describeCall(event.name, event.arguments) + "…",
                                            liveDetail = null, liveOutput = null, toolRunning = true,
                                            toolStartedAt = now, toolElapsedMs = 0L, lastOutputAt = now, toolQuietMs = 0L,
                                            toolLimitMs = toolManager.limitSecondsOf(event.name, event.arguments)?.let { it * 1000L },
                                            toolCancelling = false
                                        )
                                    }
                                    is AgentEvent.ToolOutput -> update {
                                        copy(liveOutput = event.text, lastOutputAt = System.currentTimeMillis(), toolQuietMs = 0L)
                                    }
                                    is AgentEvent.ToolProgress -> update {
                                        val lines = event.text.split('\n', limit = 2)
                                        // While stopping, the "Stopping…" line stays.
                                        if (toolCancelling) copy(liveDetail = lines.getOrNull(1))
                                        else copy(liveStatus = lines[0], liveDetail = lines.getOrNull(1))
                                    }
                                    AgentEvent.ToolsFinished -> update {
                                        copy(
                                            liveStatus = "Reading the result…",
                                            liveDetail = null, liveOutput = null, toolRunning = false, toolStartedAt = null,
                                            toolLimitMs = null, lastOutputAt = null, toolQuietMs = 0L, toolCancelling = false
                                        )
                                    }
                                    is AgentEvent.ToolWriting -> update { copy(liveStatus = event.label, liveDetail = null) }
                                    is AgentEvent.Notice -> {
                                        terminalState = when {
                                            event.text == AgentController.NOTE_STOPPED -> "CANCELLED"
                                            Regex("(?i)\\b(error|failed|crashed|unexpected|could not)\\b").containsMatchIn(event.text) -> "ERROR"
                                            else -> "FINISHED"
                                        }
                                        update { copy(notice = event.text) }
                                    }
                                    is AgentEvent.MessageSaved -> {
                                        if (event.role == com.bigmoe.onedge.data.local.entity.MessageRole.ASSISTANT) {
                                            lastAssistantMessageId = event.id
                                        }
                                        update { copy(liveText = "", liveReasoning = "") }
                                    }
                                    is AgentEvent.Artifact, AgentEvent.Done -> Unit
                                    is AgentEvent.Text, is AgentEvent.Reasoning, is AgentEvent.GenerationProgress -> Unit
                                }
                            }
                        }
                    }
                } finally {
                    // Never lose the last partial chunk, including when cancellation interrupts collection.
                    flushPending()
                }
            } catch (t: Throwable) {
                if (t !is CancellationException) {
                    terminalState = "ERROR"
                    update { copy(notice = t.message ?: t.javaClass.simpleName) }
                }
            } finally {
                withContext(NonCancellable) {
                    val final = _state.value
                    val elapsed = final.startedAt?.let { System.currentTimeMillis() - it } ?: final.elapsedMs
                    lastAssistantMessageId?.let { messageId ->
                        repository.updateGenerationTelemetry(
                            messageId = messageId,
                            elapsedMs = elapsed,
                            contextUsed = final.contextUsed,
                            contextTotal = final.contextTotal,
                            generatedTokens = final.generatedTokens
                        )
                    }
                    repository.finishGeneration(session.id, elapsed, final.generatedTokens, final.contextUsed, final.contextTotal, final.notice, terminalState)
                    _state.value = final.copy(
                        active = false, elapsedMs = elapsed, toolRunning = false, toolStartedAt = null, liveDetail = null,
                        liveOutput = null, toolLimitMs = null, lastOutputAt = null, toolQuietMs = 0L, toolCancelling = false
                    )
                    // Tools release what they held for the run (Termux's wake lock); not awaited, it may take a moment.
                    scope.launch { toolManager.finishRun() }
                    clockJob?.cancel()
                    persistenceJob?.cancel()
                    GenerationForegroundService.stop(context)
                }
            }
        }
        clockJob?.cancel()
        clockJob = scope.launch {
            while (isActive) {
                delay(250)
                val s = _state.value
                val st = s.startedAt
                val now = System.currentTimeMillis()
                if (s.active && st != null) update {
                    copy(
                        elapsedMs = now - st, toolElapsedMs = toolStartedAt?.let { now - it } ?: 0L,
                        toolQuietMs = lastOutputAt?.let { now - it } ?: 0L
                    )
                }
            }
        }
        persistenceJob?.cancel()
        persistenceJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(1000)
                val s = _state.value
                if (s.active && s.sessionId == session.id) repository.updateGenerationProgress(session.id, s.startedAt ?: started, s.elapsedMs, s.generatedTokens, s.contextUsed, s.contextTotal)
            }
        }
        return true
    }

    fun updateTokenProgress(generatedTokens: Int, contextUsed: Int? = null) = update {
        withTokens(generatedTokens).copy(contextUsed = contextUsed ?: this.contextUsed)
    }

    fun resolveConfirmation(approved: Boolean) { confirmation?.complete(approved) }
    fun stop() { if (generationJob?.isActive == true) agentController.requestStop(); confirmation?.complete(false) }

    /**
     * The cancel button next to a running tool: ends only that tool; the model gets an error and decides how to go on
     * (Stop ends the whole run). Shows "Stopping…" at once, since ending a Termux command can take a few seconds.
     */
    fun cancelTool() {
        if (generationJob?.isActive != true) return
        update { copy(toolCancelling = true, liveStatus = "Stopping…") }
        confirmation?.complete(false) // still waiting for approval: this call is declined, the model decides
        agentController.cancelRunningTools()
    }
    private inline fun update(crossinline transform: State.() -> State) { _state.value = transform(_state.value) }
}
