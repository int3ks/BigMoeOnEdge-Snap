# Upstream watch

What BigMoeOnEdge² has reviewed (and, where noted, taken over) from the projects it builds on. The weekly upstream
check reads this file, looks at what changed upstream since these commits, reports what is worth taking over, and then
moves the "last reviewed" commits forward. Nothing is taken over without the owner's OK.

| Upstream | Where | Last reviewed | Taken over |
| --- | --- | --- | --- |
| BigMoeOnEdge (engine + app) | https://github.com/Helldez/BigMoeOnEdge, default branch | `cb78bfe` (2026-10-31, after 0.39.0) | chat_parse / thinking_control / llama_backend / to_common_batch API adaptations; MTP head from a file of its own for any architecture; #446 prediction-worker teardown fix; context checkpoints (0.36); expert layout read from the file (#266, 0.34; registry kept for known architectures); cache brake (0.34); #348 read past end of file |
| Helldez llama.cpp (expert-ready hook fork) | https://github.com/Helldez/llama.cpp, branches `bmoe/*` | `bmoe/expert-ready-hook-2810` @ `396b6c4` | vendored in `app/src/main/cpp/third_party/llama.cpp` (local changes: `patches/0007`) |
| BigMoeOnEdge+ (the app this fork is built from) | https://github.com/hamodexd217/BigMoeOnEdge-plus, default branch | `b1e52c6` (0.6.0) | everything up to this commit |

## Notes for the check

* Worth reporting: new model architectures or MoE recipes, engine/streaming/MTP/NPU fixes, crash fixes, speed
  improvements, llama.cpp `bmoe/*` branches newer than the vendored one (and what they add), app features of
  BigMoeOnEdge+ that this fork does not have yet.
* Not worth reporting: desktop/CUDA/Vulkan/Metal-only work, CI of the upstream repos, docs-only changes, benchmarks.

## Engine baseline

The engine in `app/src/main/cpp/core` is upstream 0.28.0 plus this fork's patches and the ports listed above
(engine version `0.28.1` in `app/src/main/cpp/CMakeLists.txt`). Upstream rebuilt large parts after 0.29 (planner and
`--auto`, measured planning, device expert cache, server as a library), so newer upstream work is ported piece by
piece, not merged. Not taken over (not useful on the tablet or not applicable without the planner): the planner,
GPU/CUDA/ROCm/Metal device caches, the desktop app, the server agent and web UI, thinking off by default.
