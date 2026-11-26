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
reworked model page and the features listed below.

Without hamodexd217's BigMoeOnEdge+ and Helldez's BigMoeOnEdge engine this project would not exist – see
[Credits](#credits).

It installs **next to** BigMoeOnEdge+: its own application id (`com.bigmoe.onedge.snap`), its own name and a blue icon.

> **Disclosure:** like BigMoeOnEdge+, this fork was developed with heavy help from AI tools.

## Features

### Agent with Termux
* **The model runs real commands:** with the separately installed [Termux](https://github.com/termux/termux-app) app,
  the `run_shell` tool executes bash in the workspace – Python scripts, `pkg`/`pip` installs, ffmpeg, Pillow and so
  on. The agent decides on its own when to use it, checks whether a program or module is already there before
  installing it, and keeps going step by step (up to 20 tool rounds per answer by default) until the result is
  verified. Example: "make an audiobook from story.txt" → it installs a TTS package, writes and runs the script and
  names the MP3 files. Details below in [Agent mode with Termux](#agent-mode-with-termux) and in the
  [full guide](docs/wiki/Agent-mode-with-Termux.md).
* **Live status and cancel:** the chat shows what the agent is doing (writing a call, running `pip install …`, the
  latest output line, elapsed time) and a **Cancel** button that stops a hanging command with all its child processes.
  A run of tool rounds folds into one line ("Reading https://… · 4 steps"); a tap unfolds every call and result.
* **Grammar-checked tool calls:** once the model starts a tool call, the engine only lets it write a valid call to an
  enabled tool, so small or low-bit models no longer break on malformed JSON.
* **Shared workspace folder:** the workspace can live in `Internal storage/BigMoe/workspace` (with `.nomedia`), where
  Termux and other apps can read and write it; the files are moved along in both directions.
* **Approval setting:** ask before every command and change (default) or allow everything for watched tasks.

### More tools
* **Ask user (`ask_user`):** the model can stop in the middle of an answer and ask a question with 2–4 options or a
  field for your own answer; the card sits above the input bar, so it stays visible above the keyboard.
* **Asking before the web:** with Ask user on, the first web access of a message asks "May I search the web for
  "…"?" – *Yes*, *Yes, always in this chat* or *No*. An answer of your own is used instead of the search result.
* **Web budget per message:** repeated searches or page reads get a short note instead of the page again, at most 2
  searches and 3 page reads per message, and a page read takes at most a fifth of the free context. Results per search:
  1, 3, 5 or 10 (default 3).
* **Help (`app_help`):** the model can look up offline how this app and its engine work (docs, changelog and settings
  texts are bundled as a search index).
* **Wiki (`wiki_search`):** your own knowledge from `.md`/`.txt` files in `Internal storage/BigMoe/wiki`; Settings →
  Tools → Wiki imports files or a Notion export (`.zip`).
* Every tool can be switched per chat in the chat's tools menu, with a short line saying what it does.

### Long chats and context
* **Auto-compaction** – the idea comes from hamodexd217's BigMoeOnEdge+ (0.7.0), from where it was ported: when the next round would use more than a set share of the
  context (default 60 %), the loaded model summarizes the oldest messages and the chat continues from that summary plus
  the newest messages. Nothing is deleted; the summary is a card in the chat. Unlike BigMoeOnEdge+, the summary request
  is appended to the running chat, so the engine reuses its KV cache instead of reading the old messages again.
* **KV snapshots on disk (experimental):** a chat's context is saved when you switch chats, unload the model or the
  app goes to the background, and loaded back when you return – also after a restart – so only what differs is
  prefilled again. "Forget saved KV of this chat" in the ⋮ menu drops it.
* **Replay keeps the KV prefix:** edit, Regenerate and chat switches no longer clear the cache; context checkpoints
  let Qwen3.5/3.6, Gemma and other hybrid or sliding-window models cut back without a full prefill.
* **Sliding-window layers keep only their window** in the KV cache (Gemma 4 26B at 16k context: about 2.3 GiB →
  0.4 GiB).

### Expert cache, RAM and cores
* **Expert cache & RAM** has its own settings group (cache, Auto, reserve, droppable share, cold experts, dense
  weights, row-gathered tables, model mapping).
* **Auto = a growing cache:** the model opens with the set cache size as the minimum; while RAM is free the cache
  grows in 250 MiB steps and shrinks again when another app needs memory (also during an answer), never below the
  minimum. The part above the minimum is offered to the kernel, so it is dropped at once instead of swapped.
* **Droppable cache share** (global and per model): only the coldest share of the cache is ever offered to the
  kernel. Cold experts has a 5 % step.
* **Chat header shows the memory situation:** free RAM, how much of the app sits in zram and the page faults per
  second; each answer records the most of the app in zram and the faults per token.
* **Auto cores:** the decode cores chosen in Settings are the minimum; Auto adds a core while the device stays cool
  and keeps it only if it really makes decode faster, and takes added cores away when Android reports throttling
  (thermal status and headroom, on Honor also its own throttle step). It decides during an answer, too.
* **Persistent compute threads, one core per thread:** the engine keeps its thread pools for decode and prefill and
  pins each thread to its own core. On a Galaxy S25 Ultra this lifted a stuttering decode from about 1.2 to about 6 t/s.
* **CPU core table:** pick the cores for decode and prefill separately, one thread per ticked core.

### Memory and NPU per model
* **Memory section in every model card:** NPU prefill (Default / On / Off), expert cache and droppable cache share
  **per model**. Applied at the next load.
* **Estimate before loading**, read from the GGUF tensor directory: expert cache, other weights in RAM and the extra
  memory the NPU needs, compared with the device's RAM. It only warns and never blocks.
* **Free RAM seen on the device:** the app remembers the lowest free memory at which the device still responded, so a
  rough safe limit emerges.
* **NPU prefill with fewer expert sweeps:** a long prompt is no longer cut into several device pieces, each of which
  streamed every expert from flash again.

### Models: Hugging Face, model folder and archive
* **Hugging Face page** (cloud button in the top bar): search GGUF repos, filter by quant format (several at once, e.g.
  Q4_0 and Q4_1) and by parameter range (e.g. 12B–60B), see each repo's total size, download single files or all
  parts of a split model. A format guide gives a rough speed estimate per quant on a tablet. The last search is kept.
* **Parallel downloads:** each file is loaded in ranges over several connections (default 5), straight into the model
  folder, and resumes after a network drop, an app update or a crash.
* **No models in the app's storage any more:** models live in the model folder (`Internal storage/Models` or a folder
  you pick) or in an archive; imports, LoRA adapters, drafters and projectors go to the model folder, and models an
  older version kept in the app storage are moved there.
* **Archive on external storage:** USB sticks, SSDs or SD cards are archive folders, several at once, each with its
  free space and whether it is connected. Per model: **Copy to Models** or **Use in place**. Models waiting in the
  Download folder can be moved to the model folder or the archive, models in the model folder to the archive. A
  running load can be cancelled.
* **Display name per model** (pencil on the card) without renaming the file.
* **Everything attached to the model:** MTP drafter, LoRA adapter (strength 1.00–2.50), vision projector, KV cache
  type (Q8_0 default, F16, Q4_0), Guess ahead (Off / MTP / n-gram) and a decoding preset load with it. Collapsed cards
  show a one-line summary.
* **Split GGUFs** load also when the first shard holds only metadata (e.g. Unsloth's UD quants).

### Comparing models and settings
* **Regenerate keeps the earlier answers:** `‹ 2/3 ›` pages through every answer Regenerate made; the chat continues
  from the one shown.
* **Each answer records what made it:** model name under each turn; a tap shows NPU, expert cache, threads, context,
  guess ahead, sampling, preset, system prompt and the lowest free RAM.
* **Decoding presets:** Coding, Agent work, Roleplay, Creative, Lifelike chat – in Settings → Decoding or per model.
* **Performance log** (Settings → Diagnostics): every load, failed load, answer and crash with all settings, speed,
  prompt and context size, free RAM, zram, heat and the engine's own figures; a summary per model ranks the settings
  crash-free first, then by speed. Crashes are only counted against the settings when the app ended right after an
  answer or load.
* **Export for Claude** (all entries or from a date, optionally with the log files) and **Ask a model:** a local model
  gets the summary and can suggest settings, each with its own **Apply** button.
* **Auto settings:** a real device test on the loaded model (threads, cache, I/O lanes, ubatch, NPU options).

### Chats, sync and sharing
* **Every chat is a file:** `BigMoe/chats/<title>.json`; the files are the source, the database only an index.
  Format: [`docs/CHAT_SYNC.md`](docs/CHAT_SYNC.md).
* **OneDrive sync** (Settings → Chats & OneDrive sync, sign-in in the browser): chats, their attachments and results
  and the server models go to `Apps\BigMoe` and to every other signed-in device; the newer change wins. Deleting a chat
  asks about its files; deleted files go to the OneDrive recycle bin. A chat deleted elsewhere stays here marked
  "deleted on OneDrive".
* **Chat switches are the chat's own:** thinking, web search, tools, Termux and the system prompt travel with the
  chat; the switches in Settings are the defaults for new chats.
* **Several named system prompts**, ★ marks the one new chats start with.
* **Share into BigMoe²:** text, pictures, PDFs and other files from Android's share sheet go into a new or recent chat.
* **Sending in a used chat loads its model** when none is loaded.
* **Dictation and read aloud:** a mic button dictates into the message box (offline, or with *Online speech (Google)*
  where the device has no on-device recognizer); a speaker button under an answer reads it aloud.

### Server models: OpenAI-compatible API
* Use a model on another machine when the tablet is not enough, e.g. **llama-server on the PC**: Models page →
  **Server models** → name, base URL (`http://192.168.x.x:8080/v1`), optional API key, model name and context size.
  The **agent, its tools and Termux work unchanged**. Not with a server model: the local API server, KV snapshots,
  live tuning. The API key is never part of the settings export.

### Engine and llama.cpp
* **Engine work from upstream BigMoeOnEdge 0.34–0.46**, ported onto this fork's engine: context checkpoints, MoE
  expert tensors read from the file, a cache brake, an expert cache that evicts by decayed frequency and no longer
  pushes Android into swap, failed expert reads ending the run cleanly.
* **llama.cpp** Helldez/llama.cpp `bmoe/expert-ready-hook-0411` (upstream v0.9.0): faster Hexagon (NPU) MoE kernels,
  DeepSeek V4.1 Flash, LFM3, **Flint** and more. Local changes in [`patches/`](patches/).
* **MTP for every architecture** with a separate MTP drafter; most Qwen3.5/3.6 GGUFs carry their own MTP head.

### App
* **App language from JSON files** (Settings → Appearance → Language): every `*.json` in `Internal
  storage/BigMoe/language` is offered; English is built in; new texts are added to existing files, for German with the bundled translations. Create a template for your language,
  share it for translation, or use the **translation mode** to translate the texts of the current page with a local
  model.
* **In-app updates:** Settings → App update checks the newest GitHub release, downloads it and opens the installer.
* **Version numbers since 7.00:** MAJOR.MM (7.00, 7.01 ... 7.99 → 8.00); every APK is signed with one fixed key and
  installs as an update.
* **Navigation:** page buttons in every top bar (Chats, Models, Hugging Face, Settings, Log); on narrow screens some sit
  in a ⋮ menu. Settings start collapsed; Diagnostics holds Auto settings, the Performance log and the log page
  (llama.cpp and engine output, optionally written to a file that survives a crash).
* **Settings export/import** (no paths, no API keys).

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
| Conversations | Chats as files with OneDrive sync, drafts, live token streaming, stop, Regenerate with variants, edit, auto-compaction |
| Reasoning | Streaming "Thinking" display |
| Web and tools | Web search with model-written queries, Wikimedia image search, file/code/web/utility tools behind approval |
| Agent | Multi-step tool use, bash/Python in Termux (`run_shell`), ask user, help and wiki tools, grammar-checked tool calls, live status with cancel |
| Models | Hugging Face search with parallel downloads, model folder, archive on external storage, server models (OpenAI API) |
| Memory | Growing expert cache, memory estimate per model, free RAM / zram / page faults in the chat header, Auto cores |
| Voice | Dictation and read aloud |
| Attachments and vision | Text, code, archives, documents, images and video (sampled frames) |
| Artifacts and workspace | HTML, SVG and Mermaid previews, highlighted code, a workspace to browse and edit generated files (private or shared folder) |
| Telemetry | Live and final tokens/s, prefill time, cache hit rate, context use, performance log with export |
| Language | App texts from JSON language files, translation mode |
| Local API server | Anthropic Messages and OpenAI chat APIs for other apps (experimental) |
| Updates | In-app update from GitHub releases |

## Model support

Load `.gguf` models from the Models screen. MoE models are detected automatically and stream their experts; dense
models load the ordinary way. Architectures with an expert-streaming recipe include Qwen3/Qwen3.5/Qwen3.6 MoE, Qwen4Exp,
Gemma 4 MoE, gpt-oss, LFM2 MoE (e.g. LFM2-24B-A2B), DeepSeek V4, Ling 3.0, Nemotron-H MoE, **Flint** and **Mellum**. Add an MTP drafter, a LoRA
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
  (or a given run) as release `vMAJOR.MM` (e.g. `v7.55`).
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
Easiest: put your `.gguf` files into **Internal storage/Models** (or pick another folder at the top of the Models page).
Everything in it is listed automatically and used in place, nothing is copied. Otherwise download one on the
**Hugging Face** page, import a `.gguf` (it goes into the model folder) or use one from an archive source, then tap
**Load**. Attach an MTP
drafter, LoRA adapter or vision projector in the model's row; they load together with the model.
A model on another machine (llama-server on the PC, any OpenAI-compatible API) is added under **Server models** and
used the same way.

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
3. **Shared workspace:** Settings → **Chats & OneDrive sync → Workspace → Shared workspace folder** → on (allow *All files
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
  then `Termux: pip install gtts…` with elapsed time and limit (`1 min 05 s / 10 min`, plus "no output for N min" when
  it goes quiet), then *Reading the result…*.
* **▸ Output** under the status row shows the latest line; tap it to unfold the command's whole output so far, which
  follows the end like a terminal (Python output appears as it is printed).
* **Cancel** next to the running command shows *Stopping…* and stops just that command with its whole process tree;
  the model gets an error and decides how to go on. **Stop** ends the whole run. A command that exceeds its time limit is stopped the same way and the model
  gets the output so far.
* Commands get no input, so a program that asks a question fails at once instead of hanging. If Termux does not start
  a command within 20 s (Android paused it), the agent gets a clear error; if Termux's answer gets lost, the app reads
  exit code and output from the command's files.
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
* Optional shared workspace folder (`Internal storage/BigMoe/workspace`, Settings → Chats & OneDrive sync → Workspace) that Termux and other apps can read and write; needs all-files access, and turning it off moves the files back to private storage

* Saving, editing, sharing, and downloading files

Common source formats such as Kotlin, Java, Python, C/C++, JavaScript/TypeScript, JSON, YAML, shell, Dockerfile, and SQL can be shown as code. Languages that need an external interpreter, such as Python, are highlighted, copyable code blocks only; Python fences are not turned into artifact chips and are not executed by the app itself – running code is what the agent does through Termux (see above). HTML/SVG and Mermaid artifacts have dedicated previews. HTTP(S) links from the chat open in the system browser; links clicked inside a sandboxed artifact preview also leave the preview and open externally.

### Chat and attachments

Every conversation is a file in `BigMoe/chats/` (see "Chats as files and OneDrive sync"); the app keeps a Room
index of them. Supported features include:

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

* `app/src/main/cpp/core`: the customised BigMoeOnEdge engine (0.28 baseline with local patches, see `patches/`, and
  later upstream engine work ported piece by piece, see `docs/UPSTREAM_WATCH.md`). Host tests: `scripts/host-engine-tests.sh`.
* `app/src/main/cpp/third_party/llama.cpp`: vendored Helldez/llama.cpp `bmoe/expert-ready-hook-0411` (7ac98f4,
  upstream v0.9.0) with the local changes in `patches/0007`.
* `app/src/main/cpp/native-bridge.cpp` and `EngineNativeBridge.kt`: the JNI bridge.

### Known limitations

* Speed varies a lot between phones and models; an app cannot change CPU clocks or the governor (that needs root), and
  Android or the phone maker can keep an app off some cores.
* LoRA adapters, MTP drafters for non-Qwen4Exp models and the CPU core table are new and still need more testing on
  real devices; an adapter or drafter that does not fit the model is dropped with a warning.
* NPU prefill needs extra RAM (several GiB with big models, see Memory in the model card); on a tablet without swap a
  too large cache plus NPU can freeze the device. The estimate warns, it does not stop you.
* Multimodal behaviour depends on the exact model and `mmproj` combination; video uses sampled frames.
* Web search needs a network connection and is slow with large local models.
* Grammar-checked tool calls are not applied on turns where the model reasons first (Thinking on); there a small
  model can still write a malformed call.
* The Termux agent is new: it depends on Termux being set up (see above), and how well it works depends strongly on
  the model. Commands run with Termux's rights; keep *Approval* on *Ask* for anything you do not watch.
* The local API server is experimental: one request at a time, no images through the API, `count_tokens` is an estimate.

## Credits

**BigMoeOnEdge² would not exist without these two projects and their authors. Thank you!**

* **[BigMoeOnEdge+](https://github.com/hamodexd217/BigMoeOnEdge-plus)** by **hamodexd217**: the complete Android chat
  app this fork is built from – conversations, tools, web search, attachments, artifacts, workspace, API server and
  the engine integration. Everything in this fork started from that work. A special thank-you for the kind words in
  the BigMoeOnEdge+ 0.7.0 release and for pointing its users here.
* **[BigMoeOnEdge](https://github.com/Helldez/BigMoeOnEdge)** by **Helldez**: the engine that makes all of this
  possible – streaming the experts of a Mixture-of-Experts model from flash, so models far larger than the device's
  RAM run on a tablet – plus the expert-ready-hook fork of llama.cpp, NPU prefill and MTP. BigMoeOnEdge² follows its
  llama.cpp revision.

If you like this app, please also give both projects a star.

Also built on [llama.cpp](https://github.com/ggml-org/llama.cpp), Jetpack Compose, Room, DataStore and the Android
ecosystem. The native engine and vendored components keep their own licenses and notices.

## License

Apache License 2.0, like BigMoeOnEdge+. See [`LICENSE`](LICENSE).
