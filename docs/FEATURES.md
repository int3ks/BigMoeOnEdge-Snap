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
  guess ahead, last use (`.used`), LoRA scale, decoding preset (`.sampling`, `core/SamplingPreset.kt`; applied per chat turn in `AgentController`). Model folder `Internal storage/Models` scanned automatically.
* **Engine ports from upstream 0.34–0.36**: context checkpoints (recurrent/SWA models restore instead of re-prefill;
  4 copies, 320 MiB), expert tensors read from the file (`expert_layout`, registry kept for known archs), cache brake
  (halves the cache under memory pressure; first 4 tokens of a turn ignored), #348 read-past-end fix.
* **Replay keeps the KV prefix** (edit / regenerate / chat switch with `clear_kv=true` no longer clears the KV).
* **KV snapshots on disk** (`Session::save_kv`/`load_kv` in `core/src/engine/session.cpp`, JNI `nativeSaveKv`/`nativeLoadKv`,
  `core/KvSnapshotStore.kt`, `EngineController.swapSnapshot`/`saveSnapshot`): saved on chat switch, load/unload and
  `MainActivity.onStop`, loaded before a replay for another chat; details in `docs/prompt-and-kv-design.md`.
* **Model archive / cache / Downloads** (`core/ModelArchive.kt`, `ui/models/ModelArchiveCard.kt`, `ModelViewModel` archive
  functions, `ModelManager.keepLinksFor` + `exportOptions`/`importOptions`): several sources in
  `models/.archive-sources.properties`, one index each (`.archive-index-<hash>.properties`), options sidecar
  `<first file>.options.properties` beside the model in its source; fetch target in `models/.archive-fetch-target`.
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
* **Regenerate variants and run info** (`data/repository/TurnVariants.kt`, `core/RunInfo.kt`, `ui/chat/TurnVariantBar.kt`):
  earlier answers kept per user message (`variants_json`), `‹ n/m ›` under the turn's last answer; on the last turn the
  shown answer becomes live, further up view-only. Each answer stores model + settings + lowest free RAM (`run_info`).
* **Performance log and settings advice** (`core/PerfLog.kt`, `core/PerfAnalyzer.kt`, `core/SettingsAdvice.kt`,
  `ui/settings/PerfLogScreen.kt`, table `perf_events`, DB v7): every load (all LoadConfig fields via reflection — the
  app is not minified), failed load, answer (prompt chars, prefilled tokens, ctx, decode/prefill, cache hits, min free
  RAM, Android thermal status/headroom, battery °C) and crash (MemoryWatch.onAbortedRun, matched to the newest load).
  The analyzer groups answers by model + speed/RAM settings, medians over non-throttled answers, ranks crash-free >
  decode t/s > time to first token, groups under 3 answers unranked. "Export for Claude" shares intro + summary + CSV;
  "Ask a model" loads the picked model and sends `SettingsAdvice.localPrompt` into a new chat; a `bmoe-settings` JSON
  block in an answer shows `SuggestedSettingsCard` (whitelist + ranges, Apply writes global settings).
* **Dictation and read aloud** (`speech/`): mic next to Send → `Dictation` (on-device `SpeechRecognizer` only, API 31+,
  no online fallback; partial results live in the message box, restarts after each pause, ends on silence or tap;
  missing language pack → `triggerModelDownload` on API 33+). "Read aloud" under answers → `SpeechOutput` (app-wide
  `TextToSpeech`, offline voices only, default engine then Speech Services by Google; `SpeakableText` drops code,
  links, Markdown, splits into ≤400-char sentence chunks, guesses de/en). Both are separate from the chat so a later
  conversation mode can chain them (feed TTS sentence chunks while generating). Problems → `SpeechIssueDialog` with a
  Play Store / voice-data button.
