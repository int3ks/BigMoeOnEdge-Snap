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
