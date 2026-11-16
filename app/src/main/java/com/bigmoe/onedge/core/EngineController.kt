package com.bigmoe.onedge.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns the model lifecycle and turns the blocking native generation into a cancellable [Flow].
 *
 * Conversation state: the engine keeps its own chat history + KV. [engineStateKey] records which
 * conversation that is (see [ContextPlanner.stateKey]); when a request's expected key differs the engine
 * is reset (clear_kv) and the saved turns are replayed. See docs/prompt-and-kv-design.md.
 */
class EngineController(
    private val backendProvider: () -> EngineBackend,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Where to write this session's metrics CSV (null = no CSV). Only asked when metrics CSV is enabled. */
    private val csvPathProvider: () -> String? = { null },
    /** The LoRA adapter attached to a model (Models page), applied whenever that model loads; null = none. */
    private val loraProvider: suspend (modelPath: String) -> LoraChoice? = { null },
    /** The KV cache type chosen for a model on the Models page; F16 when none was chosen. */
    private val kvCacheTypeProvider: suspend (modelPath: String) -> KvCacheType = { KvCacheType.F16 },
    /** Guess ahead chosen on a model's card (Models page); null = the requested (global) setting applies. */
    private val specProvider: suspend (modelPath: String) -> SpecSource? = { null },
    /** NPU prefill and expert cache chosen on a model's card; null fields = the requested (global) setting applies. */
    private val memoryOptionsProvider: suspend (modelPath: String) -> ModelMemoryOptions = { ModelMemoryOptions() },
    /** KV snapshots on disk (chat switch, reload, app restart without a new prefill); null = never. */
    private val kvSnapshotStore: KvSnapshotStore? = null,
    private val kvSnapshotsEnabled: suspend () -> Boolean = { false }
) {
    private val backend: EngineBackend by lazy(backendProvider)

    private val _state = MutableStateFlow<EngineState>(EngineState.Unloaded)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    /** Serialises load/unload/generate on the app side (the native layer is single-flight too). */
    private val lifecycleMutex = Mutex()
    private val generating = AtomicBoolean(false)

    @Volatile
    private var engineStateKey: String? = null

    // ---- KV snapshots on disk ---------------------------------------------------------------------------
    // The chat whose conversation the engine's context holds (as far as the app knows), whether it changed since
    // the last save or load, and what ties a snapshot to the loaded model setup. A wrong guess here costs time,
    // never correctness: the engine keeps only the tokens a replay shares with what it holds.
    @Volatile
    private var kvSessionId: String? = null
    @Volatile
    private var kvDirty = false
    @Volatile
    private var kvFingerprint: String? = null

    private suspend fun snapshotsOn(): Boolean =
        kvSnapshotStore != null && kvFingerprint != null && runCatching { kvSnapshotsEnabled() }.getOrDefault(false)

    /** Writes the held chat's snapshot when it changed and is worth it. Only between generations. */
    private fun saveSnapshot() {
        val store = kvSnapshotStore ?: return
        val fingerprint = kvFingerprint ?: return
        val session = kvSessionId ?: return
        if (!kvDirty) return
        try {
            if (backend.kvTokens() < KvSnapshotStore.MIN_TOKENS || !store.hasRoom()) return
            val prefix = store.prefix(fingerprint, session)
            store.prepare(prefix)
            if (backend.saveKv(prefix) > 0) {
                kvDirty = false
                store.trim(keep = prefix)
            } else {
                store.delete(prefix)
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * Before a replay for [target]: the chat the engine holds is saved (it is about to be overwritten) and
     * [target]'s snapshot, when there is one, is loaded - the replay then finds its tokens already in the context.
     */
    private suspend fun swapSnapshot(target: String) {
        if (kvSessionId == target || !snapshotsOn()) return
        val store = kvSnapshotStore ?: return
        val fingerprint = kvFingerprint ?: return
        withContext(ioDispatcher) {
            saveSnapshot()
            val prefix = store.prefix(fingerprint, target)
            if (!store.exists(prefix)) return@withContext
            val loaded = try { backend.loadKv(prefix) } catch (_: Throwable) { 0 }
            if (loaded > 0) {
                kvSessionId = target
            } else {
                // Unusable (or the engine was busy): the context may have been emptied on the way.
                store.delete(prefix)
                kvSessionId = null
            }
            kvDirty = false
        }
    }

    /**
     * Cancels the running model load (Models page). The engine gives up at its next check - within a moment while it
     * reads the model or the weights, after the step for the NPU step - and [loadModel] then returns [LOAD_CANCELLED]
     * with the state Unloaded. Does nothing when no load runs.
     */
    fun cancelLoad() {
        if (_state.value is EngineState.Loading) backend.cancelLoad(true)
    }

    /** The app is going to the background: keep the running chat for a restart. Skipped while generating. */
    suspend fun saveSnapshotNow() = withContext(ioDispatcher) {
        if (generating.get() || !snapshotsOn()) return@withContext
        lifecycleMutex.withLock { if (!generating.get()) saveSnapshot() }
    }

    // ---- Local API server arbitration -------------------------------------------------------------------
    // The engine is single-flight. API requests queue up here (one at a time, FIFO, bounded wait); the in-app chat
    // has priority: if it starts while an API generation runs, that generation is cancelled (see acquireForApp), and
    // API requests that are still queued keep waiting until the app is idle or their wait runs out.
    private val apiQueue = Mutex()
    private val apiWaiting = AtomicInteger(0)
    private val appPending = AtomicInteger(0)
    @Volatile
    private var activeApiLease: ApiLease? = null

    val isReady: Boolean get() = _state.value is EngineState.Ready

    /** The loaded model, or null when none is ready. */
    fun loadedModel(): ModelInfo? = (_state.value as? EngineState.Ready)?.info

    /** Build-time features of the native library; all false if the library cannot be loaded. */
    val capabilities: EngineCaps by lazy {
        try { backend.capabilities() } catch (_: Throwable) { EngineCaps(overlapHook = false, multimodal = false, npuPrefill = false) }
    }

    /** Detailed runtime state for settings. The native implementation caches the probe result. */
    val npuPrefillStatus: String
        get() = try {
            backend.npuPrefillStatus()
        } catch (t: Throwable) {
            "Unavailable: native engine status could not be read (${t.message ?: t.javaClass.simpleName})."
        }

    /** The CPU cores chosen in Settings for decode and prefill (bit i = cpu i, 0 = Android decides). */
    fun setCpuMasks(decode: Long, prefill: Long) {
        try { backend.setCpuMasks(decode, prefill) } catch (_: Throwable) {}
    }

    /**
     * Live tuning of the loaded model between generations (the device tuner): compute threads for decode and prompt
     * graphs and, when given, the expert-cache budget, without a reload. False while a generation runs.
     */
    suspend fun tuneLive(decodeThreads: Int, prefillThreads: Int, cacheMb: Int? = null): Boolean = withContext(ioDispatcher) {
        if (loadedModel() == null || !generating.compareAndSet(false, true)) return@withContext false
        try {
            lifecycleMutex.withLock {
                backend.setThreads(decodeThreads, prefillThreads)
                if (cacheMb != null && cacheMb > 0) backend.setCacheBudget(cacheMb)
            }
            true
        } catch (_: Throwable) {
            false
        } finally {
            generating.set(false)
        }
    }

    /** CPU cores Android lets the app use (bit i = cpu i), 0 when unknown. */
    fun allowedCpus(): Long = try { backend.allowedCpus() } catch (_: Throwable) { 0L }

    /**
     * Loads [modelPath]; blocks (on the IO dispatcher) for as long as the engine takes. Any previously
     * loaded model is released first. Returns null on success or the error message.
     */
    suspend fun loadModel(
        modelPath: String,
        requestedConfig: LoadConfig,
        mmprojPath: String? = null,
        draftPath: String? = null,
        /** false = load exactly [requestedConfig]'s guess ahead (the auto-tuner measuring one), ignoring the model's own. */
        useModelSpec: Boolean = true
    ): String? = withContext(ioDispatcher) {
        lifecycleMutex.withLock {
            // A reload or a model switch: the chat the old session holds survives as a snapshot.
            if (_state.value is EngineState.Ready && !generating.get() && snapshotsOn()) saveSnapshot()
            kvSessionId = null
            kvDirty = false
            kvFingerprint = null
            val file = File(modelPath)
            if (!file.isFile || !file.canRead()) {
                val msg = "Model file is not readable: $modelPath"
                _state.value = EngineState.Failed(modelPath, msg)
                return@withLock msg
            }
            val kind = GgufInspector.inspect(file).kind
            // A guess-ahead mode chosen on the model's card wins over the global one. The drafter is passed by the
            // callers whenever one is attached and used only when the mode that applies is MTP.
            // Route-ahead and row streaming cannot run with speculation (the engine refuses the pair), and the Guess ahead
            // card is locked to Off then; a model's own MTP / n-gram yields to them the same way instead of failing the load.
            val specBlocked = requestedConfig.routeAhead != 0 || requestedConfig.rowStream
            val modelSpec = if (useModelSpec) runCatching { specProvider(modelPath) }.getOrNull()
                ?.let { if (specBlocked && it != SpecSource.OFF) null else it } else null
            val requestedGlobal = if (modelSpec != null) requestedConfig.copy(spec = modelSpec) else requestedConfig
            // The model's own NPU switch and expert cache (its card) win over the global settings. The NPU is only
            // forced on where this build and device have it.
            val memoryOptions = if (useModelSpec) runCatching { memoryOptionsProvider(modelPath) }.getOrDefault(ModelMemoryOptions())
            else ModelMemoryOptions()
            val requested = requestedGlobal.copy(
                npuPrefill = when (memoryOptions.npu) {
                    null -> requestedGlobal.npuPrefill
                    true -> capabilities.npuPrefill
                    false -> false
                },
                cacheMode = if (memoryOptions.cacheMb != null) CacheMode.FIXED else requestedGlobal.cacheMode,
                cacheMb = memoryOptions.cacheMb ?: requestedGlobal.cacheMb
            )
            val wantsSeparateDraft = requested.spec == SpecSource.MTP && !draftPath.isNullOrBlank()
            // A separate MTP sidecar must not disable streaming for the target MoE model. Keep the
            // normal auto policy: MoE target experts stream from storage; the independent sidecar
            // uses mmap because its tensors belong to a different GGUF/offset map.
            val kvType = runCatching { kvCacheTypeProvider(modelPath) }.getOrDefault(KvCacheType.F16)
            val config = applyAutoMode(requested, kind).copy(kvCacheType = kvType)
            engineStateKey = null
            // Cleared before the state says Loading, so a Cancel pressed from now on is never lost.
            backend.cancelLoad(false)
            _state.value = EngineState.Loading(modelPath, System.currentTimeMillis())
            // The engine reports how far the load has got (step + fraction); poll it while backend.load blocks
            // this thread, on another IO thread. Cancelled below before the state turns Ready or Failed.
            val progressPoll = this@withContext.launch {
                var last = -1
                while (true) {
                    delay(250)
                    val raw = runCatching { backend.loadProgress() }.getOrDefault(0)
                    if (raw == last || raw <= 0) continue
                    last = raw
                    val current = _state.value
                    if (current is EngineState.Loading && current.path == modelPath) {
                        // compareAndSet: never write Loading over the Ready/Failed the load thread just set.
                        _state.compareAndSet(current, current.copy(step = LoadStep.of(raw / 1000), fraction = (raw % 1000) / 999f))
                    }
                }
            }
            try {
                val csv = if (config.metricsCsv) csvPathProvider() else null
                var activeConfig = config
                var activeDraftPath: String? = if (wantsSeparateDraft) draftPath else null
                var activeLora: LoraChoice? = runCatching { loraProvider(modelPath) }.getOrNull()
                val requestedAddOns = RequestedAddOns(config, requestedConfig, kvType, activeLora, mmprojPath, if (wantsSeparateDraft) draftPath else null, modelSpec)
                var warning: String? = null
                var error = try {
                    backend.load(modelPath, activeConfig, csv, mmprojPath, activeDraftPath, activeLora)
                } catch (e: UnsatisfiedLinkError) {
                    "Native engine library failed to load: ${e.message}"
                } catch (e: Throwable) {
                    "Unexpected error while loading: ${e.message ?: e.javaClass.simpleName}"
                }

                // A quantized KV cache the backend cannot run (no flash attention for this model/device) must not brick the
                // model: load it with the default F16 cache and say so.
                val cancelled = { error == LOAD_CANCELLED }
                if (error != null && !cancelled() && activeConfig.kvCacheType != KvCacheType.F16) {
                    val f16 = activeConfig.copy(kvCacheType = KvCacheType.F16)
                    val withF16 = try {
                        backend.load(modelPath, f16, csv, mmprojPath, activeDraftPath, activeLora)
                    } catch (e: Throwable) {
                        "Unexpected error while retrying with an F16 KV cache: ${e.message ?: e.javaClass.simpleName}"
                    }
                    if (withF16 == null) {
                        warning = "KV cache ${activeConfig.kvCacheType.label} could not be used ($error). Loaded with F16."
                        activeConfig = f16
                        error = null
                    }
                }

                // A LoRA adapter made for another model must not brick the model either: load it without the adapter
                // and say so, rather than claiming the adapter is active.
                if (error != null && !cancelled() && activeLora != null) {
                    val withoutLora = try {
                        backend.load(modelPath, activeConfig, csv, mmprojPath, activeDraftPath, null)
                    } catch (e: Throwable) {
                        "Unexpected error while retrying without the LoRA adapter: ${e.message ?: e.javaClass.simpleName}"
                    }
                    // Only blame the adapter when the model loads without it; otherwise the cause is elsewhere.
                    if (withoutLora == null) {
                        warning = "LoRA adapter failed to load ($error). Loaded without it."
                        activeLora = null
                        error = null
                    }
                }

                // A broken/mismatched sidecar must not brick the target model. Retry a plain decode and
                // surface the failure rather than claiming MTP is active.
                if (error != null && !cancelled() && wantsSeparateDraft) {
                    val draftError = error
                    val plainConfig = activeConfig.copy(spec = SpecSource.OFF)
                    val plainError = try {
                        backend.load(modelPath, plainConfig, csv, mmprojPath, null, activeLora)
                    } catch (e: Throwable) {
                        "Unexpected error while retrying without the MTP draft: ${e.message ?: e.javaClass.simpleName}"
                    }
                    if (plainError == null) {
                        activeConfig = plainConfig
                        activeDraftPath = null
                        warning = listOfNotNull(
                            warning, "Separate MTP draft failed to load ($draftError). Loaded with normal decoding; speculation is OFF."
                        ).joinToString("\n")
                        error = null
                    } else {
                        error = "Separate MTP draft failed: $draftError\nFallback without speculation also failed: $plainError"
                    }
                }
                if (cancelled()) {
                    // Not a failure: the engine freed what it had built, nothing is loaded.
                    _state.value = EngineState.Unloaded
                    return@withLock LOAD_CANCELLED
                }
                if (error != null) {
                    error = ModelFamilies.explainLoadError(error)
                    _state.value = EngineState.Failed(modelPath, error)
                    return@withLock error
                }
                val info = backend.modelInfo()
                kvFingerprint = if (kvSnapshotStore == null) null else KvSnapshotStore.fingerprint(
                    file,
                    "kv=${activeConfig.kvCacheType.label}",
                    "lora=${activeLora?.let { File(it.path).name + ":" + it.scale }.orEmpty()}",
                    "mtp=${activeConfig.spec == SpecSource.MTP}",
                    "draft=${activeDraftPath?.let { File(it).name }.orEmpty()}"
                )
                _state.value = EngineState.Ready(
                    ModelInfo(
                        path = modelPath,
                        name = file.name,
                        arch = info["arch"].orEmpty(),
                        nCtx = info["n_ctx"]?.toIntOrNull() ?: activeConfig.nCtx,
                        nExpertUsed = info["n_expert_used"]?.toIntOrNull() ?: 0,
                        loadSeconds = info["load_seconds"]?.toDoubleOrNull() ?: 0.0,
                        thinkControl = info["think_control"] ?: "template",
                        overlapActive = info["overlap"] == "1",
                        visionActive = info["vision"] == "1",
                        mmprojPath = mmprojPath,
                        config = activeConfig,
                        draftModelPath = activeDraftPath,
                        lora = activeLora,
                        loadWarning = warning,
                        npuPrefillReady = info["npu_prefill"] == "ready",
                        requested = requestedAddOns
                    )
                )
                null
            } finally {
                progressPoll.cancel()
            }
        }
    }

    companion object {
        /** What [loadModel] returns (and the engine reports) when the user cancelled the load. */
        const val LOAD_CANCELLED = "load cancelled"
        /** How long an in-app generation waits for a cancelled API generation to unwind. */
        const val PREEMPT_WAIT_MS = 30_000L
        const val API_MAX_QUEUED = 8
        private const val API_POLL_MS = 50L

        /** MoE = stream experts, dense = ordinary load. An unreadable header keeps the requested value. */
        fun applyAutoMode(config: LoadConfig, kind: ModelKind): LoadConfig = when (kind) {
            ModelKind.MOE -> config.copy(mmapBaseline = false)
            ModelKind.DENSE -> config.copy(mmapBaseline = true)
            ModelKind.UNKNOWN -> config
        }
    }

    suspend fun unload() = withContext(ioDispatcher) {
        // stop() first so a running generation unwinds quickly; the native free then waits for it.
        try { backend.stop() } catch (_: Throwable) {}
        lifecycleMutex.withLock {
            engineStateKey = null
            if (_state.value is EngineState.Ready && !generating.get() && snapshotsOn()) saveSnapshot()
            kvSessionId = null
            kvDirty = false
            kvFingerprint = null
            try { backend.free() } catch (_: Throwable) {}
            _state.value = EngineState.Unloaded
        }
    }

    /** Scores a fixed set of choices with one prompt prefill and no decode. */
    suspend fun decide(request: DecisionRequest): DecisionResult = withContext(ioDispatcher) {
        val ready = _state.value as? EngineState.Ready
            ?: return@withContext DecisionResult(ok = false, error = "No model is loaded. Load a model first.")
        if (request.choices.size < 2) {
            return@withContext DecisionResult(ok = false, error = "Give at least two options.")
        }
        if (!generating.compareAndSet(false, true)) {
            return@withContext DecisionResult(ok = false, error = "The engine is already generating.")
        }
        try {
            // A decision overwrites the context: no chat owns it afterwards. Not saved first - decisions and helper
            // turns come often, and a snapshot per message would wear the flash for little; the chat's last snapshot
            // (from the last chat switch, unload or app stop) is what the next turn loads.
            kvSessionId = null
            kvDirty = false
            val result = lifecycleMutex.withLock {
                backend.decide(request)
            }
            // decide() deliberately leaves only its optional prefix cache state. Normal generation should
            // replay the chat transcript rather than assuming that state is a normal assistant turn.
            engineStateKey = null
            result
        } catch (t: Throwable) {
            engineStateKey = null
            DecisionResult(ok = false, error = "Native decide crashed: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            generating.set(false)
        }
    }

    fun stopGeneration() {
        try { backend.stop() } catch (_: Throwable) {}
    }

    /** Call after the turn's assistant message has been saved: the engine now holds exactly that state. */
    fun commitState(key: String) {
        engineStateKey = key
    }

    /** Forget what the engine holds; the next turn will reset and replay. */
    fun invalidateState() {
        engineStateKey = null
    }

    /**
     * Streams one generation. Cancelling the collector stops the native generation and waits for it to
     * unwind. The flow always ends with exactly one [EngineEvent.Finished] (unless the collector cancels).
     */
    fun generate(request: GenerationRequest): Flow<EngineEvent> = flow {
        val ready = _state.value as? EngineState.Ready
        if (ready == null) {
            emit(failed("No model is loaded. Load a model first."))
            return@flow
        }
        if (!acquireForApp()) {
            emit(failed("The engine is already generating."))
            return@flow
        }
        var completed = false
        try {
            var attempt = 0
            var shrink = 1.0
            // Only a chat turn carries a state key (ContextPlanner.stateKey); a helper turn (search query, device
            // tuner) uses the context without owning a snapshot.
            val chat = request.expectedKey.takeIf { it.contains('|') }?.substringBefore('|')
            while (true) {
                val replay = engineStateKey != request.expectedKey || shrink < 1.0
                if (replay && attempt == 0 && chat != null) swapSnapshot(chat)
                var streamed = false
                var fin: EngineEvent.Finished? = null
                runOnce(request, ready.info, shrink, replay).collect { ev ->
                    if (ev is EngineEvent.Finished) {
                        fin = ev
                    } else {
                        streamed = true
                        emit(ev)
                    }
                }
                val result = fin ?: failed("Generation ended without a result")
                if (!result.ok && !streamed && attempt < 2 && ContextPlanner.isContextOverflow(result.error)) {
                    // The replayed history did not fit; retry with half of it.
                    attempt++
                    shrink *= 0.5
                    continue
                }
                // A completed turn leaves the engine ahead of the caller's saved state until the caller
                // saves the turn and calls commitState(). A cancelled or failed turn is rolled back by the
                // engine: state is unchanged after a continued turn, and only the seeded replay after a
                // replayed one, so a replayed turn forgets the key.
                engineStateKey = if (result.ok && result.cancelled && !replay) request.expectedKey else null
                if (result.ok) {
                    kvSessionId = chat
                    kvDirty = chat != null
                }
                emit(result)
                completed = true
                return@flow
            }
        } finally {
            if (!completed) engineStateKey = null // collector cancelled mid-flight: be conservative
            generating.set(false)
        }
    }

    /**
     * The in-app chat's way to take the engine. Same as before (a plain compare-and-set) unless the engine is busy
     * with an API request: that one is cancelled and the chat waits for it to unwind. Anything else (another chat
     * generation, a decide()) is refused exactly as it always was.
     */
    private suspend fun acquireForApp(): Boolean {
        if (generating.compareAndSet(false, true)) return true
        val lease = activeApiLease ?: return false
        appPending.incrementAndGet()
        try {
            lease.markPreempted()
            try { backend.stop() } catch (_: Throwable) {}
            val deadline = System.currentTimeMillis() + PREEMPT_WAIT_MS
            while (System.currentTimeMillis() < deadline) {
                if (generating.compareAndSet(false, true)) return true
                delay(25)
            }
            return false
        } finally {
            appPending.decrementAndGet()
        }
    }

    /**
     * Waits (at most [timeoutMs], at most [maxQueued] callers at once) for the engine and returns a lease, or null
     * when the caller should answer "overloaded" (HTTP 529). Release the lease when the request is finished, whatever
     * happened; the engine is not free for anyone else until then.
     */
    suspend fun acquireForApi(timeoutMs: Long, maxQueued: Int = API_MAX_QUEUED): ApiLease? {
        if (apiWaiting.incrementAndGet() > maxQueued) {
            apiWaiting.decrementAndGet()
            return null
        }
        var locked = false
        try {
            val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(0L)
            locked = withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) { apiQueue.lock(); true } ?: false
            if (!locked) return null
            while (true) {
                if (appPending.get() == 0 && generating.compareAndSet(false, true)) {
                    val lease = ApiLease {
                        activeApiLease = null
                        generating.set(false)
                        apiQueue.unlock()
                    }
                    activeApiLease = lease
                    locked = false // the lease owns the queue slot now
                    return lease
                }
                if (System.currentTimeMillis() >= deadline) return null
                delay(API_POLL_MS)
            }
        } finally {
            if (locked) apiQueue.unlock()
            apiWaiting.decrementAndGet()
        }
    }

    /**
     * Streams one stateless chat generation for the local API server; [lease] comes from [acquireForApi]. Always ends
     * with exactly one [ChatEvent.Finished] (unless the collector cancels, which stops the native generation). The
     * engine reuses the KV prefix left by the previous request on its own. Afterwards the engine no longer holds the
     * in-app chat's state, so the next in-app turn replays its history (clear_kv), as it does after decide().
     */
    fun generateChat(lease: ApiLease, request: ChatGenRequest): Flow<ChatEvent> = flow {
        if (_state.value !is EngineState.Ready) {
            emit(chatFailed("No model is loaded. Load a model first.", lease.preempted))
            return@flow
        }
        // The in-app chat may have asked for the engine between the lease being granted and this call; a stop() sent
        // then would be lost (the native side clears its cancel flag when a generation starts), so check here.
        if (lease.preempted) {
            emit(chatFailed("The in-app chat took the model.", true))
            return@flow
        }
        engineStateKey = null
        // An API request decodes its own conversation into the context (see decide() for why nothing is saved here).
        kvSessionId = null
        kvDirty = false
        try {
            coroutineScope {
                val events = Channel<ChatEvent>(Channel.UNLIMITED)
                val stopped = AtomicBoolean(false)
                val worker = launch(ioDispatcher) {
                    try {
                        backend.generateChat(request, object : NativeChatCallback {
                            override fun onToken(answerDelta: ByteArray, reasoningDelta: ByteArray): Boolean {
                                if (reasoningDelta.isNotEmpty()) events.trySend(ChatEvent.Reasoning(decodeUtf8(reasoningDelta)))
                                if (answerDelta.isNotEmpty()) events.trySend(ChatEvent.Answer(decodeUtf8(answerDelta)))
                                // Also covers a preemption that arrived after the check above but before the native start.
                                return !stopped.get() && !lease.preempted
                            }

                            override fun onFinished(
                                ok: Boolean, cancelled: Boolean, error: ByteArray, answer: ByteArray,
                                reasoning: ByteArray, toolCalls: Array<ByteArray>, stopReason: ByteArray,
                                stopSequence: ByteArray, nGenerated: Int, nPromptTotal: Int, nReused: Int,
                                nPast: Int, tokensPerSecond: Double, prefillSeconds: Double, cacheHitPct: Double
                            ) {
                                events.trySend(
                                    ChatEvent.Finished(
                                        ok = ok, cancelled = cancelled, error = decodeUtf8(error),
                                        answer = decodeUtf8(answer), reasoning = decodeUtf8(reasoning),
                                        toolCalls = parseNativeToolCalls(toolCalls),
                                        stopReason = decodeUtf8(stopReason), stopSequence = decodeUtf8(stopSequence),
                                        stats = ChatStats(nGenerated, nPromptTotal, nReused, nPast, tokensPerSecond, prefillSeconds, cacheHitPct),
                                        preempted = lease.preempted
                                    )
                                )
                            }
                        })
                    } catch (t: Throwable) {
                        events.trySend(chatFailed("Native generation crashed: ${t.message ?: t.javaClass.simpleName}", lease.preempted))
                    } finally {
                        events.close()
                    }
                }
                try {
                    for (ev in events) emit(ev)
                } finally {
                    if (worker.isActive) {
                        stopped.set(true)
                        try { backend.stop() } catch (_: Throwable) {}
                    }
                }
            }
        } finally {
            engineStateKey = null
        }
    }

    private fun chatFailed(message: String, preempted: Boolean = false) = ChatEvent.Finished(
        ok = false, cancelled = false, error = message, answer = "", reasoning = "", toolCalls = emptyList(),
        stopReason = "", stopSequence = "", stats = ChatStats(), preempted = preempted
    )

    private fun failed(message: String) = EngineEvent.Finished(
        ok = false, cancelled = false, error = message, answer = "", reasoning = "", stats = GenerationStats()
    )

    /** One native call. [shrink] scales the replay history budget down after a context overflow. */
    private fun runOnce(
        request: GenerationRequest,
        info: ModelInfo,
        shrink: Double,
        needsReplay: Boolean
    ): Flow<EngineEvent> = flow {
        var history = emptyList<ChatTurn>()
        var prompt = request.prompt
        if (needsReplay) {
            val budget = (ContextPlanner.historyBudget(
                nCtx = info.nCtx,
                systemTokens = ContextPlanner.estimateTokens(request.system),
                promptTokens = ContextPlanner.estimateTokens(request.prompt),
                maxTokens = request.maxTokens
            ) * shrink).toInt()
            val prepared = ContextPlanner.prepare(request.history, request.prompt, budget)
            history = prepared.history
            prompt = prepared.prompt
        }
        val maxTokens = ContextPlanner.effectiveMaxTokens(request.maxTokens, info.nCtx)

        coroutineScope {
            val events = Channel<EngineEvent>(Channel.UNLIMITED)
            val stopped = AtomicBoolean(false)
            val worker = launch(ioDispatcher) {
                try {
                    backend.generate(
                        system = request.system,
                        historyFlat = history,
                        prompt = prompt,
                        maxTokens = maxTokens,
                        clearKv = needsReplay,
                        think = request.think,
                        images = request.images,
                        toolGrammar = request.toolGrammar,
                        toolGrammarTrigger = request.toolGrammarTrigger,
                        sampling = request.sampling,
                        callback = object : NativeGenerationCallback {
                            private var generated = 0
                            override fun onToken(answerDelta: ByteArray, reasoningDelta: ByteArray, contextUsed: Int): Boolean {
                                generated++
                                events.trySend(EngineEvent.Progress(generated, contextUsed))
                                if (reasoningDelta.isNotEmpty()) events.trySend(EngineEvent.Reasoning(decodeUtf8(reasoningDelta)))
                                if (answerDelta.isNotEmpty()) events.trySend(EngineEvent.Answer(decodeUtf8(answerDelta)))
                                return !stopped.get()
                            }

                            override fun onFinished(
                                ok: Boolean, cancelled: Boolean, error: ByteArray, answer: ByteArray,
                                reasoning: ByteArray, nGenerated: Int, nPrompt: Int, nPast: Int,
                                tokensPerSecond: Double, prefillSeconds: Double, cacheHitPct: Double
                            ) {
                                events.trySend(
                                    EngineEvent.Finished(
                                        ok = ok, cancelled = cancelled, error = decodeUtf8(error),
                                        answer = decodeUtf8(answer), reasoning = decodeUtf8(reasoning),
                                        stats = GenerationStats(nGenerated, nPrompt, nPast, tokensPerSecond, prefillSeconds, cacheHitPct)
                                    )
                                )
                            }
                        }
                    )
                } catch (t: Throwable) {
                    events.trySend(failed("Native generation crashed: ${t.message ?: t.javaClass.simpleName}"))
                } finally {
                    events.close()
                }
            }
            try {
                for (ev in events) emit(ev)
            } finally {
                // Collector cancelled (or finished): make sure native stops, then wait for it to unwind.
                if (worker.isActive) {
                    stopped.set(true)
                    try { backend.stop() } catch (_: Throwable) {}
                }
            }
        }
    }
}

/**
 * Exclusive hold on the engine for one API request (see [EngineController.acquireForApi]). [release] is idempotent;
 * [preempted] turns true when the in-app chat took the engine back and the request was cancelled.
 */
class ApiLease internal constructor(private val onRelease: () -> Unit) {
    @Volatile
    var preempted: Boolean = false
        private set
    private val released = AtomicBoolean(false)

    internal fun markPreempted() {
        preempted = true
    }

    fun release() {
        if (released.compareAndSet(false, true)) onRelease()
    }
}
