# CLAUDE.md — BigMoeOnEdge² (BigMoeOnEdge-Snap)

Read this first. It is the working memory of the project for a new Claude session (chat or Claude Code).
Last updated: 2026-11-01 (release v0.6.58).

## Owner and how to work with him

* Owner: Tom (Inteks, GitHub `int3ks`). **Talk German**, peer level, direct, no basic explanations or repetitions.
* Reliable information matters more than speed or tokens: verify before stating something as fact, ask instead of
  guessing. Brainstorm/propose first; he says when to build. When he says "mach"/"bau"/"release", do it.
* He tests on the device and reports back; he wants a release (APK) to test. Releases only on request
  ("mach ein Release"), but bundle changes — every CI run and release costs usage.
* Code, comments, commit messages, CHANGELOG, docs: **English**. Commit message trailer as the harness requires.
* Device: **Honor MagicPad 3 Pro** (Snapdragon 8 Elite Gen 5, 16 GB RAM, Hexagon NPU), Android, mouse + keyboard.
  Models he uses: Qwen3.6-35B-A3B (Q4), Gemma 4 (26B, 12B, E4B), Mellum, LFM2-24B-A2B, Qwen3.5/3.6 with MTP.
* Older docs in `docs/` (HANDOFF.md, STATUS.md, NEXT_CONVERSATION_PROMPT.md, …) are from the previous author of
  the BigMoeOnEdge+ base and partly outdated (e.g. "talk Arabic" does not apply). CHANGELOG.md "Snap fork" section is
  the authoritative history of this fork, newest first.

## What the project is

Android app (Kotlin/Compose) + native engine (C++) for running big MoE GGUF models on a tablet by streaming experts
from flash. Fork of BigMoeOnEdge+ (hamodexd217/BigMoeOnEdge-plus 0.6.0), engine from Helldez/BigMoeOnEdge.

* Repo: `int3ks/BigMoeOnEdge-Snap`, app id `com.bigmoe.onedge.snap`, Kotlin package `com.bigmoe.onedge`.
* Engine core: `app/src/main/cpp/core` = upstream BigMoeOnEdge **0.28 baseline + local patches + ported pieces**
  (engine version `0.28.1` in `app/src/main/cpp/CMakeLists.txt`). It is NOT upstream 0.38/0.39 — upstream rebuilt a
  lot after 0.29 (planner, `--auto`, server library); we port single features. See `docs/UPSTREAM_WATCH.md`.
* llama.cpp: vendored Helldez/llama.cpp `bmoe/expert-ready-hook-0411` (7ac98f4, upstream v0.9.0), local changes `patches/0007`.
* JNI: `app/src/main/cpp/native-bridge.cpp` ↔ `core/EngineNativeBridge.kt` ↔ `core/EngineBackend.kt` ↔
  `core/EngineController.kt` (load/generate/state; `EngineState`, `LoadConfig`, `ModelInfo` in `core/EngineTypes.kt`).

## Build, CI, release

* No Android SDK in the cloud sandbox: Kotlin compiles only in CI. C++ engine host tests run locally:
  `scripts/host-engine-tests.sh` (needs cmake, ninja, g++, python3 `gguf`+`numpy`; builds tiny random Jamba/llama
  models; tests file reader, expert layout, cache brake, context checkpoints/replay).
* CI `.github/workflows/android-npu.yml` on every push to main: `native-hexagon` (Snapdragon toolchain docker, engine
  cached by git tree hash of `app/src/main/cpp` + ccache) then `apk`. A newer push cancels the running one.
* **Version**: builds carry the NEXT release's version = newest tag `v0.6.N` + 1 (shown as notice "Version" on the run).
* **Release**: `release.yml` via REST (GraphQL/`gh workflow run` is blocked in the sandbox):
  `gh api -X POST repos/int3ks/BigMoeOnEdge-Snap/actions/workflows/release.yml/dispatches -f ref=main -f 'inputs[run_id]=<green npu run id>'`
  → tag `v0.6.N`, asset `BigMoeOnEdge-Snap-NPU-0.6.N-<sha>.apk`. Sandbox cannot edit/delete releases or branches.
* If the apk job fails with an SDK `InstallFailedException`, it is a runner flake: `gh api -X POST .../runs/<id>/rerun-failed-jobs`.
* Upstream repos are read via anonymous git clone (into the scratchpad, not the home dir — the stop hook complains
  about uncommitted clones).

## Features of this fork (main ones, see CHANGELOG for details)

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

## Open ideas / not done

* GitHub Actions use Node-20 based `@v4` actions (warning only): bump checkout/cache/upload/download-artifact/
  setup-java/gradle actions to newer majors with a test run.
* MTP drafters in the model folder are recognised by name only (`mtp-…`, `-mtp-`, `_mtp_`, `-shared-mtp`);
  size-based detection was suggested.
* Notion wiki (DE + EN pages "BigMoeOnEdge²") is not updated with the features since ~v0.6.30.
* Upstream watch: last reviewed Helldez/BigMoeOnEdge `00ab372` (0.40.2), BigMoeOnEdge+ `5d5aa97` (0.6.5), llama.cpp
  `bmoe/expert-ready-hook-0411`. Plus 0.6.5 was merged by a 3-way merge per file (base 0.6.0); not taken: AutoSaveStore
  per-model saves, response_format/JSON schema, nativeTelemetry.
* Unit tests: the APK workflow only assembles. `.github/workflows/unit-tests.yml` runs `testDebugUnitTest` on demand
  (REST dispatch, like release.yml). State 2026-11-08: 455 tests, 31 fail, all of them already failing before the
  Plus 0.6.5 port (every AgentControllerTest: IllegalArgumentException in `env()` → `loadModel`; plus DeviceTunerTest,
  LoadConfigTest defaults/index/csv, Artifact*, ToolProtocol smart quotes, WebSearch empty query, PaletteTest).
  Tests needing an HTTP server use `testutil/HttpServer.kt` (the JDK's com.sun server is not on the Android classpath).
* Not ported yet from upstream: `image-max-tokens` (Snap scales pictures to Settings → image size instead), the
  checkpoint after an image (Snap clears the KV around image turns).
