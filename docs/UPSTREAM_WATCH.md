# Upstream watch

What BigMoeOnEdge² has reviewed (and, where noted, taken over) from the projects it builds on. The weekly upstream
check reads this file, looks at what changed upstream since these commits, reports what is worth taking over, and then
moves the "last reviewed" commits forward. Nothing is taken over without the owner's OK.

| Upstream | Where | Last reviewed | Taken over |
| --- | --- | --- | --- |
| BigMoeOnEdge (engine + app) | https://github.com/Helldez/BigMoeOnEdge, default branch | `c10d2c0` (2026-10-29) | chat_parse / thinking_control / llama_backend / to_common_batch API adaptations; MTP head from a file of its own for any architecture; #446 prediction-worker teardown fix |
| Helldez llama.cpp (expert-ready hook fork) | https://github.com/Helldez/llama.cpp, branches `bmoe/*` | `bmoe/expert-ready-hook-2810` @ `396b6c4` | vendored in `app/src/main/cpp/third_party/llama.cpp` (local changes: `patches/0007`) |
| BigMoeOnEdge+ (the app this fork is built from) | https://github.com/hamodexd217/BigMoeOnEdge-plus, default branch | `b1e52c6` (0.6.0) | everything up to this commit |

## Notes for the check

* Worth reporting: new model architectures or MoE recipes, engine/streaming/MTP/NPU fixes, crash fixes, speed
  improvements, llama.cpp `bmoe/*` branches newer than the vendored one (and what they add), app features of
  BigMoeOnEdge+ that this fork does not have yet.
* Not worth reporting: desktop/CUDA/Vulkan/Metal-only work, CI of the upstream repos, docs-only changes, benchmarks.
