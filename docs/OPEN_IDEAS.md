# Open ideas / not done

Moved out of CLAUDE.md to save tokens (state 2026-11-11).

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

* Settings: remove stale DataStore keys left by v0.6.71-0.6.73 (`prefill_gpu`, `dense_prefill`, `dense_decode`) so they no longer show up in the settings export (drop them on start or skip unknown keys when exporting).
* llama.cpp 0911 (Helldez `bmoe/expert-ready-hook-0911`, ac30987) was tried in v0.6.77/0.6.78 and reverted: with the NPU prefill the load hung (Hexagon host buffer now on by default, `opt_hostbuf`), and with `GGML_HEXAGON_HOSTBUF=0` the replies were garbage. Suspects: 0911's Hexagon `set_tensor_n` / runtime repacking (#31744) against `PrefillDevice`'s binding swap and `set_tensor` writes. Its `htp-kparams` solves matmuls for `ggml_hexagon_tensor_will_tile`, which would make the local htp-kparams fix unnecessary. Upstream engine 0.42.0 (Helldez/BigMoeOnEdge `bb20578`) reviewed: nothing portable.
* KV snapshots: confirm on the device with MTP (draft context file) and with the NPU prefill (KV in the Hexagon host
  buffer); no setting for the 6 GB budget yet; a deleted chat's snapshot goes only when the budget pushes it out.
* Model archive: the first fetch on the device (v0.6.80) was far too slow, cause open (drive, USB link, FUSE layer or
  the copy loop: 8 MiB blocks, RandomAccessFile -> FileOutputStream). The progress line shows MB/s since then; compare
  fetch into app storage vs model folder vs "Use in place" before changing the copy code. Attachments (drafter, mmproj)
  are separate archive entries and are fetched one by one.
* Archive network sources (not started, Tom decides later): SMB direct (Java SMB library, check it on MagicOS first),
  HTTP directory listing (`python -m http.server` on the PC), or SAF via a provider app. Fetch and move only - no
  "Use in place" without a file path.
* Q4_0 experts are not repacked (session.cpp loads with "no repack"), so Q4_0 decodes slower than Q4_1 on the CPU
  (perf log 2026-11-21: compute 0.28–0.30 vs ~0.19 s/token, 35B-A3B, 6 threads). Repacking an expert into the
  interleaved Q4_0 layout when it enters the cache would give Q4_0 the i8mm kernels; Q4_0 is already the NPU's native
  format (least RAM and flash per prefill). Measure: compute_s_per_token Q4_0 vs Q4_1, same cores and prompt.
* Decode cost grows with context: G4-MeroMero compute 0.165 → 0.31 s/token at ctx 0.8k → 7.3k (partly hot device).
  Check the CPU flash-attention path with Q8_0 KV against F16 KV.
* NPU prefill is still one full expert sweep per piece; a prompt longer than batchWidth costs a sweep per batch. A
  wider device graph or a layer-major device prefill would read the experts once per prompt.
