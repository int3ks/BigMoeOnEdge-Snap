package com.bigmoe.onedge.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bigmoe.onedge.core.CpuTopology
import com.bigmoe.onedge.core.DeviceTuneRunner
import com.bigmoe.onedge.core.DeviceTuner
import com.bigmoe.onedge.core.EngineController
import com.bigmoe.onedge.core.EngineState
import com.bigmoe.onedge.core.GgufInspector
import com.bigmoe.onedge.core.ModelFile
import com.bigmoe.onedge.core.ModelKind
import com.bigmoe.onedge.core.ModelManager
import com.bigmoe.onedge.core.TuneException
import com.bigmoe.onedge.core.TuneParam
import com.bigmoe.onedge.core.TuneReport
import com.bigmoe.onedge.core.TuneResult
import com.bigmoe.onedge.core.TuneStatus
import com.bigmoe.onedge.data.local.DeviceTuneStore
import com.bigmoe.onedge.data.local.datastore.EngineSettings
import com.bigmoe.onedge.data.local.datastore.EngineSettingsDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class DeviceTuneUi(
    val models: List<ModelFile> = emptyList(),
    val selectedPath: String? = null,
    val isMoe: Boolean = true,
    val npuAvailable: Boolean = false,
    val report: TuneReport? = null,
    /** Non-null while a test runs. */
    val status: TuneStatus? = null,
    val message: String? = null,
    /** The settings' current value per parameter, to show next to the measured one. */
    val current: Map<TuneParam, Int> = emptyMap()
) {
    val running: Boolean get() = status != null
}

/** The Auto settings screen: measures the device-dependent settings with one model and applies what the user picks. */
class DeviceTuneViewModel(
    private val dataStore: EngineSettingsDataStore,
    private val engineController: EngineController,
    private val modelManager: ModelManager,
    private val store: DeviceTuneStore,
    availRamMb: () -> Long
) : ViewModel() {

    private val _ui = MutableStateFlow(DeviceTuneUi())
    val ui: StateFlow<DeviceTuneUi> = _ui.asStateFlow()
    private var job: Job? = null

    private val runner = DeviceTuneRunner(
        engine = engineController,
        availRamMb = availRamMb,
        coresFastestFirst = { CpuTopology.fastestFirst(engineController.allowedCpus()) },
        riskyPoint = { param, value -> store.markTesting(_ui.value.selectedPath, param, value) }
    )

    init {
        viewModelScope.launch {
            store.takeInterrupted()?.let { (path, param, value) ->
                say("The app closed last time while testing ${param.title} = ${DeviceTuneRunner.display(param, value)} " +
                    "with ${File(path).name} (most likely out of memory). That value is marked and will not be picked.")
            }
            _ui.update { it.copy(npuAvailable = runCatching { engineController.capabilities.htpPrefill }.getOrDefault(false)) }
            refreshModels()
        }
        viewModelScope.launch {
            dataStore.settingsFlow.collect { s -> _ui.update { it.copy(current = currentValues(s)) } }
        }
    }

    /** The settings' values; an Auto cache as -1 (shown as "Auto"; the test starts it at 2000 MiB). */
    private fun currentValues(s: EngineSettings): Map<TuneParam, Int> = TuneParam.values().associateWith {
        if (it == TuneParam.CACHE && s.cacheMb == com.bigmoe.onedge.data.local.datastore.CACHE_AUTO_MB) -1 else DeviceTuner.valueOf(it, s)
    }

    fun dismissMessage() = _ui.update { it.copy(message = null) }
    private fun say(text: String) = _ui.update { it.copy(message = text) }

    fun refreshModels() {
        viewModelScope.launch {
            val models = modelManager.listModels()
            val loaded = (engineController.state.value as? EngineState.Ready)?.info?.path
            val last = dataStore.settingsFlow.first().lastModelPath
            val pick = _ui.value.selectedPath?.takeIf { p -> models.any { it.path == p } }
                ?: listOfNotNull(loaded, last).firstOrNull { p -> models.any { it.path == p } }
                ?: models.firstOrNull()?.path
            _ui.update { it.copy(models = models) }
            pick?.let { selectModel(it) }
        }
    }

    fun selectModel(path: String) {
        if (_ui.value.running) return
        viewModelScope.launch {
            val kind = withContext(Dispatchers.IO) { runCatching { GgufInspector.inspect(File(path)).kind }.getOrDefault(ModelKind.UNKNOWN) }
            _ui.update { it.copy(selectedPath = path, isMoe = kind != ModelKind.DENSE, report = store.load(path)) }
        }
    }

    /** Tests everything (in the tuner's order). */
    fun startAll() = start(TuneParam.ORDER)

    /** Tests one parameter again, with the other parameters at their measured best. */
    fun retest(param: TuneParam) = start(listOf(param))

    private fun start(params: List<TuneParam>) {
        val path = _ui.value.selectedPath ?: return say("Pick a model first.")
        if (job?.isActive == true) return
        if ((engineController.state.value as? EngineState.Loading) != null) return say("A model is loading; wait for it.")
        job = viewModelScope.launch {
            val global = dataStore.settingsFlow.first()
            // The other parameters at their measured best (what an "apply all" would give), this one from the settings.
            val report = _ui.value.report
            var start = global
            if (params.size == 1) {
                report?.results?.values?.filter { it.param != params[0] }?.forEach { r ->
                    r.best?.let { start = DeviceTuner.applyTo(r.param, it, start, runner::coreMask) }
                }
            }
            val name = _ui.value.models.firstOrNull { it.path == path }?.displayName ?: File(path).name
            var current = report ?: TuneReport(path, name)
            try {
                val drift = runner.run(
                    modelPath = path,
                    isMoe = _ui.value.isMoe,
                    npuAvailable = _ui.value.npuAvailable,
                    start = start,
                    params = params,
                    onStatus = { st -> _ui.update { it.copy(status = st) } },
                    onResult = { r ->
                        current = current.copy(results = current.results + (r.param to r), updatedAt = System.currentTimeMillis())
                        _ui.update { it.copy(report = current) }
                        viewModelScope.launch { store.save(current) }
                    },
                    restore = global
                )
                current = current.copy(driftNote = drift, updatedAt = System.currentTimeMillis())
                store.save(current)
                _ui.update { it.copy(report = current) }
                say(if (drift != null) "Done. $drift" else "Done. Apply the values you want; they take effect at the next model load.")
            } catch (e: CancellationException) {
                say("Test cancelled. The values measured so far are kept.")
                throw e
            } catch (e: TuneException) {
                say(e.message ?: "The test could not run.")
            } catch (e: Throwable) {
                say("The test stopped: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                _ui.update { it.copy(status = null) }
            }
        }
    }

    fun cancel() {
        engineController.stopGeneration()
        job?.cancel()
    }

    /** Writes one measured value into the settings. */
    fun apply(param: TuneParam) {
        val best = _ui.value.report?.results?.get(param)?.best ?: return
        viewModelScope.launch {
            write(param, best)
            say("${param.title}: ${DeviceTuneRunner.display(param, best)} applied. It takes effect at the next model load.")
        }
    }

    fun applyAll() {
        val results = _ui.value.report?.results?.values?.filter { it.best != null } ?: return
        if (results.isEmpty()) return
        viewModelScope.launch {
            results.forEach { write(it.param, it.best!!) }
            say("Applied ${results.size} value(s). They take effect at the next model load.")
        }
    }

    private suspend fun write(param: TuneParam, v: Int) = when (param) {
        TuneParam.DECODE_CORES -> {
            dataStore.updateCpuMask(runner.coreMask(v))
            dataStore.updateThreadCount(v)
        }
        TuneParam.PREFILL_CORES -> {
            dataStore.updateCpuMaskPrefill(runner.coreMask(v))
            dataStore.updateBatchThreadCount(v)
        }
        TuneParam.CACHE -> dataStore.updateCacheMb(v)
        TuneParam.IO_LANES -> dataStore.updateIoThreads(v)
        TuneParam.UBATCH -> dataStore.updateBatchWidth(v)
        TuneParam.NPU -> dataStore.updateNpuPrefill(v == 1)
        TuneParam.NPU_LOADERS -> dataStore.updateNpuLoaders(v)
        TuneParam.NPU_MIN_TOKENS -> dataStore.updateNpuMinTokens(v)
    }

    /** Which cores a core count means on this device, for the screen ("cpu6, cpu7, cpu0, cpu1"). */
    fun coresText(n: Int): String {
        val mask = runner.coreMask(n)
        return (0 until 64).filter { (mask shr it) and 1L == 1L }.joinToString(", ") { "cpu$it" }
    }

    /** The parameters this model and device can test, in the tuner's order. */
    fun params(): List<TuneParam> = TuneParam.ORDER.filter { (!it.moeOnly || _ui.value.isMoe) && (!it.npuOnly || _ui.value.npuAvailable) }

    fun resultOf(param: TuneParam): TuneResult? = _ui.value.report?.results?.get(param)
}
