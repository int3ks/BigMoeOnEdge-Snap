# Features of this fork

Moved out of CLAUDE.md to save tokens. CHANGELOG.md "Snap fork" section has the history.

* **Agent with Termux** (`tools/RunShellTool.kt`, `core/AgentController.kt`, `tools/ToolManager.kt`): run_shell via
  Termux RUN_COMMAND, wrapper with pid/rc files, history in `<workspace>/.bigmoe/history`, output tail 1000 chars,
  environment probe in the prompt, approval inline in the status row (atomic `GenerationManager.update`), Cancel ends
  one call (model gets an error and decides), Stop ends the run; invented `<tool_response>` is cut.
* **Grammar-checked tool calls** (lazy GBNF, `core/src/engine/tool_grammar.cpp`), greedy after `<tool_call>`.
* **Guess ahead** (MTP / n-gram) works with any temperature (verify by drawing). Mode is **per model** on its card
  (`.model-options.properties` `<model>.spec`, `EngineController.specProvider`); default mode and tuning in
  Settings → Guess ahead.
* **Per-model options** in `models/.model-options.properties`: KV cache type (default **Q8_0**, stored explicitly),
  guess ahead, last use (`.used`), LoRA scale. Model folder `Internal storage/Models` scanned automatically.
* **Engine ports from upstream 0.34–0.36**: context checkpoints (recurrent/SWA models restore instead of re-prefill;
  4 copies, 320 MiB), expert tensors read from the file (`expert_layout`, registry kept for known archs), cache brake
  (halves the cache under memory pressure; first 4 tokens of a turn ignored), #348 read-past-end fix.
* **Replay keeps the KV prefix** (edit / regenerate / chat switch with `clear_kv=true` no longer clears the KV).
* **Load progress** (`core/include/bmoe/load_progress.h`, JNI `nativeLoadProgress`, `EngineState.Loading.step/fraction`)
  shown in the model's card on the Models page (status, Reload/Unload live in the model card).
* **Auto settings = real device test** (`core/DeviceTuner.kt`, `core/DeviceTuneRunner.kt`, `data/local/DeviceTuneStore.kt`,
  `ui/settings/DeviceTuneScreen.kt`/`DeviceTuneViewModel.kt`): decode/prefill threads (fastest-N cores), expert cache,
  I/O lanes, ubatch, NPU on/off, NPU loaders, NPU min tokens. Coarse search, then fine search on the values the
  settings offer (`core/SettingChoices.kt` shared with the settings screen), 2 runs + median, 3 % noise threshold,
  live changes via JNI `nativeSetThreads` / `nativeSetCacheBudget`, crash marker per value, heat check, results per
  model, Apply/Retest per row. Applies to GLOBAL settings.
* **Settings export/import** (Settings top, `EngineSettingsDataStore.exportJson/importJson`; no paths, no API keys).
* Right-click menu in text fields (`ui/SecondaryClick.kt`, Compose 1.6 has none), edit message in the input bar,
  per-chat toggles (tools/thinking/web) in `ChatComposerDataStore`.
