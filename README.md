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

### Agent with Termux (new)
* **The model runs real commands:** with the separately installed [Termux](https://github.com/termux/termux-app) app,
  the new `run_shell` tool executes bash in the workspace – Python scripts, `pkg`/`pip` installs, ffmpeg, Pillow and so
  on. The agent decides on its own when to use it, checks whether a program or module is already there before
  installing it, and keeps going step by step (up to 20 tool rounds per answer by default) until the result is
  verified. Example: "make an audiobook from story.txt" → it installs a TTS package, writes and runs the script and
  names the MP3 files. Details below in [Agent mode with Termux](#agent-mode-with-termux) and in the
  [full guide](docs/wiki/Agent-mode-with-Termux.md).
* **Live status and cancel:** the chat shows what the agent is doing (writing a call, running `pip install …`, the
  latest output line, elapsed time) and a **Cancel** button that stops a hanging command with all its child processes.
* **Grammar-checked tool calls:** once the model starts a tool call, the engine only lets it write a valid call to an
  enabled tool, so small or low-bit models no longer break on malformed JSON.
* **Shared workspace folder:** the workspace can live in `Internal storage/BigMoe/workspace` (with `.nomedia`), where
  Termux and other apps can read and write it; the files are moved along in both directions.
* **Approval setting:** ask before every command and change (default) or allow everything for watched tasks.

### Engine and models
* **Newer llama.cpp:** the vendored tree moved from 965f897 (2026-09-26) to Helldez/llama.cpp
  `bmoe/expert-ready-hook-2810` (upstream of 2026-10-28), the revision BigMoeOnEdge uses. It brings faster Hexagon
  (NPU) MoE kernels, DeepSeek V4.1 Flash, LFM3 and more models, and the **Flint** architecture
  (`FlintForCausalLM`, e.g. flint-40B-A2.5B) and the Jinja hex escapes its chat template needs; Flint is also
  registered for expert streaming. Local changes on top are recorded in [`patches/0007`](patches/0007-llama-cpp-local-on-2810.patch).
* **NPU prefill builds on every push:** the Snapdragon Hexagon build runs in CI and is the APK that gets released.
* **MTP for every architecture:** a separate MTP drafter (MTP-only GGUF) is no longer limited to Qwen4Exp; Qwen3.5/3.6
  and others can use one. Most Qwen3.5/3.6 GGUFs carry their own MTP head anyway.
* **LoRA adapters** (e.g. an uncensored fine-tune), as in LlamaLauncher: attach a LoRA GGUF to a model with a strength
  of 1.00-2.50 (default 1.75). It is recognised by its GGUF header, applied at load (also to the MTP draft context) and
  dropped with a warning if it does not fit the model.
* **CPU core table:** pick the cores for decode (writing the answer) and for prefill (reading the prompt) separately,
  one thread per ticked core. Default: the first two and the last two cores. Implemented with a small llama.cpp hook
  that places each graph's threads before it runs.
* **KV cache type per model:** F16 (default), Q8_0 (about half the memory, nearly lossless) or Q4_0 (a quarter, costs
  quality) – the easiest way to afford a bigger context for agent work. A type the device cannot use falls back to F16
  with a note.

### Models page
* **Everything attached to the model:** the MTP drafter, the LoRA adapter and the vision projector (mmproj) are
  attached per model and load with it. Loading no longer asks for a projector, and the confirmation only appears when
  the pre-flight checks warn about something.
* **Guess ahead in one place:** a card for all models with Off / MTP / n-gram, tokens per pass, the MTP confidence
  floor and the n-gram match lengths. With MTP, a model uses its attached drafter, otherwise its own head.
* **Clear order:** autoload switch, Guess ahead card, then the MTP drafters, LoRA adapters and vision projectors in
  their own lists, then the models.
* **Collapsible model cards:** only the loaded model is open; the others fold into one line with a summary
  (`MTP • Vision • LoRA • KV Q8_0`), so a long list stays short.

### Chat
* **Live statistics:** tokens/second and context use count up while the answer is written.
* **Context menus where you click:** right-click (or long-press) opens the menu at the pointer, with a new
  **Regenerate** entry that answers a turn again without editing it.
* **One model under the chat title:** the loaded model, else the last one this chat used.
* **Wider model menu:** the model picker in the chat uses almost the full screen width and shows the complete file
  name, wrapped if needed.
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
| Add-ons per model | MTP drafter, LoRA adapter, vision projector (mmproj), KV cache type |
| Conversations | Persistent chats with drafts, live token streaming, stop, regenerate, edit |
| Reasoning | Streaming "Thinking" display |
| Web and tools | Web search with model-written queries, Wikimedia image search, file/code/web/utility tools behind approval |
| Agent | Multi-step tool use, bash/Python in Termux (`run_shell`), grammar-checked tool calls, live status with cancel |
| Attachments and vision | Text, code, archives, documents, images and video (sampled frames) |
| Artifacts and workspace | HTML, SVG and Mermaid previews, highlighted code, a workspace to browse and edit generated files (private or shared folder) |
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

### Agent mode with Termux

BigMoeOnEdge² can work as an **agent**: the model does not just answer in text, it runs commands on the device, reads
their results and keeps going until the task is really done – install what is missing, write a script, run it, check
the output, fix errors, run again. Typical jobs: turn a text into an audiobook, convert audio/video with ffmpeg, draw a
small cover picture with Pillow, unpack and sort files, write and test a Python script.

The commands run in the separately installed [Termux](https://github.com/termux/termux-app) app. Android 10+ does not
let an app execute programs from its own data folder, so BigMoeOnEdge² hands each command to Termux through Termux's
official `RUN_COMMAND` interface and waits for the answer. Without Termux nothing changes; the Termux switch simply has
nothing to use. **Full guide with examples and troubleshooting: [docs/wiki/Agent-mode-with-Termux.md](docs/wiki/Agent-mode-with-Termux.md).**

#### Setup (once)

Settings → *Tools, web search, pictures & videos* → **Tools → Termux** shows a status line for each step and copies
the commands with one tap.

1. **Install Termux** from [F-Droid](https://f-droid.org/packages/com.termux/) or
   [GitHub](https://github.com/termux/termux-app/releases) – not from Google Play – and open it once.
2. **In Termux:**
   ```bash
   termux-setup-storage
   mkdir -p ~/.termux && echo 'allow-external-apps = true' >> ~/.termux/termux.properties && termux-reload-settings
   ```
   Optional: `ln -s ~/storage/shared/BigMoe/workspace ~/bigmoe` to reach the workspace as `~/bigmoe` yourself.
3. **Shared workspace:** Settings → **Diagnostics & workspace → Shared workspace folder** → on (allow *All files
   access*). The workspace moves to `Internal storage/BigMoe/workspace`, which Termux can see; a `.nomedia` file keeps
   generated media out of the gallery.
4. **Permission:** Settings → Tools → Termux → **Grant permission** (*Run commands in Termux environment*).
5. **Test Termux** – it should answer with the workspace path. Then turn on the **Termux** switch (in Settings or with
   the tool chips above the chat input).

#### How it works

* With Termux on, the model gets the tool **`run_shell`** (`command`, optional `cwd` inside the workspace, optional
  `timeout_seconds` 5–1800, default 120) and a short instruction: use the shell **on its own** instead of telling you
  to run commands, work step by step, **check before installing** (`command -v ffmpeg >/dev/null || pkg install -y
  ffmpeg`, `python3 -c 'import gtts' 2>/dev/null || pip install gtts`), never start interactive programs, and only
  report success when exit code and output show it.
* Commands run **in the workspace**: files the model writes with `create_file` and files a script produces are the
  same files, visible in the Workspace screen.
* A small wrapper script records each command's process id and copies its output to `.bigmoe/` in the workspace
  (hidden in the app, removed afterwards). That gives the chat the live output line and lets a command be stopped.
* The model gets `exit code`, stdout and stderr back (Termux keeps the last ~100 KB of each) and reacts to errors in
  the next step.

#### In the chat

* A **live status row** shows what is going on: `Writing run_shell: python3 make_audio.py…` while the call is written,
  then `Termux: pip install gtts…` with the elapsed time and the latest output line (`› Successfully installed …`),
  then *Reading the result…*.
* **Cancel** next to the running command stops it with its whole process tree and tells the model it was cancelled;
  Stop does the same. A command that exceeds its time limit is stopped the same way and the model gets the output so
  far.
* Each result is a collapsible card: `Termux · exit 0 ✓`, `Termux · exit 1 ✗`, `Termux · cancelled`,
  `Termux · stopped (time limit)`.

#### Settings (Settings → Tools → Agent)

| Setting | What it does |
| --- | --- |
| Agent steps per answer | How many tool rounds one answer may chain: 6, 12, **20** (default), 30 or 50. At the limit the agent stops; write "continue" to go on. |
| Approval | **Ask before changes and commands** (default: a dialog shows each command and folder) or **Allow everything without asking** (for tasks you watch). |
| Grammar-checked tool calls | On by default: after `<tool_call>` the engine only accepts a valid call to an enabled tool (name and JSON). Not applied while the model reasons first (Thinking on); Guess ahead drafts are checked against it too. |
| Termux | The `run_shell` switch, status lines, permission button, setup commands and *Test Termux*. |

When the context is almost full, the agent stops before the next step and says so instead of producing a cut-off
answer.

#### Tips

* Models trained for tool use work best (Qwen3.5/3.6, Gemma 4); very small models lose track over many steps.
* Thinking off is faster for routine agent work and lets the tool grammar apply.
* Each round adds command and output to the context: 24–32k is more comfortable than 16k. A **Q8_0 KV cache**
  (Models page → model card) roughly halves its memory with almost no quality loss.
* With large MoE models, a slightly smaller expert cache leaves RAM for the KV cache and for Termux's own processes.

#### Safety

A command runs with **all of Termux's rights**: it can change or delete files in Termux and shared storage and use
the network. Text the model reads (web pages, files, attachments) can contain instructions it might follow. Keep
*Approval* on *Ask* unless you are watching the task.

### Artifacts and workspace

Generated files and code are handled as artifacts instead of plain chat text:

* HTML, SVG, and Mermaid previews

* Markdown-formatted text in chat responses

* Highlighted, copyable source-code artifacts

* Copyable code

* JavaScript execution only through an explicit, approved tool flow; code artifacts themselves are not implicitly executed

* Workspace browsing and file management
* Optional shared workspace folder (`Internal storage/BigMoe/workspace`, Settings → Diagnostics & workspace) that Termux and other apps can read and write; needs all-files access, and turning it off moves the files back to private storage

* Saving, editing, sharing, and downloading files

Common source formats such as Kotlin, Java, Python, C/C++, JavaScript/TypeScript, JSON, YAML, shell, Dockerfile, and SQL can be shown as code. Languages that need an external interpreter, such as Python, are highlighted, copyable code blocks only; Python fences are not turned into artifact chips and are not executed by the app itself – running code is what the agent does through Termux (see above). HTML/SVG and Mermaid artifacts have dedicated previews. HTTP(S) links from the chat open in the system browser; links clicked inside a sandboxed artifact preview also leave the preview and open externally.

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
* `app/src/main/cpp/third_party/llama.cpp`: vendored Helldez/llama.cpp `bmoe/expert-ready-hook-2810` (396b6c4,
  upstream of 2026-10-28) with the local changes in `patches/0007`.
* `app/src/main/cpp/native-bridge.cpp` and `EngineNativeBridge.kt`: the JNI bridge.

### Known limitations

* Speed varies a lot between phones and models; an app cannot change CPU clocks or the governor (that needs root), and
  Android or the phone maker can keep an app off some cores.
* LoRA adapters, MTP drafters for non-Qwen4Exp models and the CPU core table are new and still need more testing on
  real devices; an adapter or drafter that does not fit the model is dropped with a warning.
* Multimodal behaviour depends on the exact model and `mmproj` combination; video uses sampled frames.
* Web search needs a network connection and is slow with large local models.
* Grammar-checked tool calls are not applied on turns where the model reasons first (Thinking on); there a small
  model can still write a malformed call.
* The Termux agent is new: it depends on Termux being set up (see above), and how well it works depends strongly on
  the model. Commands run with Termux's rights; keep *Approval* on *Ask* for anything you do not watch.
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
