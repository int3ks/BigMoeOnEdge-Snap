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
//   6. a replayed conversation (clear_kv=true: edit, regenerate, chat switch) keeps the shared prefix and
//      answers exactly as a fresh session.
#include "bmoe/session.h"
#include "bmoe/runtime.h"
#include "bmoe/metrics.h"

#include <cstdio>
#include <memory>
#include <string>

using namespace bmoe;

static int failures = 0;
static void check(bool ok, const std::string & what) {
    std::printf("[%s] %s\n", ok ? "PASS" : "FAIL", what.c_str());
    if (!ok) ++failures;
}

static std::unique_ptr<Session> open_session(const char * path, int ckpts, bool moe, bool mtp_like_spec) {
    SessionConfig cfg;
    cfg.model_path = path;
    cfg.chatml = true;
    cfg.n_ctx = 1024;
    cfg.n_batch = 512;
    cfg.n_threads = 2;
    cfg.n_threads_batch = 2;
    cfg.ctx_checkpoints = ckpts;
    cfg.moe.enabled = moe;
    cfg.moe.cache_mb = moe ? 1 : 0;
    cfg.moe.force_cache = true;
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
    // checkpoint (at n_prompt) is one past the cut; the one at R1's prompt end serves.
    check(a3.summary.kv_reuse == KV_REUSE_CHECKPOINT && a3.n_reused == a1.n_prompt_total,
          tag + "regenerate restores the newest usable checkpoint (R1's prompt end)");
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

int main(int argc, char ** argv) {
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
    if (failures) {
        std::printf("\n%d check(s) FAILED\n", failures);
        return 1;
    }
    std::printf("\nall session checkpoint checks passed\n");
    return 0;
}
