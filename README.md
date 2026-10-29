<p align="center">
  <img src="docs/assets/logo-snap.png" alt="BigMoeOnEdge²" width="240">
</p>

<h1 align="center">BigMoeOnEdge²</h1>

<p align="center">
  An Android chat app for running local GGUF models on-device, especially large Mixture-of-Experts models,
  with Snapdragon NPU prefill, MTP, LoRA adapters and in-app updates.
</p>

<p align="center">
  <a href="https://github.com/int3ks/BigMoeOnEdge-Snap/releases/latest">Download</a>
  ·
  <a href="https://github.com/int3ks/BigMoeOnEdge-Snap/issues">Issues</a>
  ·
  <a href="CHANGELOG.md">Changelog</a>
</p>

<p align="center">
  <a href="https://github.com/int3ks/BigMoeOnEdge-Snap/releases"><img src="https://img.shields.io/github/v/release/int3ks/BigMoeOnEdge-Snap?display_name=tag" alt="Latest release"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/int3ks/BigMoeOnEdge-Snap" alt="License"></a>
</p>

---

## What is BigMoeOnEdge²?

BigMoeOnEdge² is a fork of [BigMoeOnEdge+](https://github.com/hamodexd217/BigMoeOnEdge-plus), which is itself a fork of
[BigMoeOnEdge](https://github.com/Helldez/BigMoeOnEdge). It keeps the complete BigMoeOnEdge+ chat app (persistent
conversations, reasoning display, web search, tools, attachments, multimodal input, artifacts, workspace, local API
server) and the BigMoeOnEdge streaming engine, which reads the experts of a Mixture-of-Experts model from flash during
generation, so models larger than the phone's RAM become practical. On top of that it brings a newer llama.cpp, a
reworked model page and the changes listed below.

It installs **next to** BigMoeOnEdge+: its own application id (`com.bigmoe.onedge.snap`), its own name and a blue icon.

> **Disclosure:** like BigMoeOnEdge+, this fork was developed with heavy help from AI tools.

## What's new compared to BigMoeOnEdge+ 0.6.0

### Engine and models
* **Newer llama.cpp:** the vendored tree moved from 965f897 (2026-09-26) to Helldez/llama.cpp
  `bmoe/expert-ready-hook-2210-dl`, the revision BigMoeOnEdge 0.38 uses. This adds the **Flint** architecture
  (`FlintForCausalLM`, e.g. flint-40B-A2.5B) and the Jinja hex escapes its chat template needs; Flint is also
  registered for expert streaming. Local changes on top are recorded in [`patches/0007`](patches/0007-llama-cpp-local-on-2210-dl.patch).
* **NPU prefill builds on every push:** the Snapdragon Hexagon build runs in CI and is the APK that gets released.
* **MTP for every architecture:** a separate MTP drafter (MTP-only GGUF) is no longer limited to Qwen4Exp; Qwen3.5/3.6
  and others can use one. Most Qwen3.5/3.6 GGUFs carry their own MTP head anyway.
* **LoRA adapters** (e.g. an uncensored fine-tune), as in LlamaLauncher: attach a LoRA GGUF to a model with a strength
  of 1.00-2.50 (default 1.75). It is recognised by its GGUF header, applied at load (also to the MTP draft context) and
  dropped with a warning if it does not fit the model.
* **CPU core table:** pick the cores for decode (writing the answer) and for prefill (reading the prompt) separately,
  one thread per ticked core. Default: the first two and the last two cores. Implemented with a small llama.cpp hook
  that places each graph's threads before it runs.

### Models page
* **Everything attached to the model:** the MTP drafter, the LoRA adapter and the vision projector (mmproj) are
  attached per model and load with it. Loading no longer asks for a projector, and the confirmation only appears when
  the pre-flight checks warn about something.
* **Guess ahead in one place:** a card for all models with Off / MTP / n-gram, tokens per pass, the MTP confidence
  floor and the n-gram match lengths. With MTP, a model uses its attached drafter, otherwise its own head.
* **Clear order:** autoload switch, Guess ahead card, then the MTP drafters, LoRA adapters and vision projectors in
  their own lists, then the models.

### Chat
* **Live statistics:** tokens/second and context use count up while the answer is written.
* **Context menus where you click:** right-click (or long-press) opens the menu at the pointer, with a new
  **Regenerate** entry that answers a turn again without editing it.
* **One model under the chat title:** the loaded model, else the last one this chat used.
* **Settings button** in the chat's top bar, and the Chats tab returns to the chat you came from.

### App
* **In-app updates:** Settings → App update checks the newest GitHub release, downloads it and opens the installer.
* **Every build counts up** (0.6.1, 0.6.2, ...) and is signed with one fixed key, so each APK installs as an update.
* Settings open on Engine performance; the app has a dark blue icon with a red ².

The complete list is in [`CHANGELOG.md`](CHANGELOG.md) under "Snap fork".

## Download and updates

**[Download the latest APK](https://github.com/int3ks/BigMoeOnEdge-Snap/releases/latest)**, or update from inside the app: **Settings → App update → Update**
(Android asks once whether the app may install apps).

Releases are published by hand once a build has been tested; every push builds a test APK in GitHub Actions
(Actions → "Android NPU APK" → Artifacts). The NPU APK also runs on phones without a supported Snapdragon NPU; the
NPU prefill switch then simply stays unavailable.

## Highlights

| Area | Details |
| --- | --- |
| Local inference | GGUF models through the native BigMoeOnEdge streaming engine |
| Model support | Mixture-of-Experts and dense models; multi-shard GGUF import with validation |
| Speed-ups | Snapdragon NPU prefill, MTP and n-gram guess ahead, expert cache, I/O-compute overlap, CPU core pinning |
| Add-ons per model | MTP drafter, LoRA adapter, vision projector (mmproj) |
| Conversations | Persistent chats with drafts, live token streaming, stop, regenerate, edit |
| Reasoning | Streaming "Thinking" display |
| Web and tools | Web search with model-written queries, Wikimedia image search, file/code/web/utility tools behind approval |
| Attachments and vision | Text, code, archives, documents, images and video (sampled frames) |
| Artifacts and workspace | HTML, SVG and Mermaid previews, highlighted code, a workspace to browse and edit generated files |
| Telemetry | Live and final tokens/s, prefill time, cache hit rate, context use |
| Local API server | Anthropic Messages and OpenAI chat APIs for other apps (experimental) |
| Updates | In-app update from GitHub releases |

## Model support

Load `.gguf` models from the Models screen. MoE models are detected automatically and stream their experts; dense
models load the ordinary way. Architectures with an expert-streaming recipe include Qwen3/Qwen3.5/Qwen3.6 MoE, Qwen4Exp,
Gemma 4 MoE, gpt-oss, LFM2 MoE, DeepSeek V4, Ling 3.0, Nemotron-H MoE and **Flint**. Add an MTP drafter, a LoRA
adapter or an mmproj to a model in its row on the Models screen.

## Performance notes

Speed depends on the model, quantisation, context, storage speed, cache settings, phone and temperature. For large
MoE models decode is usually limited by how fast experts can be read from flash and RAM, not by the CPU: more or faster
cores often change little, a larger expert cache helps more. NPU prefill pays off on long prompts (hundreds to
thousands of tokens); short follow-up messages are about as fast on the CPU.

## Building

### In GitHub Actions (how the releases are made)
* `.github/workflows/android-npu.yml` builds the NPU APK on every push (Hexagon engine in the Snapdragon toolchain
  image, then the APK), signed with the release key.
* `.github/workflows/android-apk.yml` builds a CPU-only APK on demand.
* `.github/workflows/release.yml` ("Publish release", run by hand) publishes the APK of the latest successful NPU build
  (or a given run) as release `v0.6.N`.
* Signing needs the repository secrets `BMOE_KEYSTORE_BASE64`, `BMOE_KEYSTORE_PASSWORD` and `BMOE_KEY_ALIAS`; the
  workflows check every APK against the certificate pinned in them. See [`docs/SIGNING.md`](docs/SIGNING.md).

### Locally
Android Studio, Android SDK 35, JDK 17, an Android NDK and CMake 3.22.1.

```bash
git clone https://github.com/int3ks/BigMoeOnEdge-Snap.git
cd BigMoeOnEdge-Snap
./gradlew assembleDebug                 # CPU-only ARM64 debug APK
./gradlew testDebugUnitTest             # JVM unit tests
```

Native code is built in Release mode even for debug APKs, because an unoptimised engine is too slow for real use. The
NPU build needs the Snapdragon toolchain; see `scripts/build-hexagon-onedge-engine.sh`.

## Using the app

### Loading models
Open **Models**, import a `.gguf` (it is copied into app storage) or register a path, then tap **Load**. Attach an MTP
drafter, LoRA adapter or vision projector in the model's row; they load together with the model.

### Multimodal models
Vision-capable models need an `mmproj` projector attached to the model. Image and video support depends on the model,
projector and phone; video is handled as sampled frames.

### Web search and tools

Web search and tools run inside the normal chat workflow.

* Web features need network access.

* Searches and tool calls can take noticeable time.

* Tool execution can require additional model reasoning.

* Large local models make these operations considerably slower.

### Artifacts and workspace

Generated files and code are handled as artifacts instead of plain chat text:

* HTML, SVG, and Mermaid previews

* Markdown-formatted text in chat responses

* Highlighted, copyable source-code artifacts

* Copyable code

* JavaScript execution only through an explicit, approved tool flow; code artifacts themselves are not implicitly executed

* Workspace browsing and file management

* Saving, editing, sharing, and downloading files

Common source formats such as Kotlin, Java, Python, C/C++, JavaScript/TypeScript, JSON, YAML, shell, Dockerfile, and SQL can be shown as code. Languages that need an external interpreter, such as Python, are highlighted, copyable code blocks only; Python fences are not turned into artifact chips and are not executed by the app. HTML/SVG and Mermaid artifacts have dedicated previews. HTTP(S) links from the chat open in the system browser; links clicked inside a sandboxed artifact preview also leave the preview and open externally.

### Chat and attachments

Conversations persist through Room. Supported features include:

* Editing and copying messages

* Copying raw code blocks

* Persistent per-chat drafts

* Text and code attachments

* Images and video

* ZIP, JAR, TAR, and GZ archives

* DOCX, XLSX, PPTX, and other supported document formats

* File navigation and workspace operations

Attachment processing uses size limits, so very large files do not silently consume excessive memory.

### Local API server (experimental)

BigMoeOnEdge² can expose the model that is loaded in the app to other apps on the same device, for example Mobile Harness running Claude Code.

Turn it on in Settings → Local API server (the last section; tap its header to expand it).

|                |                                                                                                                                                                                                                                             |
| -------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Endpoints      | POST /v1/messages (Anthropic Messages, SSE streaming, tool_use, thinking blocks), POST /v1/messages/count_tokens (estimate), POST /v1/chat/completions (OpenAI), GET /v1/models, GET /health                                                |
| Address        | http://127.0.0.1:<port>, default port 8080. Loopback only unless you explicitly enable listening on the network                                                                                                                             |
| Authentication | An API key is generated the first time you turn the server on and is required by default. Send it as x-api-key: <key> or Authorization: Bearer <key>. It can be switched off only while the server is loopback-only                         |
| Model          | Whatever is loaded in the app. Any model name in a request is accepted                                                                                                                                                                      |
| Settings       | The server uses the app's own settings: Context size, Max tokens, Thinking, sampling and the MTP / guess-ahead options. The only server-specific controls are on/off, port, key, authentication, "Force server settings" and the queue wait |
| Long prompts   | The KV cache is reused when a request starts with the same tokens as the previous one, and only the new part is prefilled. event: ping keeps streaming clients alive during a long prefill                                                  |
| Concurrency    | One request at a time, with a short queue; if the queue wait runs out the server answers HTTP 529. The in-app chat has priority over API requests                                                                                           |
| Service        | Runs in a foreground service (with a notification and a Stop button) so Android does not kill it                                                                                                                                            |

Quick test from a computer (`adb forward tcp:8080 tcp:8080` first):

```bash
curl -s http://127.0.0.1:8080/v1/messages \
  -H "x-api-key: <your key>" -H "content-type: application/json" \
  -d '{"model":"any","max_tokens":64,"messages":[{"role":"user","content":"Say hi."}]}'
```

For Claude Code, point `ANTHROPIC_BASE_URL` at the endpoint and use the key as `ANTHROPIC_AUTH_TOKEN` (check the variable names against your Claude Code version). Claude Code sends very large prompts, so set the app's Context size to 32768 or more and reload the model; a big context costs RAM for the KV cache.

Full details, every curl command, and what was and was not verified: [`docs/API_SERVER.md`](docs/API_SERVER.md).

### Native engine

* `app/src/main/cpp/core`: the customised BigMoeOnEdge engine (0.28 baseline with local patches, see `patches/`).
* `app/src/main/cpp/third_party/llama.cpp`: vendored Helldez/llama.cpp `bmoe/expert-ready-hook-2210-dl` (ad98071) with
  the local changes in `patches/0007`.
* `app/src/main/cpp/native-bridge.cpp` and `EngineNativeBridge.kt`: the JNI bridge.

### Known limitations

* Speed varies a lot between phones and models; an app cannot change CPU clocks or the governor (that needs root), and
  Android or the phone maker can keep an app off some cores.
* LoRA adapters, MTP drafters for non-Qwen4Exp models and the CPU core table are new and still need more testing on
  real devices; an adapter or drafter that does not fit the model is dropped with a warning.
* Multimodal behaviour depends on the exact model and `mmproj` combination; video uses sampled frames.
* Web search needs a network connection and is slow with large local models.
* Tool calls are parsed from the model's text (no grammar constraint), so a small model can write a malformed call.
* The local API server is experimental: one request at a time, no images through the API, `count_tokens` is an estimate.

## Credits

Many thanks to the two projects this fork stands on:

* **[BigMoeOnEdge](https://github.com/Helldez/BigMoeOnEdge)** by Helldez: the streaming MoE engine, the expert-ready
  hook fork of llama.cpp, NPU prefill and MTP. BigMoeOnEdge² follows its llama.cpp revision.
* **[BigMoeOnEdge+](https://github.com/hamodexd217/BigMoeOnEdge-plus)** by hamodexd217: the complete Android chat app
  this fork is built from.

Also built on [llama.cpp](https://github.com/ggml-org/llama.cpp), Jetpack Compose, Room, DataStore and the Android
ecosystem. The native engine and vendored components keep their own licenses and notices.

## License

Apache License 2.0, like BigMoeOnEdge+. See [`LICENSE`](LICENSE).
