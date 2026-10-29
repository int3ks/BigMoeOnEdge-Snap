# BigMoeOnEdge+ Changelog

## Snap fork

- Shared workspace folder (Settings → Diagnostics & workspace → Workspace → "Shared workspace folder"): moves the workspace from app-private storage to `Internal storage/BigMoe/workspace`, so Termux (after `termux-setup-storage`) and other apps with storage access can work on the same files, e.g. through a link `~/bigmoe -> ~/storage/shared/BigMoe/workspace`. Needs all-files access (Android 11+); the switch opens Android's grant page and continues once access is given. The move copies first and deletes a source file only once its copy is byte-identical; a different file already at the target is kept as "<name> (before move)"; files written during the move are picked up by a second pass. Artifacts and attachments stay linked because the database stores workspace-relative paths. A `.nomedia` marker keeps gallery and music apps out and is hidden from listings and the AI's file tools. Turning the switch off moves everything back. If all-files access is taken away later, the app opens the private workspace at its next start and says so in Settings; the shared files are left untouched. New: `workspace/WorkspaceMover.kt` (pure, JVM tests in `WorkspaceMoverTest`), `core/WorkspaceSwitcher.kt`, `ui/settings/WorkspaceSettings.kt`; `Workspace.relocate()`; FileProvider path `shared_workspace`.
- llama.cpp updated again, to Helldez/llama.cpp `bmoe/expert-ready-hook-2810` (396b6c4, upstream of 2026-10-28, the revision BigMoeOnEdge moved to): Hexagon MUL_MAT_ID row skipping, multicore HMX flash attention and op fusion (NPU prefill), DeepSeek V4.1 Flash, LiquidAI LFM3 / d1, MiniCPM-OCR, GLM5-Next / Laguna / MiniMax-M3 / Nemotron fixes, a DRY-sampler resource-exhaustion fix and recursion limits for grammars and JSON. The engine builds against it unchanged; local changes: patches/0007-llama-cpp-local-on-2810.patch.
- Engine fix from upstream BigMoeOnEdge (#446): the router's prediction worker is joined before the expert source frees the gate weights, so unloading or switching a model with predictive prefetch on cannot read freed memory.
- Own application id `com.bigmoe.onedge.snap` and app name "BigMoeOnEdge²" (first "BigMoeOnEdge Snap"), so this build installs next to BigMoeOnEdge+ (`com.bigmoe.onedge`). The Kotlin package stays `com.bigmoe.onedge`.
- Updated the vendored llama.cpp from 965f897 (2026-09-26) to Helldez/llama.cpp `bmoe/expert-ready-hook-2210-dl` (ad98071, the revision BigMoeOnEdge 0.38 pins). This adds the **Flint** architecture (`FlintForCausalLM`, e.g. flint-40B-A2.5B) and the Jinja `\x` hex escapes its chat template uses. Local llama.cpp changes kept on top: patches/0007.
- Engine core adapted to the newer llama.cpp `common` API (`common_chat_prompt`, `common_chat_input`, `common_batch` for the MTP driver, reasoning format set on the template inputs), the same way upstream BigMoeOnEdge did.
- `flint` added to the MoE streaming registry (standard `ffn_{gate,up,down}_exps`).
- APK builds: `.github/workflows/android-npu.yml` builds the NPU (Hexagon) APK on every push, `.github/workflows/android-apk.yml` the CPU-only APK on demand. Both are signed with the same release key (secrets `BMOE_KEYSTORE_*`, certificate pinned in the workflows), so every build installs as an update.
- Live tokens/second and live context use (`N / n_ctx ctx`) in the footer while a reply is generated; the engine now reports the context per token (`TokenMetrics::n_ctx_used`, passed through `onToken`). The rate is measured from the first generated token (so the prefill does not drag it down); the final figure after the turn is still the engine's own.
- A mouse right-click opens the context menu of a chat message or a chat in the list right away, like a long-press does with a finger.
- Every CI build counts the version up: 0.6.1, 0.6.2, ... (commits since the 0.6.0 baseline), with versionCode raised to match.
- Launcher icon: dark blue with light blue lettering and a red superscript 2.
- In-app update (Settings → App update): checks the fork's newest GitHub release, downloads its APK and hands it to the system installer (Android asks once to allow installs from this app). Releases are published by hand once a build is approved: the "Publish release" workflow takes the APK of the latest successful NPU build and releases it as v0.6.N.
- Guess ahead is set in one place: a "Guess ahead • all models" card above the model list with Off / MTP / n-gram, tokens drafted per pass, the MTP confidence floor and the n-gram match lengths. With MTP, a model that has a drafter GGUF attached uses it, any other model its own MTP head. The only per-model setting is which drafter is attached. Settings → Engine performance no longer carries guess-ahead controls.
- Context menus (chat messages, chat lists) open where they were right-clicked or long-pressed. Chat messages gain "Regenerate": the turn is answered again without editing it first.
- A Settings button in the chat's top bar, next to Home, chat files and the model picker.
- Settings open with "Engine performance" expanded and "Appearance" collapsed.
- LoRA adapters (e.g. an uncensored fine-tune), as in LlamaLauncher: Models → a model → "LoRA adapter" adds a LoRA GGUF (copied into app storage as lora-*.gguf, recognised by the `adapter.type` key in its GGUF header, not by its name) with a strength of 1.00–2.50 (default 1.75). The engine applies it at load (`llama_adapter_lora_init` + `llama_set_adapters_lora`, also on the MTP draft context when the model drafts with its own head). An adapter that does not fit the model is dropped with a warning and the model loads without it.
- Vision projectors (mmproj) are attached to a model like drafters and LoRA adapters (Models → a model → "Vision projector": pick one, import a file, or detach) and load together with it. Loading no longer asks for a projector, and the confirmation step only appears when the pre-flight checks warn about something. A model loaded with a projector before this keeps it as its attachment.
- The MTP drafter row is always shown under each model, so drafters can be attached while Guess ahead is off; they are used once MTP is switched on.
- The chat header shows one model under the chat title: the loaded one, or else the last model this chat used (instead of every model the chat ever used).
- The Chats tab returns to where the person was: the open chat when Models or Settings was opened from it, otherwise the chat list. The Home button in a chat still opens the chat list.
- CPU cores (Settings → Engine performance → CPU compute & memory), replacing the old decode and prompt thread pickers: a table with one column per core (cpu0…cpu7, max clock, ★ = fastest, ✕ = blocked by Android) and two rows, Decode and Prefill. Each row pins its threads to the ticked cores and uses one thread per ticked core. Default for both rows: the first two and the last two cores (cpu0, cpu1, cpu6, cpu7 on an 8-core phone); an empty row falls back to it. A local llama.cpp hook (`llama_set_graph_compute_hook`, patches/0007) tells the app before every graph whether it is batched, and the app sets the computing thread to the decode or prefill cores; ggml's per-graph threads inherit them.
- Models page order: "Load the last model" switch, Guess ahead card, then the MTP drafters, LoRA adapters and vision projectors each in their own list, then the models.
- A separate MTP draft (MTP-only sidecar GGUF) is no longer limited to Qwen4Exp targets: Qwen3.5/3.6 (`qwen35`, `qwen35moe`) and other architectures with an MTP head can use one, as upstream BigMoeOnEdge's `--mtp-head` does. The sidecar is still checked against the target (architecture, MTP block, width, vocabulary). Qwen3.5/3.6 GGUFs usually carry the head already, so MTP works on them without a sidecar.

## Unreleased - NPU prefill + MTP fix

- NPU prefill now works together with Guess ahead (MTP and n-gram). Before, three independent gates refused the combination: `config.cpp` ("prefill.device does not combine with speculative decoding"), `native-bridge.cpp` (rejected any `I_SPEC_SOURCE`) and the Settings switch (`npuCombinationAllowed`). Removing the gates alone would have been unsafe, so the engine was also fixed: the MTP draft pass during prefill ran while the target's weights were rebound to the device (and with the arena hooked in), and the MTP head's layer, experts and KV were pulled into the device rebind. See docs/NPU_MTP_FIX.md.
- The NPU "minimum prompt tokens" threshold must now exceed the speculative verify width (draft_max + 1); the app raises it automatically and the Settings list only offers valid values.
- A separate MTP sidecar GGUF is loaded CPU-only while NPU prefill is on, so it does not open a second HTP session.
- MTP / n-gram Guess ahead can now be loaded together with an mmproj. Before, `session.cpp` refused both at load and on any image turn. Image turns decode plainly (the engine already disabled drafting for them), and both contexts' KV is cleared around them, so text turns afterwards re-prefill target and draft consistently.
- Not compiled with the NDK/Hexagon toolchain and not run on a device in this environment.

## Signing fix

- Added a stable release signing key mechanism (`app/build.gradle.kts`, env/secrets driven) and updated the NPU CI workflows to require it, so future APKs share one certificate and install as updates. See docs/SIGNING.md.
- One-time limitation: v0.5.5 and earlier were signed with a throwaway CI debug key that no longer exists, so the first build signed with the new key cannot update them; uninstall is required once.

## 0.6.0 — 2026-10-23

- **Auto settings** (Settings → top card): scans RAM, CPU cores/fast cores, SoC, a quick memory-speed test and whether the NPU runtime is available, then builds settings for the chosen model in three tiers: **Fastest**, **Balanced** and **Most accurate (slowest)**. MoE and dense models get different plans (expert cache, dropped/substituted experts, prefetch, guess-ahead, context, threads).
- Each save shows an **estimated tokens/second**; **Measure** loads the model with that save, generates 64 tokens and records the real speed, then restores the model that was loaded before. A "this may take a while" prompt shows a time estimate first, and measuring can be cancelled.
- **Saves**: named, switchable (Use), renamable, deletable, and downloadable/importable as small JSON files. Switching a save only changes the engine settings Auto settings owns; sampling, tools and theme are untouched.
- Measuring is crash-guarded: plans are kept inside a cautious RAM budget, a save that wouldn't fit is skipped instead of loaded, only one big load exists at a time, the screen stays on while measuring, and a crash marker lowers a save's memory use automatically if the app is killed mid-measurement. The Fastest tier no longer uses expert substitution, two-wave reads or a wide prediction window (the settings its first version crashed a 35B-A3B model with), and its expert-count cut is milder (8 → 6); older saves are cleaned automatically. NPU prefill is opt-in (off by default). Each save has a "Lower memory" button.
- Bumped to 0.6.0 (versionCode 11).

## 0.5.9 — 2026-10-22

- Fixed NPU unavailable at runtime ("HTP0 was not registered (devices: CPU)"): the manifest now declares `<uses-native-library android:name="libcdsprpc.so" android:required="false"/>`, which Android 12+ requires before an app can load Qualcomm's FastRPC library that ggml-hexagon dlopen()s.
- Fixed the NPU payload verifier false-failing under `set -o pipefail` (`strings | grep -q` SIGPIPE); it now greps the binary directly.
- Bumped versionCode to 10 so the NPU build installs as an update instead of being mistaken for the CPU-only 0.5.8 APK.

## 0.5.8 — 2026-10-22

- Fixed the Android NPU workflow to run Gradle from the project root and use the correct root-relative APK path.
- Hardened the Hexagon CMake path with explicit SDK/toolchain validation, the existing `PREBUILT_LIB_DIR=android_aarch64` selector, a fresh build directory, and stale-output invalidation. The reported SDK `string(FIND...)` failure is not reproduced here and remains pending a real toolchain CI run.
- Updated the native-build container reference to the documented Snapdragon toolchain `v0.8` image and added ELF/backend-marker/payload validation before packaging and after extracting files back out of the APK.
- Made the Hexagon build script remove only its own staged libraries instead of deleting the entire `jniLibs/arm64-v8a` directory.
- Added a source-level NPU validator and made settings/upstream wiring validators follow the current app version instead of hard-coding 0.5.7.
- Clarified that a successful CI package is not proof of on-device HTP execution; runtime verification on a supported Snapdragon device is still required.

This is an NPU build-pipeline hardening change. It does not claim an APK has been built or benchmarked in this environment.

## 0.5.7 — 2026-10-22

- Reorganized Engine Performance into four grouped cards: Hexagon prefill, MoE streaming/I/O, speed/quality, and CPU compute/memory.
- Added native-wired controls for batch/prefill threads, ubatch width, sampling seed, two-wave I/O, row-stream window, NPU routing thresholds, N-gram matching, drop normalization/prefill, and expert-stream diagnostics.
- Added the upstream decision-prefix-cache policy to the scored-choice API settings.
- Added an explicit force switch for expert-cache sizes below 1500 MiB while keeping the previous BigMoeOnEdge+ default behavior.
- Enabled I/O/compute overlap in fresh-install defaults. The native build still reports overlap inactive when the expert-ready hook is absent or streaming/cache is unavailable.
- Made Hexagon/NPU controls visible in CPU-only builds, disabled with an explanation instead of hiding the settings.
- Added a source-level parity inventory separating supported options from unsupported desktop/GPU/planner/debug-only options.
- Preserved BigMoeOnEdge+ identity, API, database, MTP sidecar flow, and custom UI.

This is a settings/UI/native-wiring update on the existing local engine baseline, not a wholesale merge of upstream 0.33.x.

## 0.5.6 — 2026-10-21

- Fixed host-side handling of routing index tensors whose buffers are owned by a non-host backend.
- Avoided streaming/rebinding device-owned expert weight tensors and restricted route-mutating diagnostics to host-readable tensors.
- Preserved BigMoeOnEdge+ UI, API, multimodal, persistence, split-GGUF, and custom MTP paths.
- Documented the current upstream boundary and verification limits in `docs/UPSTREAM_UPGRADE_0.5.6.md`.

This is a compatibility fix on the existing 0.28.0 engine baseline, revisioned locally as 0.28.1. It is not a wholesale merge of upstream 0.33.x.
