package com.bigmoe.onedge.core

import com.bigmoe.onedge.data.local.datastore.AgentApproval
import com.bigmoe.onedge.data.local.datastore.EngineSettings
import com.bigmoe.onedge.data.local.datastore.WebSearchMode
import com.bigmoe.onedge.data.local.entity.MessageRole
import com.bigmoe.onedge.parser.ArtifactModel
import com.bigmoe.onedge.parser.ParsedChunk
import com.bigmoe.onedge.parser.StreamOutputParser
import com.bigmoe.onedge.parser.ToolCallParseResult
import com.bigmoe.onedge.parser.ToolCallWatcher
import com.bigmoe.onedge.parser.ToolProtocol
import com.bigmoe.onedge.tools.CODE_TOOL_NAMES
import com.bigmoe.onedge.tools.FILE_TOOL_NAMES
import com.bigmoe.onedge.tools.HELP_TOOL_NAMES
import com.bigmoe.onedge.tools.WIKI_TOOL_NAMES
import com.bigmoe.onedge.tools.ASK_USER_TOOL_NAMES
import com.bigmoe.onedge.tools.TERMUX_TOOL_NAMES
import com.bigmoe.onedge.tools.ToolContext
import com.bigmoe.onedge.tools.ToolManager
import com.bigmoe.onedge.tools.UTILITY_TOOL_NAMES
import com.bigmoe.onedge.tools.WEB_PAGE_TOOL_NAMES
import com.bigmoe.onedge.tools.WebSearchTool
import com.bigmoe.onedge.tools.ImageSearchTool
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** A committed message as the agent needs it (decoupled from Room). */
data class HistoryMessage(val id: String, val role: MessageRole, val content: String)

data class AgentRequest(
    val sessionId: String,
    /** Messages BEFORE [prompt], oldest first. The prompt's own user message must not be in here. */
    val history: List<HistoryMessage>,
    val prompt: String,
    val settings: EngineSettings,
    /** Hidden text appended to the prompt: the attached files' contents (already saved as a CONTEXT message). */
    val contextText: String = "",
    /** Pictures (or video frames) for the first engine round of this turn. */
    val images: List<ImageData> = emptyList()
)

/** A message the agent wants persisted. */
data class NewMessage(
    val role: MessageRole,
    val content: String,
    val reasoning: String? = null,
    val stats: GenerationStats? = null,
    /** Shown under the bubble: "Stopped", an engine error, ... */
    val note: String? = null,
    /** Tokens the prompt processing had to read for this turn. */
    val prefillTokens: Int? = null,
    /** How long the model spent in its reasoning span. */
    val thinkingMs: Long? = null,
    /** Wall-clock time the tools of this round took (tool-result messages). */
    val toolMs: Long? = null,
    /** Assistant messages: model and settings of the turn as JSON (see RunInfo). */
    val runInfo: String? = null
)

/** Persists a message and returns its id. */
fun interface MessageSink {
    suspend fun save(message: NewMessage): String
}

sealed interface AgentEvent {
    data class Text(val delta: String) : AgentEvent
    data class Reasoning(val delta: String) : AgentEvent
    data object GenerationStarted : AgentEvent
    data class GenerationProgress(val generatedTokens: Int, val contextUsed: Int? = null) : AgentEvent
    data class Artifact(val artifact: ArtifactModel) : AgentEvent
    data class ToolStarted(val name: String, val arguments: String) : AgentEvent
    /** Live status of the running tool, e.g. elapsed time and the last line a Termux command printed. */
    data class ToolProgress(val text: String) : AgentEvent
    /** The running command's output so far (its end), shown folded under the status line. */
    data class ToolOutput(val text: String) : AgentEvent
    /** All tools of this round have finished (or were cancelled); the cancel button can go. [ms] is the wall-clock
     *  time of the round (parallel calls overlap). */
    data class ToolsFinished(val names: List<String> = emptyList(), val ms: Long = 0L) : AgentEvent
    /** The model is writing a tool call right now (the call itself is hidden from the chat), e.g. "Writing create_file: a.py". */
    data class ToolWriting(val label: String) : AgentEvent
    /**
     * One generation round ended: how many prompt tokens it had to read and how long that took, the decode speed,
     * and how long the model reasoned (null when it did not).
     */
    data class RoundStats(val prefillTokens: Int, val prefillMs: Long, val tokensPerSecond: Double, val thinkingMs: Long?) : AgentEvent
    /** A visible, non-fatal problem (malformed call, limit reached, engine error). */
    data class Notice(val text: String) : AgentEvent
    /** The live buffers of the message that was just persisted can be cleared. */
    data class MessageSaved(val id: String, val role: MessageRole) : AgentEvent
    data object Done : AgentEvent
}

/**
 * One user turn = 1..(1+EngineSettings.agentMaxSteps) engine generations, and a model that repeats one identical call
 * round after round is stopped early. Talks to the engine ONLY through
 * [EngineController.generate]; never builds a prompt string. See docs/prompt-and-kv-design.md and
 * docs/tool-calling.md.
 */
private const val PROGRESS_TOKEN_INTERVAL = 8

class AgentController(
    private val engineController: EngineController,
    private val toolManager: ToolManager
) {
    @Volatile
    private var stopRequested = false

    /**
     * Chat in which the user chose "Yes, always in this chat" (ask_user on): no further permission question there until
     * the user leaves the chat ([resetWebPermission]). A plain yes, a no or an own answer count only for their message.
     */
    @Volatile private var webAllowedSessionId: String? = null

    /** The user left the chat (other chat, new chat, another screen): the next web access asks again. */
    fun resetWebPermission() { webAllowedSessionId = null }

    /**
     * Stop button. Interrupts a running generation (the engine then rolls the turn back and the loop
     * ends with a cancelled result) and also ends the tool loop when pressed while a tool is running,
     * where the engine has nothing to cancel.
     */
    fun requestStop() {
        stopRequested = true
        engineController.stopGeneration()
        toolManager.cancelRunningTools()
    }

    /**
     * The cancel button next to a running tool: ends only the tool(s). The model gets "cancelled by the user" as their
     * result and decides how to go on. Stop ([requestStop]) ends the whole run.
     */
    fun cancelRunningTools(): Boolean = toolManager.cancelRunningTools()

    fun runAgentLoop(request: AgentRequest, sink: MessageSink): Flow<AgentEvent> = flow {
        stopRequested = false
        val settings = request.settings
        // The loaded model's own decoding preset (its card) replaces the global decoding values for the whole answer.
        val sampling = engineController.loadedModel()?.path?.let { engineController.samplingPresetFor(it) }
            ?.applyTo(settings.samplingOverride()) ?: settings.samplingOverride()
        // Web search has two modes (docs/tool-calling.md):
        //  AUTO   - the model is told about the tool and decides to emit <tool_call> itself;
        //  ALWAYS - the app searches for every message and hands the results to the model, so it works even
        //           with small models that never call tools.
        val searchOn = settings.isWebSearchEnabled && toolManager.getTool("web_search") != null
        val alwaysSearch = searchOn && settings.webSearchMode == WebSearchMode.ALWAYS
        toolManager.syncExternalTools(settings.externalToolsJson)
        val enabledNames = enabledToolNames(settings, toolManager.toolNames(), toolManager.externalToolNames())
        val toolsEnabled = enabledNames.isNotEmpty()
        (toolManager.getTool("web_search") as? WebSearchTool)?.let {
            it.braveApiKey = settings.braveApiKey
            it.searxngInstanceUrl = settings.searxngUrl
            it.maxResults = settings.webSearchResults
        }
        // Image search shares the configured search credentials/instance and otherwise uses its keyless backend.
        (toolManager.getTool("image_search") as? ImageSearchTool)?.let {
            it.braveApiKey = settings.braveApiKey
            it.searxngInstanceUrl = settings.searxngUrl
        }
        toolManager.maxResultChars = settings.toolResultChars
        toolManager.autoApprove = settings.agentApproval == AgentApproval.ALLOW_ALL
        val maxToolRounds = settings.agentMaxSteps.coerceAtLeast(1)
        // Lazy tool-call grammar over the tools the model may call this turn (the engine decides per round whether
        // it applies: not when the model is expected to reason first).
        val toolGrammar = if (settings.toolCallGrammar && toolsEnabled) {
            com.bigmoe.onedge.parser.ToolGrammar.build(enabledNames, MAX_CALLS_PER_ROUND)
        } else ""
        val system = buildSystemPrompt(
            settings, toolsEnabled, toolManager.getToolsDescriptionPrompt(enabledNames), alwaysSearch
        )

        // With ask_user on, the user is always asked at least once before the web is used for this message: if the
        // model has not asked anything yet, the app asks whether it may (the user can also write an own answer).
        val askBeforeWeb = "ask_user" in enabledNames
        var askedThisTurn = false
        var webDenied = false
        /** null = the web may be used; [WEB_NO] = the user said no (or dismissed); else the user's own answer. */
        suspend fun askForWeb(name: String, args: String): String? {
            if (!askBeforeWeb) return null
            if (webDenied) return WEB_NO
            if (webAllowedSessionId == request.sessionId) return null
            if (askedThisTurn) return null
            val handler = toolManager.questionHandler ?: return null
            askedThisTurn = true
            val o = runCatching { org.json.JSONObject(args) }.getOrNull()
            val target = (o?.optString("query")?.ifBlank { null } ?: o?.optString("url").orEmpty()).take(200)
            // The exact query / page is shown, so the user can answer it directly instead of the web.
            val question = if (name == "web_search" || name == "image_search") t("May I search the web for \"{0}\"?", target)
            else t("May I open this web page? {0}", target)
            val yes = t("Yes, use the web")
            val always = t("Yes, always in this chat")
            val no = t("No, answer without the web")
            return when (val answer = handler.ask(com.bigmoe.onedge.tools.UserQuestion(question, listOf(yes, always, no)))) {
                yes -> null
                always -> { webAllowedSessionId = request.sessionId; null }
                null, no -> { webDenied = true; WEB_NO }
                else -> answer
            }
        }

        val turns = historyToTurns(request.history).toMutableList()
        var lastEngineMsgId: String? = request.history.lastOrNull()?.id
        var prompt = request.prompt
        if (request.contextText.isNotBlank()) prompt += "\n\n" + request.contextText
        var toolRounds = 0
        var lastCallSignature: String? = null
        var identicalRounds = 0
        // Web budget of this user message: a small context fills fast with page text, and a model that re-reads the
        // same page never learns anything new. Repeats are answered with a short note instead of the page again.
        val webSeen = HashSet<String>()
        var webSearches = 0
        var pageReads = 0
        var engineRound = 0
        var generatedTotal = 0

        if (alwaysSearch) {
            // Ask the loaded model to formulate a search query instead of sending the user's raw message
            // directly to the search provider. This is a separate, hidden short generation; the main answer
            // generation replays the real conversation and the search results as usual.
            // The query is written first (from the last exchange only, so it is quick), then the user sees it in the
            // permission question and can answer it directly instead of the web. Stop in either step ends the run.
            emit(AgentEvent.ToolWriting(t("Writing the search query…")))
            val generatedQuery = generateSearchQuery(request, turns)
            val query = searchQueryFrom(generatedQuery).ifBlank { searchQueryFrom(request.prompt) }
            val args = org.json.JSONObject().put("query", query).toString()
            val webAnswer = if (stopRequested) WEB_NO else askForWeb("web_search", args)
            if (stopRequested) {
                // Stop pressed while asking or while writing the query: no search, no answer.
            } else if (webAnswer != null) {
                // Not allowed: no search; the model learns why and what the user said instead.
                val note = if (webAnswer == WEB_NO) "The user was asked and does not want a web search for this message: " +
                    "answer from your own knowledge, without web results."
                else "Web search for \"$query\": not run. The user answered this question directly instead of the web: " +
                    "\"$webAnswer\". Use this as the result of the search; it is first-hand information from the user and " +
                    "takes precedence. Do not search for it again."
                val wrapped = ToolProtocol.wrapResult("ask_user", note)
                val id = sink.save(NewMessage(MessageRole.TOOL, wrapped))
                emit(AgentEvent.MessageSaved(id, MessageRole.TOOL))
                prompt = prompt + "\n\n" + wrapped
            }
            if (webAnswer == null && !stopRequested) {
                val searchStarted = System.currentTimeMillis()
                emit(AgentEvent.ToolStarted("web_search", args))
                val result = toolManager.executeToolCall("web_search", args)
                val searchMs = System.currentTimeMillis() - searchStarted
                emit(AgentEvent.ToolsFinished(listOf("web_search"), searchMs))
                val wrapped = ToolProtocol.wrapResult("web_search", "Query: $query\n$result")
                // Stored as a TOOL message right after the user's message; replay merges the two user-side turns
                // with the same "\n\n" join used below, so a replayed chat looks exactly like the live one.
                val id = sink.save(NewMessage(MessageRole.TOOL, wrapped, toolMs = searchMs))
                emit(AgentEvent.MessageSaved(id, MessageRole.TOOL))
                prompt = prompt + "\n\n" + wrapped
                if (settings.imageSearchEnabled && settings.autoImageSearchAfterWebSearch && !stopRequested) {
                    val imageArgs = org.json.JSONObject().put("query", query).toString()
                    val imageStarted = System.currentTimeMillis()
                    emit(AgentEvent.ToolStarted("image_search", imageArgs))
                    val imageResult = toolManager.executeToolCall("image_search", imageArgs)
                    val imageMs = System.currentTimeMillis() - imageStarted
                    emit(AgentEvent.ToolsFinished(listOf("image_search"), imageMs))
                    val imageWrapped = ToolProtocol.wrapResult("image_search", "Query: $query\n$imageResult")
                    val imageId = sink.save(NewMessage(MessageRole.TOOL, imageWrapped, toolMs = imageMs))
                    emit(AgentEvent.MessageSaved(imageId, MessageRole.TOOL))
                    prompt = prompt + "\n\n" + imageWrapped
                }
            }
            if (stopRequested) { // Stop pressed while searching: nothing is running in the engine to cancel
                emit(AgentEvent.Notice(NOTE_STOPPED))
                emit(AgentEvent.Done)
                return@flow
            }
        }

        while (true) {
            val expectedKey = ContextPlanner.stateKey(request.sessionId, lastEngineMsgId, system)
            val parser = StreamOutputParser()
            val callWatch = ToolCallWatcher()
            val rawAnswer = StringBuilder()
            val reasoning = StringBuilder()
            var finished: EngineEvent.Finished? = null
            // Set when the model started to write a tool result itself (`<tool_response>`): only the app writes
            // those. Everything from there on is dropped and the generation is stopped, so an invented result
            // (e.g. "/usr/bin/python3: can't open /root/x.pdf") never reaches the chat or the next round.
            var cutFakeResponse = false
            // How long the model reasoned: from its first reasoning token to its first answer token (or the end).
            var thinkStartedAt = 0L
            var thinkingMs: Long? = null

            suspend fun show(chunks: List<ParsedChunk>) {
                for (c in chunks) when (c) {
                    is ParsedChunk.Text -> emit(AgentEvent.Text(c.content))
                    is ParsedChunk.ArtifactDetected -> emit(AgentEvent.Artifact(c.artifact))
                    else -> Unit // tool calls are decided from the final saved text, see below
                }
            }

            emit(AgentEvent.GenerationStarted)
            engineController.generate(
                GenerationRequest(
                    system = system,
                    history = turns.toList(),
                    prompt = prompt,
                    expectedKey = expectedKey,
                    maxTokens = settings.maxTokens,
                    // After a tool result the model usually just has to use it: reasoning again costs minutes at
                    // decode speed, so it only happens when the user asked for it.
                    think = settings.thinkingEnabled && (engineRound == 0 || settings.thinkAfterTools),
                    images = if (engineRound == 0) request.images else emptyList(),
                    toolGrammar = toolGrammar,
                    // Also without the grammar: from this word on the engine writes the call greedily (temperature 0),
                    // whatever the chat's temperature is, so a call's name and JSON are the model's most likely tokens.
                    toolGrammarTrigger = if (toolsEnabled) com.bigmoe.onedge.parser.ToolGrammar.TRIGGER else "",
                    sampling = sampling
                )
            ).collect { ev ->
                when (ev) {
                    is EngineEvent.Progress -> {
                        // Progress is informational, unlike answer deltas. Emitting it for every token adds
                        // cross-coroutine/channel work and UI bookkeeping to a CPU-bound decode. Text still streams
                        // token-by-token; refresh the counter every 8 tokens and always send the final exact total.
                        val total = generatedTotal + ev.generatedTokens
                        if (total % PROGRESS_TOKEN_INTERVAL == 0) {
                            emit(AgentEvent.GenerationProgress(total, ev.contextUsed.takeIf { it > 0 }))
                        }
                    }
                    is EngineEvent.Answer -> {
                        if (cutFakeResponse) return@collect
                        val before = rawAnswer.length
                        if (thinkStartedAt != 0L && thinkingMs == null) thinkingMs = System.currentTimeMillis() - thinkStartedAt
                        rawAnswer.append(ev.delta)
                        val fake = rawAnswer.indexOf(ToolProtocol.RESPONSE_OPEN, maxOf(0, before - ToolProtocol.RESPONSE_OPEN.length))
                        if (fake >= 0) {
                            cutFakeResponse = true
                            rawAnswer.setLength(fake)
                            engineController.stopGeneration()
                            if (fake > before) show(parser.processToken(ev.delta.substring(0, fake - before)))
                            return@collect
                        }
                        show(parser.processToken(ev.delta))
                        // The call itself is hidden from the chat; say what is being written instead of "Reading…".
                        callWatch.update(rawAnswer)?.let { emit(AgentEvent.ToolWriting(it)) }
                    }
                    is EngineEvent.Reasoning -> {
                        if (thinkStartedAt == 0L) thinkStartedAt = System.currentTimeMillis()
                        reasoning.append(ev.delta)
                        emit(AgentEvent.Reasoning(ev.delta))
                    }
                    is EngineEvent.Finished -> finished = ev
                }
            }
            show(parser.flush())
            if (thinkStartedAt != 0L && thinkingMs == null) thinkingMs = System.currentTimeMillis() - thinkStartedAt

            val fin = finished
            if (fin != null) {
                emit(
                    AgentEvent.RoundStats(
                        prefillTokens = fin.stats.nPrompt,
                        prefillMs = (fin.stats.prefillSeconds * 1000.0).toLong(),
                        tokensPerSecond = fin.stats.tokensPerSecond,
                        thinkingMs = thinkingMs
                    )
                )
                generatedTotal += fin.stats.nGenerated
                emit(AgentEvent.GenerationProgress(generatedTotal, fin.stats.nPast.takeIf { it > 0 })) // 0 = unknown: never overwrite a good value
            }
            if (fin == null || !fin.ok) {
                val message = fin?.error?.ifBlank { null } ?: "Generation ended unexpectedly"
                emit(AgentEvent.Notice(message))
                saveIfAny(sink, rawAnswer, reasoning, null, note = message)?.let {
                    emit(AgentEvent.MessageSaved(it, MessageRole.ASSISTANT))
                }
                emit(AgentEvent.Done)
                return@flow
            }
            if (fin.cancelled && (!cutFakeResponse || stopRequested)) {
                // The engine rolled the whole turn back, so it does NOT hold this partial answer.
                // The saved partial message makes the next request's expected key differ -> replay.
                saveIfAny(sink, rawAnswer, reasoning, fin.stats, note = NOTE_STOPPED)?.let {
                    emit(AgentEvent.MessageSaved(it, MessageRole.ASSISTANT))
                }
                emit(AgentEvent.Done)
                return@flow
            }

            val content = if (cutFakeResponse) rawAnswer.toString().trimEnd() else fin.answer.ifBlank { rawAnswer.toString() }
            val reasoningText = (if (cutFakeResponse) reasoning.toString() else fin.reasoning.ifBlank { reasoning.toString() }).ifBlank { null }
            val assistantId = sink.save(
                NewMessage(
                    MessageRole.ASSISTANT, content, reasoningText, fin.stats,
                    prefillTokens = fin.stats.nPrompt.takeIf { it > 0 },
                    thinkingMs = thinkingMs.takeIf { reasoningText != null }
                )
            )
            emit(AgentEvent.MessageSaved(assistantId, MessageRole.ASSISTANT))
            // The engine now holds exactly: ... + this prompt + this answer. After a turn with pictures it does
            // not (its KV holds image positions), so the next turn must re-send the history as text.
            // After a cut the engine holds something else than the saved message (it rolled the turn back, or kept
            // the dropped part), so the next round re-sends the history.
            if (cutFakeResponse || (engineRound == 0 && request.images.isNotEmpty())) {
                engineController.invalidateState()
            } else {
                engineController.commitState(ContextPlanner.stateKey(request.sessionId, assistantId, system))
            }
            engineRound++
            lastEngineMsgId = assistantId
            turns.add(ChatTurn(ChatTurn.USER, prompt))
            turns.add(ChatTurn(ChatTurn.ASSISTANT, content))

            val payloads = ToolProtocol.extractPayloads(content)
            if (payloads.isEmpty()) {
                emit(AgentEvent.Done)
                return@flow
            }

            if (toolRounds >= maxToolRounds) {
                val text = "Tool call limit reached ($maxToolRounds rounds, Settings → Tools → Agent steps). " +
                    "No further tools were run; say \"continue\" to let the agent go on."
                emit(AgentEvent.Notice(text))
                val id = sink.save(NewMessage(MessageRole.TOOL, text))
                emit(AgentEvent.MessageSaved(id, MessageRole.TOOL))
                emit(AgentEvent.Done)
                return@flow
            }
            toolRounds++

            val results = ArrayList<Pair<String, String>>()
            var malformedReason: String? = null
            val calls = ArrayList<Pair<String, String>>() // (name, arguments JSON) of the calls that parsed
            for ((payload, _) in payloads.take(MAX_CALLS_PER_ROUND)) {
                when (val parsed = ToolProtocol.parseCall(payload)) {
                    is ToolCallParseResult.Malformed -> malformedReason = malformedReason ?: parsed.reason
                    is ToolCallParseResult.Ok -> calls.add(parsed.call.name to parsed.call.arguments)
                }
            }
            // The steps limit above caps a long chain; this stops a model that is stuck sooner: the exact same call
            // (same tool, same arguments) round after round never gets anywhere new.
            val signature = calls.joinToString("\n") { it.first + "\u0000" + it.second }
            if (signature.isNotEmpty() && signature == lastCallSignature) identicalRounds++ else identicalRounds = 0
            lastCallSignature = signature.ifEmpty { null }
            if (identicalRounds >= MAX_IDENTICAL_ROUNDS) {
                val text = "The model made the same tool call ${identicalRounds + 1} times in a row, so it was stopped. " +
                    "Send a message (for example \"continue\") to let it try again."
                emit(AgentEvent.Notice(text))
                val id = sink.save(NewMessage(MessageRole.TOOL, text))
                emit(AgentEvent.MessageSaved(id, MessageRole.TOOL))
                emit(AgentEvent.Done)
                return@flow
            }

            val toolsStartedAt = System.currentTimeMillis()
            // Status events from the tools (which call starts, live progress) arrive on this channel and are emitted by
            // the loop below while the tools run, so the chat shows what is happening instead of a frozen line.
            val live = Channel<AgentEvent>(Channel.UNLIMITED)
            val toolCtx = ToolContext(
                request.sessionId, assistantId,
                onOutput = { text -> live.trySend(AgentEvent.ToolOutput(text)) },
                onProgress = { text -> live.trySend(AgentEvent.ToolProgress(text)) }
            )
            // ask_user on and nothing asked yet: the first web call of the message waits for the user's permission,
            // unless the model asks its own question in this round before it.
            val firstWeb = calls.indexOfFirst { it.first in WEB_ACCESS_TOOL_NAMES && it.first in enabledNames }
            val firstAsk = calls.indexOfFirst { it.first == "ask_user" }
            val webBlock: String? = if (firstWeb < 0 || (firstAsk in 0 until firstWeb)) null else {
                when (val answer = askForWeb(calls[firstWeb].first, calls[firstWeb].second)) {
                    null -> null
                    WEB_NO -> "Not run: the user does not want the web used for this message. Answer without it, " +
                        "from your own knowledge, and do not call web tools again."
                    else -> "Not run. The user answered this directly instead of the web: \"$answer\". Use this as the " +
                        "result; it is first-hand information from the user and takes precedence. Do not call web tools " +
                        "for it again."
                }
            }
            // Decided in call order before anything runs (the reads below may run side by side): repeats and calls
            // over the web budget get a short note; page reads are capped to a share of the free context.
            val freeTokens = (engineController.loadedModel()?.nCtx ?: 0) - fin.stats.nPast
            val preset = arrayOfNulls<String>(calls.size)
            for (i in calls.indices) {
                val (name, args) = calls[i]
                if (name !in enabledNames || (webBlock != null && name in WEB_ACCESS_TOOL_NAMES)) continue
                val o = runCatching { org.json.JSONObject(args) }.getOrNull() ?: continue
                when (name) {
                    "web_search" -> {
                        val key = "s:" + o.optString("query").trim().lowercase()
                        preset[i] = when {
                            key in webSeen -> NOTE_WEB_REPEAT
                            webSearches >= MAX_WEB_SEARCHES_PER_MESSAGE -> NOTE_WEB_BUDGET
                            else -> { webSeen.add(key); webSearches++; null }
                        }
                    }
                    in WEB_PAGE_TOOL_NAMES -> {
                        val url = normalizeUrl(o.optString("url"))
                        val key = if (name == "extract_webpage") "e:$url\u0000${o.optString("query").trim().lowercase()}" else "r:$url"
                        preset[i] = when {
                            key in webSeen || (name == "read_webpage" && "r:$url" in webSeen) -> NOTE_WEB_REPEAT
                            pageReads >= MAX_PAGE_READS_PER_MESSAGE -> NOTE_WEB_BUDGET
                            else -> {
                                webSeen.add(key); pageReads++
                                if (name == "read_webpage" && freeTokens > 0) {
                                    // ~3 characters per token; at most a fifth of the free context per page.
                                    val cap = (freeTokens * 3 / 5).coerceIn(500, 6000)
                                    val asked = o.optInt("max_chars", 2500)
                                    if (asked > cap) calls[i] = name to o.put("max_chars", cap).toString()
                                }
                                null
                            }
                        }
                    }
                }
            }
            suspend fun runOne(name: String, args: String): String {
                if (webBlock != null && name in WEB_ACCESS_TOOL_NAMES) return webBlock
                if (name == "ask_user") askedThisTurn = true
                live.trySend(AgentEvent.ToolStarted(name, args))
                return when {
                    name in enabledNames -> toolManager.executeToolCall(name, args, toolCtx)
                    !toolsEnabled -> "Error: tools are disabled. Answer without them."
                    else -> "Error: tool '$name' is not enabled. Enabled tools: ${enabledNames.sorted().joinToString(", ")}."
                }
            }
            // Calls that only read from the network do not depend on each other: run them side by side, so a round
            // with three page reads costs the slowest one, not the sum. Anything else keeps its order.
            val outputs: List<String> = coroutineScope {
                val work = async {
                    if (calls.size > 1 && calls.all { it.first in enabledNames && toolManager.isParallelSafe(it.first) }) {
                        coroutineScope { calls.mapIndexed { i, c -> async { preset[i] ?: runOne(c.first, c.second) } }.awaitAll() }
                    } else {
                        calls.mapIndexed { i, c -> preset[i] ?: runOne(c.first, c.second) }
                    }
                }
                while (true) {
                    val done = select<Boolean> {
                        live.onReceive { emit(it); false }
                        work.onAwait { true }
                    }
                    if (done) break
                }
                while (true) emit(live.tryReceive().getOrNull() ?: break)
                work.await()
            }
            live.close()
            if (calls.isNotEmpty()) emit(AgentEvent.ToolsFinished(calls.map { it.first }, System.currentTimeMillis() - toolsStartedAt))
            for (i in calls.indices) results.add(calls[i].first to outputs[i])

            // In AUTO mode, follow a successful/attempted web search with image results only when explicitly enabled.
            // Reuse the model's web query rather than spending another generation just to formulate an image query.
            if (settings.isWebSearchEnabled && settings.imageSearchEnabled && settings.autoImageSearchAfterWebSearch &&
                webBlock == null && calls.any { it.first == "web_search" } && calls.none { it.first == "image_search" } &&
                "image_search" in enabledNames && !stopRequested
            ) {
                val webCall = calls.first { it.first == "web_search" }
                val query = runCatching { org.json.JSONObject(webCall.second).optString("query") }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: searchQueryFrom(request.prompt)
                val imageArgs = org.json.JSONObject().put("query", query).toString()
                emit(AgentEvent.ToolStarted("image_search", imageArgs))
                results.add("image_search" to toolManager.executeToolCall("image_search", imageArgs, toolCtx))
            }

            val toolMs = System.currentTimeMillis() - toolsStartedAt

            prompt = when {
                results.isEmpty() -> {
                    val reason = malformedReason ?: "unknown error"
                    emit(AgentEvent.Notice("The model produced an invalid tool call ($reason); asking it to retry."))
                    ToolProtocol.malformedFeedback(reason)
                }
                malformedReason != null -> {
                    ToolProtocol.wrapResults(results + ("tool_call" to "Error: another call could not be parsed ($malformedReason)."))
                }
                else -> ToolProtocol.wrapResults(results)
            }
            val toolId = sink.save(NewMessage(MessageRole.TOOL, prompt, toolMs = toolMs))
            emit(AgentEvent.MessageSaved(toolId, MessageRole.TOOL))
            if (stopRequested) {
                emit(AgentEvent.Notice(NOTE_STOPPED))
                emit(AgentEvent.Done)
                return@flow
            }
            // A long agent chain fills the context. Stop with a clear message before the engine would fail on the
            // next round (the tool result is saved, so "continue" in a new chat or after raising Context size works).
            contextFullNotice(fin.stats.nPast, prompt, engineController.loadedModel()?.nCtx ?: 0)?.let { text ->
                emit(AgentEvent.Notice(text))
                emit(AgentEvent.Done)
                return@flow
            }
            // Loop: `prompt` (the tool response) is sent as the next user turn. lastEngineMsgId stays the
            // assistant message, which is what the engine holds.
        }
    }


    /**
     * Uses the current model to turn the user's intent into a concise web-search query.
     * The result is not shown as an assistant message and is never saved to chat history.
     * On generation failure/empty output, the caller safely falls back to the user's text.
     */
    private suspend fun generateSearchQuery(request: AgentRequest, turns: List<ChatTurn>): String {
        val prompt = """Create the best concise web search query for the user's latest request.
Use the conversation context only to resolve what the user means. Do not answer the request.
Return ONLY the search query, with no quotes, explanation, prefix, or markdown.
Prefer specific names, current/recent intent, and useful keywords. Keep it under 20 words.

Latest user request:
${request.prompt}""".trim()
        var finished: EngineEvent.Finished? = null
        return try {
            engineController.generate(
                GenerationRequest(
                    system = "You are a search-query writer. Output only one useful search query.",
                    // Only the last exchange: the whole chat would be a full prefill (other system prompt, no cache
                    // hit) before every answer; it is only needed to resolve what the user means.
                    history = turns.takeLast(SEARCH_QUERY_HISTORY_TURNS).map { it.copy(content = it.content.take(1500)) },
                    prompt = prompt,
                    expectedKey = "${request.sessionId}:search-query:${System.nanoTime()}",
                    maxTokens = 48,
                    think = false,
                    images = emptyList()
                )
            ).collect { event -> if (event is EngineEvent.Finished) finished = event }
            val result = finished
            if (result != null && result.ok && !result.cancelled) result.answer else ""
        } catch (_: Exception) {
            ""
        }
    }

    private suspend fun saveIfAny(
        sink: MessageSink,
        answer: CharSequence,
        reasoning: CharSequence,
        stats: GenerationStats?,
        note: String
    ): String? {
        if (answer.isBlank() && reasoning.isBlank()) return null
        return sink.save(
            NewMessage(
                MessageRole.ASSISTANT,
                answer.toString(),
                reasoning.toString().ifBlank { null },
                stats,
                note
            )
        )
    }

    companion object {
        /** Identical consecutive tool rounds after which the model is considered stuck. */
        const val MAX_IDENTICAL_ROUNDS = 8
        /** Turns of the chat the hidden search-query generation sees (the last user/assistant exchange). */
        const val SEARCH_QUERY_HISTORY_TURNS = 2
        const val MAX_WEB_SEARCHES_PER_MESSAGE = 2
        const val MAX_PAGE_READS_PER_MESSAGE = 3
        const val NOTE_WEB_REPEAT = "Not run: already done above in this message, the result is unchanged. Use it; do not repeat this call."
        const val NOTE_WEB_BUDGET = "Not run: the web budget of this message is used up. Answer now with what you have."

        /** Same page however the model spells it: no fragment, no trailing slash, lower-case scheme and host. */
        fun normalizeUrl(url: String): String {
            val u = url.trim().substringBefore('#').trimEnd('/')
            val i = u.indexOf("://")
            if (i < 0) return u.lowercase()
            val hostEnd = u.indexOf('/', i + 3).let { if (it < 0) u.length else it }
            return u.substring(0, hostEnd).lowercase() + u.substring(hostEnd)
        }
        const val MAX_CALLS_PER_ROUND = 16
        const val NOTE_STOPPED = "Stopped"

        /** Tools that use the web: with ask_user on, the user is asked before the first of them runs in a message. */
        val WEB_ACCESS_TOOL_NAMES = setOf("web_search", "image_search") + WEB_PAGE_TOOL_NAMES

        /** askForWeb's answer when the user said no to the web (or dismissed the question). */
        private const val WEB_NO = "\u0000no"

        /**
         * Non-null when the next round would not fit: [nPast] tokens already in the context, plus the tool result
         * (estimated at 3 characters per token, on the safe side) and room for an answer, against [nCtx].
         */
        fun contextFullNotice(nPast: Int, nextPrompt: String, nCtx: Int): String? {
            if (nPast <= 0 || nCtx <= 0) return null
            val needed = nPast + nextPrompt.length / 3 + CONTEXT_ANSWER_RESERVE
            if (needed < nCtx) return null
            return "The context is almost full ($nPast of $nCtx tokens used), so the agent stopped before the next step. " +
                "Raise Context size in Settings and reload the model, or start a new chat with a short summary of the task."
        }

        /** Tokens kept free for the model's next answer when checking [contextFullNotice]. */
        const val CONTEXT_ANSWER_RESERVE = 512

        fun buildSystemPrompt(
            settings: EngineSettings,
            toolsEnabled: Boolean,
            toolPrompt: String = "",
            alwaysSearch: Boolean = false
        ): String {
            val base = settings.systemPrompt.trim()
            val extras = buildList {
                when {
                    toolsEnabled && toolPrompt.isNotBlank() -> add(toolPrompt)
                    alwaysSearch -> add(ALWAYS_SEARCH_HINT)
                }
                if (settings.interactiveUiEnabled) add(INTERACTIVE_UI_HINT)
            }
            return (listOf(base).filter { it.isNotBlank() } + extras).joinToString("\n\n").trim()
        }

        const val ALWAYS_SEARCH_HINT =
            "Web search results for the user's latest message are attached after it inside <tool_response> tags. " +
                "Use them when they are relevant and mention the source URLs you rely on. " +
                "If they are empty, irrelevant or an error, say so and answer from your own knowledge."

        /**
         * System-prompt text for Native UI. It is part of the cached prompt prefix, so it is short and never
         * changes per message: the model has to decide on its own, from the request, when a visual card fits.
         */
        const val INTERACTIVE_UI_HINT =
            "Native UI cards are ON. Use one WITHOUT being asked whenever the answer is a comparison (X vs Y, \"compare\", \"difference between\"), pros and cons, a table, a ranking, a timeline, steps or a plan, key numbers, a summary card, or slides: " +
                "write one short sentence, then ONE fenced ```bmoe-ui block. For normal chat, explanations, code and maths answer in plain text with no block. " +
                "The block is rendered with native Jetpack Compose; never use HTML, CSS or JavaScript, and never say you cannot make visuals.\n" +
                "Block = one compact, valid JSON object with short text. title is required, other fields optional: " +
                "{\"type\":\"rich\",\"title\":\"..\",\"description\":\"..\"," +
                "\"badges\":[{\"label\":\"..\",\"tone\":\"success|warning|danger|info|neutral\"}]," +
                "\"metrics\":[{\"label\":\"..\",\"value\":\"..\",\"detail\":\"..\"}]," +
                "\"comparison\":{\"leftTitle\":\"A\",\"rightTitle\":\"B\",\"rows\":[{\"label\":\"..\",\"left\":\"..\",\"right\":\"..\",\"highlight\":false}]}," +
                "\"slides\":[{\"title\":\"..\",\"subtitle\":\"..\",\"body\":\"..\"}]," +
                "\"lists\":[{\"title\":\"..\",\"style\":\"bullet|ordered\",\"items\":[{\"title\":\"..\",\"detail\":\"..\"}]}]," +
                "\"cards\":[{\"title\":\"..\",\"subtitle\":\"..\",\"body\":\"..\"}]," +
                "\"bars\":[{\"label\":\"..\",\"value\":70,\"valueLabel\":\"..\"}]," +
                "\"timeline\":[{\"time\":\"..\",\"title\":\"..\",\"detail\":\"..\",\"status\":\"done|current|upcoming\"}]," +
                "\"sections\":[{\"title\":\"..\",\"body\":\"..\"}]," +
                "\"actions\":[{\"label\":\"..\",\"prompt\":\"..\"}]}.\n" +
                "Example, \"compare cats and dogs\":\n```bmoe-ui\n" +
                "{\"type\":\"rich\",\"title\":\"Cats vs Dogs\",\"comparison\":{\"leftTitle\":\"Cats\",\"rightTitle\":\"Dogs\",\"rows\":[{\"label\":\"Care\",\"left\":\"Low\",\"right\":\"High\"},{\"label\":\"Exercise\",\"left\":\"Little\",\"right\":\"Daily walks\"}]}}\n```\n" +
                "Limits: 8 metrics/cards/slides, 12 rows/items/badges, 10 bars (0-100), 5 lists, 8 sections, 6 actions. Never invent measurements; label estimates. Actions send their prompt as a new user message."

        /** The user's message as a search query: first 200 characters, one line. */
        fun searchQueryFrom(message: String): String {
            var query = message.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
            query = query.replace(Regex("^(search query|query|web search|search for)\\s*[:：-]\\s*", RegexOption.IGNORE_CASE), "")
                .trim().trim('"', '\'', '`')
            return query.take(200)
        }

        /**
         * Tools the model may use this turn: file, code and utility tools by their switches, the web-page tools
         * whenever web search is on (plus web_search itself in AUTO mode, in ALWAYS mode the app searches).
         * Only tools that are registered count.
         */
        fun enabledToolNames(settings: EngineSettings, registered: Set<String>, external: Set<String> = emptySet()): Set<String> {
            val names = HashSet<String>()
            if (settings.toolsExternalEnabled) names.addAll(external)
            if (settings.toolsFilesEnabled) names.addAll(FILE_TOOL_NAMES)
            if (settings.toolsCodeEnabled) names.addAll(CODE_TOOL_NAMES)
            if (settings.toolsUtilitiesEnabled) names.addAll(UTILITY_TOOL_NAMES)
            if (settings.toolsTermuxEnabled) names.addAll(TERMUX_TOOL_NAMES)
            if (settings.toolsHelpEnabled) names.addAll(HELP_TOOL_NAMES)
            if (settings.toolsWikiEnabled) names.addAll(WIKI_TOOL_NAMES)
            if (settings.toolsAskUserEnabled) names.addAll(ASK_USER_TOOL_NAMES)
            if (settings.isWebSearchEnabled) {
                names.addAll(WEB_PAGE_TOOL_NAMES)
                // Image search is independently switchable so it can be removed from the model's tool list.
                if (settings.imageSearchEnabled) names.add("image_search")
                if (settings.webSearchMode == WebSearchMode.AUTO) names.add("web_search")
            }
            return names.intersect(registered)
        }

        /** DB messages -> engine turns. Tool results are user turns; SYSTEM messages are not replayed. */
        fun historyToTurns(history: List<HistoryMessage>): List<ChatTurn> = history.mapNotNull {
            when (it.role) {
                MessageRole.USER, MessageRole.TOOL, MessageRole.CONTEXT -> ChatTurn(ChatTurn.USER, it.content)
                MessageRole.ASSISTANT -> ChatTurn(ChatTurn.ASSISTANT, it.content)
                MessageRole.SYSTEM -> null
            }
        }
    }
}

/** Where messages are persisted (implemented by ChatRepository; faked in tests). */
interface MessageStore {
    suspend fun addMessage(sessionId: String, message: NewMessage): String
}
