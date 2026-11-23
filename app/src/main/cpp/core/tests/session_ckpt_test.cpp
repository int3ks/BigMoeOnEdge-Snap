// Host test of the ported context checkpoints and expert-layout discovery, on a tiny random Jamba
// (attention + Mamba + MoE): its memory refuses a partial cut (recurrent layers), and "jamba" is
// not in arch_registry.cpp, so streaming only works if the layout is read from the file.
//
// What must hold:
//   1. the model streams (layout discovered);
//   2. a request that diverges right after the previous prompt restores the checkpoint
//      (kv_reuse == KV_REUSE_CHECKPOINT, n_reused == previous prompt length);
//   3. that answer is byte-identical to a fresh session's answer to the same request (lossless);
//   4. with ctx_checkpoints = 0 the same request is a full re-prefill (KV_REUSE_REFUSED), same answer;
//   5. the app's own chat path (clear_kv=false turns) still reuses the KV and gives the same answers;
//   7. a sliding-window model (third argument) with the window-only SWA cache stays lossless when a
//      cut reaches back past the window.
//   6. a replayed conversation (clear_kv=true: edit, regenerate, chat switch) keeps the shared prefix and
//      answers exactly as a fresh session.
#include "bmoe/session.h"
#include "bmoe/runtime.h"
#include "bmoe/metrics.h"
#include "bmoe/load_progress.h"
#include "context_checkpoints.h"

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <memory>
#include <string>
#include <utility>
#include <vector>

using namespace bmoe;

static int failures = 0;
static void check(bool ok, const std::string & what) {
    std::printf("[%s] %s\n", ok ? "PASS" : "FAIL", what.c_str());
    if (!ok) ++failures;
}

static std::unique_ptr<Session> open_session(const char * path, int ckpts, bool moe, bool mtp_like_spec, int n_batch = 512) {
    SessionConfig cfg;
    cfg.model_path = path;
    cfg.chatml = true;
    cfg.n_ctx = 1024;
    cfg.n_batch = n_batch;
    cfg.n_threads = 2;
    cfg.n_threads_batch = 2;
    cfg.ctx_checkpoints = ckpts;
    cfg.moe.enabled = moe;
    cfg.moe.cache_mb = moe ? 1 : 0;
    cfg.moe.force_cache = true;
    // BMOE_TEST_DISCARD_DROP=1: every offered cache entry is dropped before a lookup takes it back, so
    // the re-read path of the discardable cache runs (answers must stay identical).
    cfg.moe.cache_discard_drop = std::getenv("BMOE_TEST_DISCARD_DROP") != nullptr;
    if (mtp_like_spec) cfg.spec.source = DraftSource::ngram;
    std::string err;
    auto s = Session::open(cfg, err);
    if (!s) std::printf("open failed: %s\n", err.c_str());
    return s;
}

static RunResult ask(Session & s, const std::string & messages, int n = 16) {
    GenerateRequest r;
    r.chat_messages_json = messages;
    r.n_predict = n;
    r.think = false;
    return s.generate(r);
}

static const char * R1 = R"([{"role":"user","content":"Hello there"}])";
static const char * R2 =
    R"([{"role":"user","content":"Hello there"},{"role":"assistant","content":"the world"},{"role":"user","content":"Hello world"}])";

static void scenario(const char * path, bool moe, bool spec) {
    const std::string tag = std::string(moe ? "stream" : "mmap") + (spec ? "+ngram" : "") + ": ";
    auto fresh = open_session(path, 4, moe, spec);
    check(fresh != nullptr, tag + "opens (expert layout read from the file)");
    if (!fresh) return;
    const LoadProgress lp = load_progress_get();
    check(lp.step == LoadStep::Finish && lp.frac == 1.0f, tag + "the load reports its last step as done");
    const RunResult want = ask(*fresh, R2);
    check(want.ok, tag + "fresh answer to R2");

    auto s = open_session(path, 4, moe, spec);
    const RunResult a1 = ask(*s, R1);
    check(a1.ok && !a1.generated_text.empty(), tag + "R1 answers");
    const RunResult a2 = ask(*s, R2);
    std::printf("    R1 prompt=%d, R2 prompt=%d reused=%d kv_reuse=%d\n", a1.n_prompt_total, a2.n_prompt_total,
                a2.n_reused, a2.summary.kv_reuse);
    check(a2.ok && a2.summary.kv_reuse == KV_REUSE_CHECKPOINT, tag + "R2 diverges after R1's prompt: checkpoint restored");
    check(a2.n_reused == a1.n_prompt_total, tag + "the restored prefix is R1's whole prompt");
    check(a2.generated_text == want.generated_text, tag + "answer identical to a fresh session (lossless)");
    // Again: the newest checkpoint is now R2's prompt end; regenerate R2 (same prompt).
    const RunResult a3 = ask(*s, R2);
    check(a3.ok && a3.generated_text == want.generated_text, tag + "regenerate of R2 gives the same answer");
    // A regenerate must decode at least the prompt's last token again, so R2's own prompt-end
    // checkpoint (at n_prompt) is one past the cut; the one the prefill took 4 tokens before the
    // prompt's end serves (upstream #603, as llama.cpp's server places them).
    check(a3.summary.kv_reuse == KV_REUSE_CHECKPOINT && a3.n_reused == a2.n_prompt_total - 4,
          tag + "regenerate restores the newest usable checkpoint (4 tokens before R2's prompt end)");
    std::printf("    regenerate reused=%d of %d kv_reuse=%d\n", a3.n_reused, a3.n_prompt_total, a3.summary.kv_reuse);

    auto off = open_session(path, 0, moe, spec);
    ask(*off, R1);
    const RunResult b2 = ask(*off, R2);
    check(b2.ok && b2.summary.kv_reuse == KV_REUSE_REFUSED && b2.n_reused == 0,
          tag + "without checkpoints the same request is a full re-prefill");
    check(b2.generated_text == want.generated_text, tag + "and gives the same answer");
}

static void app_path(const char * path) {
    // The app's chat protocol: clear_kv=true starts a chat, clear_kv=false continues it.
    auto s = open_session(path, 4, true, false);
    auto ref = open_session(path, 0, true, false);
    for (Session * x : {s.get(), ref.get()}) (void) x;
    std::string out_s, out_r;
    for (int turn = 0; turn < 3; ++turn) {
        GenerateRequest r;
        r.prompt = turn == 0 ? "Hello there" : (turn == 1 ? "Hello world" : "the world");
        r.n_predict = 12;
        r.think = false;
        r.clear_kv = turn == 0;
        const RunResult a = s->generate(r);
        const RunResult b = ref->generate(r);
        check(a.ok && b.ok && a.generated_text == b.generated_text,
              "app path turn " + std::to_string(turn) + ": same answer with and without checkpoints");
        std::printf("    turn %d reused=%d/%d kv_reuse=%d (ref reused=%d kv_reuse=%d)\n", turn, a.summary.n_reused,
                    a.summary.n_prompt_total, a.summary.kv_reuse, b.summary.n_reused, b.summary.kv_reuse);
    }
}

// The app replays a conversation with clear_kv=true after an edit, a regenerate or a chat switch. The
// KV is now kept and diffed like any other turn: same answer as a fresh session, less prefilled.
static void replay_path(const char * path, int ckpts, bool recurrent = true) {
    const std::string tag = std::string(recurrent ? "" : "llama ") + "replay (checkpoints " + std::to_string(ckpts) + "): ";
    const bool moe = recurrent; // the dense llama has no experts to stream
    auto s = open_session(path, ckpts, moe, false);
    GenerateRequest r0;
    r0.prompt = "Hello there";
    r0.n_predict = 12;
    r0.think = false;
    r0.clear_kv = true;
    const RunResult a0 = s->generate(r0);
    GenerateRequest r1 = r0;
    r1.prompt = "Hello world";
    r1.clear_kv = false;
    const RunResult a1 = s->generate(r1);
    check(a0.ok && a1.ok, tag + "two turns");

    // Edit of the second message: the app replays turn 1 and its answer, with the new text.
    GenerateRequest edit = r0;
    edit.prompt = "the world";
    edit.clear_kv = true;
    edit.history = {{"user", "Hello there"}, {"assistant", a0.generated_text}};
    const RunResult e = s->generate(edit);
    auto fresh = open_session(path, ckpts, moe, false);
    const RunResult want = fresh->generate(edit);
    std::printf("    edit reused=%d/%d kv_reuse=%d\n", e.summary.n_reused, e.summary.n_prompt_total, e.summary.kv_reuse);
    check(e.ok && want.ok && e.generated_text == want.generated_text, tag + "edit answers as a fresh session does");
    if (!recurrent)
        check(e.summary.n_reused > 0 && e.summary.kv_reuse == KV_REUSE_PARTIAL,
              tag + "the edit keeps the conversation before it (KV cut at the change)");
    else if (ckpts > 0)
        check(e.summary.n_reused > 0 && e.summary.kv_reuse == KV_REUSE_CHECKPOINT,
              tag + "the edit keeps the conversation before it");
    else
        check(e.summary.n_reused == 0, tag + "without checkpoints a recurrent model prefills all of it");

    // Regenerate the edited answer: the same replay again.
    const RunResult g = s->generate(edit);
    check(g.ok && g.generated_text == want.generated_text, tag + "regenerate gives the same answer");
    std::printf("    regenerate reused=%d/%d kv_reuse=%d\n", g.summary.n_reused, g.summary.n_prompt_total,
                g.summary.kv_reuse);

    // A different chat: only what it shares (nothing here, no system prompt) may be reused.
    GenerateRequest other = r0;
    other.prompt = "the world";
    other.history = {{"user", "Hello world"}, {"assistant", "Hello"}};
    const RunResult o = s->generate(other);
    auto fresh2 = open_session(path, ckpts, moe, false);
    const RunResult want2 = fresh2->generate(other);
    check(o.ok && o.generated_text == want2.generated_text, tag + "switching chats answers as a fresh session does");
}

// The KV snapshot on disk: a chat saved in one session and loaded into a fresh one (app restart, chat
// switch) must continue exactly as a session that prefilled everything, and must not prefill it again.
static void snapshot_path(const char * path, int ckpts, bool recurrent, bool spec) {
    const std::string tag = std::string(recurrent ? "" : "llama ") + "snapshot (checkpoints " + std::to_string(ckpts) +
                            (spec ? ", ngram" : "") + "): ";
    const bool moe = recurrent;
    const std::string prefix = std::string(path) + ".snap" + std::to_string(ckpts) + (spec ? "s" : "");
    auto s = open_session(path, ckpts, moe, spec);
    check(!s->save_kv(prefix).ok, tag + "nothing to save before the first turn");
    GenerateRequest r0;
    r0.prompt = "Hello there";
    r0.n_predict = 12;
    r0.think = false;
    r0.clear_kv = true;
    r0.system_prompt = "the world Hello";
    const RunResult a0 = s->generate(r0);
    GenerateRequest r1 = r0;
    r1.prompt = "Hello world";
    r1.clear_kv = false;
    const RunResult a1 = s->generate(r1);
    check(a0.ok && a1.ok, tag + "two turns " + a0.error + a1.error);
    const Session::KvSnapshotResult saved = s->save_kv(prefix);
    check(saved.ok && saved.n_tokens == s->kv_tokens_held() && saved.bytes > 0, tag + "saved (" + saved.error + ")");

    // The app's replay of that chat with a third message.
    GenerateRequest next = r0;
    next.prompt = "the world";
    next.history = {{"user", "Hello there"}, {"assistant", a0.generated_text},
                    {"user", "Hello world"}, {"assistant", a1.generated_text}};
    const RunResult live = s->generate(next); // the session that still holds the chat

    auto fresh = open_session(path, ckpts, moe, spec);
    const RunResult want = fresh->generate(next);
    auto loaded = open_session(path, ckpts, moe, spec);
    const Session::KvSnapshotResult got = loaded->load_kv(prefix);
    check(got.ok && got.n_tokens == saved.n_tokens && loaded->kv_tokens_held() == saved.n_tokens,
          tag + "loaded into a fresh session (" + got.error + ")");
    const RunResult l = loaded->generate(next);
    std::printf("    snapshot %d tokens, %.2f MiB; replay reused=%d/%d kv_reuse=%d (live reused=%d kv_reuse=%d)\n",
                saved.n_tokens, (double) saved.bytes / (1024.0 * 1024.0), l.summary.n_reused, l.summary.n_prompt_total,
                l.summary.kv_reuse, live.summary.n_reused, live.summary.kv_reuse);
    check(l.ok && want.ok && l.generated_text == want.generated_text, tag + "continues as a fresh session does");
    check(l.summary.n_reused == live.summary.n_reused && l.summary.kv_reuse == live.summary.kv_reuse,
          tag + "reuses exactly what the session that kept the chat reuses");
    if (!recurrent || ckpts > 0) check(l.summary.n_reused > 0, tag + "the snapshot spares the prefill");

    // A second turn after the load, and a load over a running chat.
    GenerateRequest more = r1;
    more.prompt = "Hello there";
    const RunResult m1 = loaded->generate(more), m2 = fresh->generate(more);
    check(m1.ok && m2.ok && m1.generated_text == m2.generated_text, tag + "the turn after that matches too");
    const Session::KvSnapshotResult again = loaded->load_kv(prefix);
    const RunResult l2 = loaded->generate(next);
    check(again.ok && l2.ok && l2.generated_text == want.generated_text, tag + "loading over a running chat works");

    // Refused without harm: a missing snapshot, a damaged index, a different draft setup.
    check(!loaded->load_kv(prefix + ".none").ok, tag + "a missing snapshot is refused");
    if (std::FILE * f = std::fopen((prefix + ".aux").c_str(), "r+b")) {
        std::fputc('X', f);
        std::fclose(f);
    }
    check(!loaded->load_kv(prefix).ok && loaded->kv_tokens_held() > 0, tag + "a damaged index is refused, the chat is kept");
    const RunResult l3 = loaded->generate(next);
    check(l3.ok && l3.generated_text == want.generated_text, tag + "and the session still answers the same");
    for (const char * ext : {".kv", ".dft", ".aux"}) std::remove((prefix + ext).c_str());
}

// A cancelled load fails as "load cancelled" and leaves nothing behind; the next load works.
static void cancel_load_path(const char * path) {
    load_cancel_set(true);
    bool threw = false;
    try {
        auto s = open_session(path, 4, true, false);
        threw = (s == nullptr);
    } catch (...) {
        threw = true;
    }
    load_cancel_set(false);
    check(threw, "cancel: a load requested to stop does not open");
    auto s = open_session(path, 4, true, false);
    GenerateRequest r;
    r.prompt = "Hello there";
    r.n_predict = 4;
    r.think = false;
    r.clear_kv = true;
    const RunResult a = s->generate(r);
    check(a.ok, "cancel: the next load works");
    // A late request after the load must not stop a generation (the abort callback only looks while opening).
    load_cancel_set(true);
    const RunResult b = s->generate(r);
    load_cancel_set(false);
    check(b.ok && !b.cancelled && b.generated_text == a.generated_text, "cancel: a request after the load does not stop a reply");
}

// A sliding-window model (tiny gemma4, window 16, one full-attention layer) with the window-only SWA
// cache (swa_full off, upstream #580): answers far longer than the window, so every cut back past the
// window must come from a checkpoint (or a full prefill), never from the window that no longer holds it.
static void swa_path(const char * path) {
    const std::string tag = "swa: ";
    auto turn = [](const std::string & prompt, bool clear, std::vector<std::pair<std::string, std::string>> hist) {
        GenerateRequest r;
        r.prompt = prompt;
        r.n_predict = 120;
        r.think = false;
        r.clear_kv = clear;
        for (auto & h : hist) r.history.push_back({h.first, h.second});
        return r;
    };
    auto fresh_answer = [&](const GenerateRequest & r) {
        auto f = open_session(path, 4, /*moe*/ true, false, /*n_batch*/ 32);
        return f ? f->generate(r).generated_text : std::string("<open failed>");
    };
    auto s = open_session(path, 4, /*moe*/ true, false, /*n_batch*/ 32);
    check(s != nullptr, tag + "opens");
    if (!s) return;
    const GenerateRequest t1 = turn("Hello there, tell me a long story about a dragon and a knight who meet in a dark forest", true, {});
    const RunResult a1 = s->generate(t1);
    const GenerateRequest t2 = turn("And then what happened", false, {});
    const RunResult a2 = s->generate(t2);
    check(a1.ok && a2.ok && a1.generated_text.size() > 16, tag + "two long turns");
    // The same two turns as one replay in a fresh session: the app's chat path must be lossless.
    const GenerateRequest t2r = turn("And then what happened", true, {{"user", t1.prompt}, {"assistant", a1.generated_text}});
    check(a2.generated_text == fresh_answer(t2r), tag + "follow-up answers as a fresh session does");

    // Regenerate turn 2: cut back past a 40-token answer, far beyond the 16-token window.
    const RunResult g = s->generate(t2r);
    std::printf("    regenerate reused=%d/%d kv_reuse=%d\n", g.summary.n_reused, g.summary.n_prompt_total, g.summary.kv_reuse);
    check(g.ok && g.generated_text == a2.generated_text, tag + "regenerate past the window gives the same answer");
    check(g.summary.n_reused > 0, tag + "regenerate keeps the conversation instead of prefilling all of it");

    // Edit turn 2: the cut sits at the edited message's start.
    const GenerateRequest e = turn("Something else entirely", true, {{"user", t1.prompt}, {"assistant", a1.generated_text}});
    const RunResult er = s->generate(e);
    std::printf("    edit reused=%d/%d kv_reuse=%d\n", er.summary.n_reused, er.summary.n_prompt_total, er.summary.kv_reuse);
    check(er.ok && er.generated_text == fresh_answer(e), tag + "edit answers as a fresh session does");

    // Regenerate turn 1 (the furthest cut back) and then continue: both as fresh.
    const RunResult g1 = s->generate(t1);
    check(g1.ok && g1.generated_text == a1.generated_text, tag + "regenerate of the first turn gives the same answer");
    const RunResult c = s->generate(turn("And then what happened", false, {}));
    check(c.ok && c.generated_text == a2.generated_text, tag + "continuing after it answers as before");

    // Snapshot round trip on the window-only cache.
    const std::string snap = "swa-snap";
    // (the snapshot is taken after continuing turn 2 again above)
    const auto sv = s->save_kv(snap);
    auto s2 = open_session(path, 4, /*moe*/ true, false, /*n_batch*/ 32);
    const auto ld = s2 ? s2->load_kv(snap) : Session::KvSnapshotResult{};
    check(sv.ok && ld.ok, tag + "a snapshot saves and loads");
    if (ld.ok) {
        const GenerateRequest n3 = turn("What is the moral", true,
                                        {{"user", t1.prompt}, {"assistant", a1.generated_text},
                                         {"user", t2.prompt}, {"assistant", a2.generated_text}});
        const RunResult l = s2->generate(n3);
        std::printf("    snapshot replay reused=%d/%d kv_reuse=%d\n", l.summary.n_reused, l.summary.n_prompt_total, l.summary.kv_reuse);
        check(l.ok && l.generated_text == fresh_answer(n3), tag + "after the snapshot it answers as a fresh session does");
        check(l.summary.n_reused > 0, tag + "the replay after the snapshot reuses it");
    }
}

// Regenerate of the last answer right after a snapshot was loaded (the app: reload the model, open the
// chat, press Regenerate). Seen on the MagicPad with Qwen3.6 (7.04): the restored checkpoint 4 tokens
// before the prompt's end, and the answer ended after 1-4 tokens.
static void regen_after_snapshot(const char * path, bool moe) {
    const std::string tag = std::string("regen after snapshot") + (moe ? "" : " (llama)") + ": ";
    const std::string prefix = std::string(path) + ".regen";
    auto s = open_session(path, 4, moe, false);
    GenerateRequest r0;
    r0.prompt = "Hello there";
    r0.n_predict = 12;
    r0.think = false;
    r0.clear_kv = true;
    r0.system_prompt = "the world Hello";
    const RunResult a0 = s->generate(r0);
    GenerateRequest r1 = r0;
    r1.prompt = "Hello world";
    r1.n_predict = 24;
    r1.clear_kv = false;
    const RunResult a1 = s->generate(r1);
    const Session::KvSnapshotResult saved = s->save_kv(prefix);
    check(a0.ok && a1.ok && saved.ok, tag + "two turns, saved");
    GenerateRequest regen = r1;
    regen.clear_kv = true;
    regen.history = {{"user", "Hello there"}, {"assistant", a0.generated_text}};
    auto fresh = open_session(path, 4, moe, false);
    const RunResult want = fresh->generate(regen);
    const RunResult live = s->generate(regen);
    std::printf("    live regen reused=%d/%d kv_reuse=%d gen=%d\n", live.n_reused, live.n_prompt_total,
                live.summary.kv_reuse, (int) live.generated_text.size());
    check(live.ok && live.generated_text == want.generated_text, tag + "regenerate in the live session answers as fresh");
    auto loaded = open_session(path, 4, moe, false);
    const Session::KvSnapshotResult got = loaded->load_kv(prefix);
    const RunResult l = loaded->generate(regen);
    std::printf("    loaded regen reused=%d/%d kv_reuse=%d gen=%d (want %d) err=%s\n", l.n_reused, l.n_prompt_total,
                l.summary.kv_reuse, (int) l.generated_text.size(), (int) want.generated_text.size(), l.error.c_str());
    check(got.ok && l.ok && l.generated_text == want.generated_text, tag + "regenerate after loading the snapshot answers as fresh");
    const RunResult l2 = loaded->generate(regen);
    std::printf("    second regen reused=%d/%d kv_reuse=%d\n", l2.n_reused, l2.n_prompt_total, l2.summary.kv_reuse);
    check(l2.ok && l2.generated_text == want.generated_text, tag + "and again");
    check(l2.n_reused == l.n_reused, tag + "the second regenerate reuses as much as the first");
    for (const char * ext : {".kv", ".dft", ".aux"}) std::remove((prefix + ext).c_str());
}

// Where the prefill stops for checkpoints. On the host every user message start and n_ubatch + 4 / 4 tokens
// before the end; with a prefill device (one full expert sweep per piece) only the preamble's end and the 4-token
// tail, so a long conversation is not cut into many device pieces.
static void ckpt_stops_device() {
    bmoe::detail::ContextCheckpoints c;
    c.configure(4, 8192, 0);
    const std::vector<size_t> us = {12, 200, 400};
    auto ends = [&](bool device) {
        const bmoe::detail::ContextCheckpoints::Stops st{&us, 576, 512, 512, device};
        std::vector<int64_t> v;
        for (int64_t i = 0; i < 576;) {
            const int64_t e = c.next_stop(st, i, std::min<int64_t>(512, 576 - i));
            v.push_back(e);
            i = e;
        }
        return v;
    };
    const std::vector<int64_t> host = ends(false), dev = ends(true);
    check(host == std::vector<int64_t>({12, 64, 200, 400, 572, 576}), "host prefill stops at every user start and both tail points");
    check(dev == std::vector<int64_t>({12, 524, 572, 576}), "device prefill stops only at the preamble, the piece width and the 4-token tail");
}

// Auto cores while an answer runs: request_cores from the token callback is taken between two tokens (thread
// counts and compute pools change mid-decode); the answer stays the same and the live counters add up.
static void live_cores_path(const char * path, bool moe, bool spec) {
    const std::string tag = std::string("live cores ") + (moe ? "stream" : "mmap") + (spec ? "+ngram" : "") + ": ";
    auto ref = open_session(path, 4, moe, spec);
    auto s = open_session(path, 4, moe, spec);
    if (!ref || !s) { check(false, tag + "opens"); return; }
    GenerateRequest r;
    r.chat_messages_json = R1;
    r.n_predict = 24;
    r.think = false;
    const RunResult want = ref->generate(r);
    int cb_masks = 0;
    s->set_cores_callback([&](unsigned long long, unsigned long long) { ++cb_masks; });
    long long t0 = 0, ns0 = 0;
    int k0 = 0;
    s->live_decode(t0, ns0, k0);
    Session * sp = s.get();
    const RunResult got = s->generate(r, [&](const TokenMetrics & m) {
        // Early steps: a tiny random model may end its answer after a few tokens.
        if (m.step == 1) sp->request_cores(3, 3, 0, 0);
        if (m.step == 2) sp->request_cores(1, 2, 0, 0);
    }, nullptr);
    long long t1 = 0, ns1 = 0;
    int k1 = 0;
    s->live_decode(t1, ns1, k1);
    check(got.ok && got.generated_text == want.generated_text, tag + "same answer with the threads changed twice mid-decode");
    std::printf("    generated %d, requests taken %d\n", got.summary.n_generated, k1 - k0);
    check(got.summary.n_generated >= 2 && k1 - k0 == 2 && cb_masks == 2,
          tag + "both requests taken during the answer, the callback told each");
    check(t1 - t0 == got.summary.n_generated && ns1 > ns0, tag + "live counters: every emitted token and decode time counted");
    // A request made between answers waits for the next one; set_threads drops it.
    s->request_cores(2, 2, 0, 0);
    s->set_threads(2, 2);
    s->generate(r);
    int k2 = 0;
    s->live_decode(t1, ns1, k2);
    check(k2 == k1, tag + "set_threads drops a request not taken yet");
}

int main(int argc, char ** argv) {
    ckpt_stops_device();
    if (argc < 2) {
        std::printf("usage: %s tiny-jamba.gguf [tiny-llama.gguf]\n", argv[0]);
        return 2;
    }
    if (argc > 2) replay_path(argv[2], 4, /*recurrent*/ false);
    scenario(argv[1], /*moe*/ true, /*spec*/ false);
    scenario(argv[1], /*moe*/ false, /*spec*/ false);
    scenario(argv[1], /*moe*/ true, /*spec*/ true);
    app_path(argv[1]);
    replay_path(argv[1], 4);
    replay_path(argv[1], 0);
    if (argc > 2) {
        snapshot_path(argv[2], 4, /*recurrent*/ false, /*spec*/ false);
        // Guess ahead only on the model that can cut its KV anywhere: the tiny Jamba cannot roll a
        // rejected draft back (no bounded-rollback state), with or without a snapshot.
        snapshot_path(argv[2], 4, /*recurrent*/ false, /*spec*/ true);
    }
    snapshot_path(argv[1], 4, true, false);
    snapshot_path(argv[1], 0, true, false);
    cancel_load_path(argv[1]);
    regen_after_snapshot(argv[1], /*moe*/ true);
    live_cores_path(argv[1], /*moe*/ true, /*spec*/ false);
    live_cores_path(argv[1], /*moe*/ true, /*spec*/ true);
    if (argc > 2) live_cores_path(argv[2], /*moe*/ false, /*spec*/ true);
    if (argc > 3) swa_path(argv[3]);
    if (failures) {
        std::printf("\n%d check(s) FAILED\n", failures);
        return 1;
    }
    std::printf("\nall session checkpoint checks passed\n");
    return 0;
}
