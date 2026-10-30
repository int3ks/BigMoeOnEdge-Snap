# Agent mode with Termux

BigMoeOnEdge² can work as an **agent**: the local model does not only answer in text, it runs real commands on the
device, reads their results and keeps going until the task is done. Typical jobs:

* turn a text file into an audiobook (it installs a TTS package, writes a Python script, runs it, checks the MP3),
* convert or cut audio/video with ffmpeg, resize pictures, draw a small cover image with Pillow,
* unpack, search, rename or sort files, build a ZIP,
* write a Python script, run it, read the error, fix it and run it again.

The commands run in the separately installed [Termux](https://github.com/termux/termux-app) app. BigMoeOnEdge² itself
cannot run programs (Android 10+ forbids executing files from an app's own data folder), so it hands each command to
Termux through Termux's official `RUN_COMMAND` interface and waits for the answer. Without Termux the app works as
before; the Termux switch then simply has nothing to use.

---

## 1. One-time setup

Settings → **Tools, web search, pictures & videos → Tools → Termux** shows a status line for every step and copies the
commands below with one tap.

1. **Install Termux** from [F-Droid](https://f-droid.org/packages/com.termux/) or
   [GitHub](https://github.com/termux/termux-app/releases) – *not* from Google Play (that build is outdated and lacks
   the interface). Open it once so it finishes its first start.
2. **In Termux**, run:
   ```bash
   termux-setup-storage
   mkdir -p ~/.termux && echo 'allow-external-apps = true' >> ~/.termux/termux.properties && termux-reload-settings
   ```
   The first line gives Termux access to shared storage (and so to the workspace); the second allows other apps – this
   one – to send commands. Optional: `ln -s ~/storage/shared/BigMoe/workspace ~/bigmoe` makes the workspace reachable
   as `~/bigmoe` when you work in Termux yourself.
3. **Shared workspace:** Settings → **Diagnostics & workspace → Shared workspace folder** → on, and allow
   *All files access* when Android asks. The workspace moves to `Internal storage/BigMoe/workspace`, a folder Termux can
   see. Existing files are moved along; a `.nomedia` file keeps generated pictures and audio out of the gallery and
   music apps. (Turning the switch off moves everything back to private app storage.)
4. **Permission:** Settings → Tools → Termux → **Grant permission** (*Run commands in Termux environment*). If no
   dialog appears: App settings → Permissions → Additional permissions. If the entry is missing there, Termux was
   installed after BigMoeOnEdge²; installing the next update of this app makes Android offer it.
5. **Test Termux:** the button runs a tiny command and shows Termux's answer: `Termux answers from: <workspace path>`,
   the CPU architecture and the Python version (or a note that Python is not installed yet – the agent installs it
   itself when it needs it).
6. Turn on the **Termux** switch (Settings → Tools → Termux, or the tool chips above the chat input).

All four status lines – *Termux installed*, *Permission to run commands*, *Workspace in the shared folder* and the
test – should be green.

---

## 2. How it works

```
you ──► model ──► <tool_call> run_shell {"command": "..."} ──► BigMoeOnEdge² ──RUN_COMMAND──► Termux (bash)
                                                                    ▲                              │
                         model reads exit code + output ◄───────────┴──── stdout, stderr, exit ◄───┘
```

* When Termux is enabled, the model gets the tool **`run_shell`** (`command`, optional `cwd` inside the workspace,
  optional `timeout_seconds` 5–1800, default 120) plus a short instruction: use the shell **on its own**, without asking
  first and without telling you to run commands; work in steps; **check before installing** (e.g.
  `command -v ffmpeg >/dev/null || pkg install -y ffmpeg`, `python3 -c 'import gtts' 2>/dev/null || pip install gtts`);
  run `pkg update -y` if `pkg` fails; never start interactive programs; only report success when the exit code and the
  output show it, and name the files it made.
* Every command runs with **the workspace as the current folder**. A file the model writes with `create_file` and a
  file a Python script produces are the same files; you see them in the Workspace screen and can open, share or
  download them there.
* The app wraps the command in a small bash script that records the process id and copies the output to a log file in
  the hidden folder `.bigmoe/` inside the workspace. That is how the chat can show the latest output line live and how
  a command can be stopped. The files are removed when the command ends; `.bigmoe` is not shown in the Workspace
  screen.
* The model sees `exit code: N` and the stdout/stderr (Termux keeps the last ~100 KB of each stream; a cut is marked).
  If a command fails, the model reads the error and tries a fix in the next step.

### Agent steps

One answer may contain several tool rounds: install → write script → run → check → fix → run again. Settings → Tools
→ **Agent → Agent steps per answer** sets the limit: 6, 12, **20 (default)**, 30 or 50 rounds. When the limit is
reached the agent stops and says so; write "continue" to let it go on.

When the context is nearly full, the agent stops *before* the next step instead of running into a cut-off answer, and
tells you. Start a new chat, or raise the context size on the Models / Engine settings and reload.

---

## 3. What you see in the chat

* **Live status row** above the input while a tool runs: what it is doing (`Termux: pip install gtts…`), the elapsed
  time, and the **last output line** of the command (`› Successfully installed gtts-2.5.4`). While the model is writing a
  tool call you already see `Writing run_shell: python3 make_audio.py…`; after a command it shows *Reading the result…*.
* **Cancel button** next to the running command. It stops the command **including all child processes** (TERM, then
  KILL for the whole process tree) and tells the model "Cancelled by the user after N s", so it can react instead of
  waiting. The normal Stop button also stops a running command.
* **Timeouts:** if a command takes longer than its `timeout_seconds`, it is stopped the same way and the model gets
  the output so far with a note. The tool description tells the model to ask for a longer `timeout_seconds` for
  installs (up to 1800 s = 30 minutes).
* **Results** appear as collapsible tool cards titled `Termux · exit 0 ✓`, `Termux · exit 1 ✗`,
  `Termux · cancelled` or `Termux · stopped (time limit)`; tap one to see the full command output.

---

## 4. Approval and safety

Settings → Tools → **Agent → Approval**:

* **Ask before changes and commands (default):** every Termux command opens a dialog showing the exact command and the
  folder. File edits and deletes ask as well.
* **Allow everything without asking:** commands, edits and deletes run without a dialog. Convenient for a task you are
  watching; the agent can then work through many steps alone.

Keep in mind:

* A command runs with **all of Termux's rights**: it can change or delete files in Termux's home and in shared storage
  (all-files access is on for the shared workspace) and it can use the network.
* Text the model reads – web pages, downloaded files, documents you attach – can contain instructions it might follow
  ("prompt injection"). With *Allow everything*, do not let it work on untrusted content unattended.
* The workspace path is the only place the app *sends* commands to, but a command itself can `cd` anywhere Termux can
  go. The approval dialog is the real safeguard.

---

## 5. Grammar-checked tool calls

Settings → Tools → Agent → **Grammar-checked tool calls** (on by default). As soon as the model writes `<tool_call>`,
the engine only allows tokens that continue a **valid call to an enabled tool** (correct name, valid JSON). Small and
heavily quantised models then no longer fail on broken JSON or invented tool names. Details:

* It is not applied on turns where the model reasons first (Thinking on, or a model that always thinks), because the
  grammar would also constrain the reasoning.
* While it is active, *Guess ahead* (MTP / n-gram) drafts are checked against the grammar as well, so speculative
  decoding keeps working.
* If a model behaves oddly with it, switch it off; calls are then parsed from free text as before.

More in [`docs/tool-calling.md`](../tool-calling.md).

---

## 6. Which model, which settings

* **Tool use is the hard part**, not speed alone. Models trained for agentic tool calls work best; in practice
  Qwen3.5/3.6 (e.g. Qwen3.6-35B-A3B) and Gemma 4 are reliable choices. Very small models (≤ 4B) often lose track over
  many steps.
* **Thinking off** for agent work is usually faster and lets the tool grammar apply; turn it on for tasks that need
  planning.
* **Context:** each round adds the command and its output to the context. 16k fills up after a few long outputs; 24–32k
  is more comfortable if RAM allows.
* **KV cache per model (Models page → model card → KV cache):** `Q8_0` roughly halves the KV cache memory with almost no
  quality loss – the easiest way to afford a bigger context. `Q4_0` saves more but costs quality. F16 is the default.
  A type the device cannot use for that model falls back to F16 at load, with a note.
* **Expert cache:** for MoE models streamed from flash, a smaller expert cache leaves RAM for the KV cache and for
  Termux's own processes (Python, ffmpeg). If Android starts killing other apps during long runs, lower it a bit.

---

## 7. Examples

> Make an audiobook from `story.txt` in German, one MP3 per chapter.

The agent checks for Python and a TTS package, installs what is missing, writes `make_audiobook.py`, runs it, lists the
MP3 files with their sizes and tells you where they are.

> Draw a 128×128 cover for the audiobook: dark blue, a book and the title "Der Sturm".

It checks for Pillow (`python3 -c 'import PIL'`), installs it if needed, writes and runs a small drawing script and
reports `cover.png`.

> Cut the first 30 seconds of `talk.mp4` and save the audio as `intro.m4a`.

`command -v ffmpeg >/dev/null || pkg install -y ffmpeg`, then one ffmpeg call, then `ls -la intro.m4a` to check.

---

## 8. Troubleshooting

| Symptom | Fix |
| --- | --- |
| *Termux is not installed* although it is | It must be the F-Droid/GitHub build (`com.termux`). Reinstall this app's update once after installing Termux. |
| *Termux could not run the command … allow-external-apps* | Run the `allow-external-apps` line from step 2 in Termux, then `termux-reload-settings`. |
| *may not run commands in Termux yet* | Grant *Run commands in Termux environment* (step 4). |
| *the workspace is in the app's private storage* | Turn on *Shared workspace folder* (step 3). |
| `Permission denied` on files in the workspace | Run `termux-setup-storage` in Termux and allow storage access. |
| `pkg install` fails / *unable to locate package* | Let it run `pkg update -y` first; on a slow mirror run `termux-change-repo` once in Termux. |
| A command hangs | Tap **Cancel** next to it; the whole process tree is stopped. Often a program waited for input – the model then retries with `-y` or a non-interactive flag. |
| The agent explains commands instead of running them | Make sure the Termux switch is on for this chat (tool chips above the input). |
| The agent stops with a context note | Start a new chat, or raise the context size and use a `Q8_0` KV cache. |
| No result and no error after a long time | Open Termux once: Android may have put it to sleep. Excluding Termux from battery optimisation helps for long jobs. |
