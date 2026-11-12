# CLAUDE.md — BigMoeOnEdge² (BigMoeOnEdge-Snap)

Read this first. It is the working memory of the project for a new Claude session (chat or Claude Code).
Last updated: 2026-11-11 (release v0.6.70).

## Owner and how to work with him

* Owner: Tom (Inteks, GitHub `int3ks`). **Talk German**, peer level, direct, no basic explanations or repetitions.
* Reliable information matters more than speed or tokens: verify before stating something as fact, ask instead of
  guessing. Brainstorm/propose first; he says when to build. When he says "mach"/"bau"/"release", do it.
* He tests on the device and reports back; he wants a release (APK) to test. Releases only on request
  ("mach ein Release"), but bundle changes — every CI run and release costs usage.
* Code, comments, commit messages, CHANGELOG, docs: **English**. Commit message trailer as the harness requires.
* **Save tokens** (weekly limit): skip unimportant steps, run tests only after bigger changes, read targeted excerpts, bundle changes into one push.
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
* llama.cpp: vendored Helldez/llama.cpp `bmoe/expert-ready-hook-0911` (ac30987, upstream of 2026-11-09), local changes
  `patches/0007` (regenerate it as the diff of the pristine branch against `third_party/llama.cpp` after a bump).
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

## Features and open ideas (read on demand)

* Feature overview with file names: `docs/FEATURES.md` (agent/Termux, grammar tool calls, guess ahead, per-model options,
  engine ports, replay, load progress, auto settings, settings export). Read it before touching one of those areas.
* Open ideas, upstream-watch state, unit-test state: `docs/OPEN_IDEAS.md`.

## Upstream policy (since 2026-11-09, after v0.6.59)

* The UI is considered good as it is and is developed here. **No more UI/app features from BigMoeOnEdge+.**
* Upstream merges only for the engine (Helldez/BigMoeOnEdge) or llama.cpp, and only when something makes it faster
  (or fixes a real bug). Report findings, Tom decides.

