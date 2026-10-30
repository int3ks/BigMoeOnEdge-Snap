// Host test for bmoe::detail::ToolGrammarSampler against a tiny vocab-only GGUF.
#include "tool_grammar.h"
#include "llama.h"
#include <chrono>
#include <cmath>
#include <cstdio>
#include <fstream>
#include <map>
#include <sstream>
#include <string>
#include <vector>

static int failures = 0;
#define CHECK(c, msg) do { if (!(c)) { std::printf("FAIL: %s (line %d)\n", msg, __LINE__); ++failures; } } while (0)

struct Env {
    llama_model * model;
    const llama_vocab * vocab;
    int n;
    std::map<std::string, llama_token> id;
    std::string piece(llama_token t) const {
        char buf[128];
        int k = llama_token_to_piece(vocab, t, buf, sizeof buf, 0, true);
        return k > 0 ? std::string(buf, k) : std::string();
    }
};

// Logits that prefer `want` (score 10), then `second` (5), everything else 0.
static std::vector<float> prefer(const Env & e, llama_token want, llama_token second = -1) {
    std::vector<float> l((size_t) e.n, 0.0f);
    l[(size_t) want] = 10.0f;
    if (second >= 0) l[(size_t) second] = 5.0f;
    return l;
}

static std::string run(const Env & e, bmoe::detail::ToolGrammarSampler & s, const std::vector<std::pair<llama_token, llama_token>> & script,
                       std::vector<llama_token> * out_toks = nullptr) {
    std::string text;
    for (auto [want, second] : script) {
        auto l = prefer(e, want, second);
        llama_token t = s.sample(l.data());
        if (out_toks) out_toks->push_back(t);
        if (llama_vocab_is_eog(e.vocab, t)) { text += "<EOS>"; break; }
        text += e.piece(t);
    }
    return text;
}

int main(int argc, char ** argv) {
    llama_backend_init();
    llama_model_params mp = llama_model_default_params();
    mp.vocab_only = true;
    Env e;
    e.model = llama_model_load_from_file(argv[1], mp);
    if (!e.model) { std::printf("cannot load vocab\n"); return 2; }
    e.vocab = llama_model_get_vocab(e.model);
    e.n = llama_vocab_n_tokens(e.vocab);
    for (int t = 0; t < e.n; ++t) e.id[e.piece(t)] = t;
    std::ifstream f(argv[2]);
    std::stringstream ss; ss << f.rdbuf();
    const std::string grammar = ss.str();
    const llama_token EOS = llama_vocab_eos(e.vocab);
    auto T = [&](const std::string & p) { auto it = e.id.find(p); if (it == e.id.end()) { std::printf("no token %s\n", p.c_str()); std::exit(3); } return it->second; };

    // The grammar must parse.
    { bmoe::detail::ToolGrammarSampler s(e.vocab, grammar, "<tool_call>", nullptr); CHECK(s.active(), "grammar parses"); }
    // A broken grammar is dropped, not fatal.
    { bmoe::detail::ToolGrammarSampler s(e.vocab, "root ::= (", "<tool_call>", nullptr); CHECK(!s.active(), "broken grammar inactive"); }

    const std::vector<std::pair<llama_token, llama_token>> good = {
        {T("Hello"), -1}, {T("\n"), -1}, {T("<tool_call>"), -1}, {T("{\"name\": \""), -1}, {T("run_shell"), -1},
        {T("\""), -1}, {T(","), -1}, {T(" "), -1}, {T("\"arguments\": {"), -1}, {T("\"command\": \""), -1}, {T("ls -la"), -1},
        {T("\""), -1}, {T("}"), -1}, {T("}"), -1}, {T("</tool_call>"), -1}, {EOS, -1}};

    // (a) a correct call passes untouched, prose before it is free, EOS after it is allowed
    {
        bmoe::detail::ToolGrammarSampler s(e.vocab, grammar, "<tool_call>", nullptr);
        std::string out = run(e, s, good);
        CHECK(out == "Hello\n<tool_call>{\"name\": \"run_shell\", \"arguments\": {\"command\": \"ls -la\"}}</tool_call><EOS>", ("valid call kept: " + out).c_str());
        CHECK(s.corrections() == 0, "no corrections for a valid call");
    }
    // (b) same with a sampling chain as base (top-k / temp / dist)
    {
        llama_sampler * base = llama_sampler_chain_init(llama_sampler_chain_default_params());
        llama_sampler_chain_add(base, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(base, llama_sampler_init_temp(0.7f));
        llama_sampler_chain_add(base, llama_sampler_init_dist(42));
        bmoe::detail::ToolGrammarSampler s(e.vocab, grammar, "<tool_call>", base);
        std::string out = run(e, s, good);
        CHECK(out.rfind("Hello\n<tool_call>{\"name\": \"run_shell\"", 0) == 0, ("sampled valid call: " + out).c_str());
        CHECK(out.size() > 6 && out.substr(out.size() - 5) == "<EOS>", "sampled call ends");
        llama_sampler_free(base);
    }
    // (b2) greedy inside a call: with a very hot sampling chain as base, greedy=true still takes the best allowed token
    {
        llama_sampler * base = llama_sampler_chain_init(llama_sampler_chain_default_params());
        llama_sampler_chain_add(base, llama_sampler_init_temp(50.0f));
        llama_sampler_chain_add(base, llama_sampler_init_dist(7));
        bmoe::detail::ToolGrammarSampler s(e.vocab, grammar, "<tool_call>", base);
        int hits = 0;
        for (int i = 0; i < 50; ++i) {
            auto l = prefer(e, T("Hello"), T("\n"));
            if (s.sample(l.data(), /*greedy*/ true) == T("Hello")) ++hits;
        }
        CHECK(hits == 50, "greedy ignores the hot base chain");
        int hot = 0;
        for (int i = 0; i < 50; ++i) {
            auto l = prefer(e, T("Hello"), T("\n"));
            if (s.sample(l.data(), /*greedy*/ false) == T("Hello")) ++hot;
        }
        CHECK(hot < 50, "without greedy the hot chain does sample");
        llama_sampler_free(base);
    }
    // (c) unknown tool name is overruled; EOS in the middle of a call is refused (no abort)
    {
        bmoe::detail::ToolGrammarSampler s(e.vocab, grammar, "<tool_call>", nullptr);
        std::string out = run(e, s, {{T("<tool_call>"), -1}, {T("{\"name\": \""), -1}, {T("bash"), T("run_shell")}});
        CHECK(out == "<tool_call>{\"name\": \"run_shell", ("name corrected: " + out).c_str());
        CHECK(s.corrections() == 1, "one correction");
        auto l = prefer(e, EOS, T("\""));
        llama_token t = s.sample(l.data());
        CHECK(t == T("\""), "EOS refused mid-call, next best allowed token taken");
    }
    // (d) prose after a finished call is refused; EOS (second choice) ends the turn
    {
        bmoe::detail::ToolGrammarSampler s(e.vocab, grammar, "<tool_call>", nullptr);
        std::vector<std::pair<llama_token, llama_token>> script(good.begin() + 2, good.end() - 1);
        script.push_back({T("Hello"), EOS});
        std::string out = run(e, s, script);
        CHECK(out.size() > 5 && out.substr(out.size() - 5) == "<EOS>", ("no prose after the call: " + out).c_str());
        CHECK(s.corrections() == 1, "prose correction counted");
    }
    // (e) JSON with a raw newline inside a string is not possible: the grammar forces an escape or a quote
    {
        bmoe::detail::ToolGrammarSampler s(e.vocab, grammar, "<tool_call>", nullptr);
        std::string out = run(e, s, {{T("<tool_call>"), -1}, {T("{\"name\": \""), -1}, {T("read_file"), -1}, {T("\""), -1},
                                     {T(","), -1}, {T("\"arguments\": {"), -1}, {T("\"command\": \""), -1}, {T("\n"), T("\\")}});
        CHECK(out.back() == '\\', ("raw newline in string refused: " + out).c_str());
    }
    // (f) the trigger split over two tokens still fires (pieces "<tool" "_call>" do not exist here, so use chars)
    {
        bmoe::detail::ToolGrammarSampler s(e.vocab, grammar, "<tool_call>", nullptr);
        std::vector<std::pair<llama_token, llama_token>> script;
        for (char c : std::string("<tool_call>")) script.push_back({T(std::string(1, c)), -1});
        script.push_back({T("Hello"), T("{")});
        std::string out = run(e, s, script);
        CHECK(out == "<tool_call>{", ("char-wise trigger: " + out).c_str());
    }
    // (g) a long answer without any call: no slowdown or stack trouble from the trigger search
    {
        bmoe::detail::ToolGrammarSampler s(e.vocab, grammar, "<tool_call>", nullptr);
        auto t0 = std::chrono::steady_clock::now();
        auto l = prefer(e, T("Hello"));
        for (int i = 0; i < 12000; ++i) s.sample(l.data()); // 60,000 characters of prose
        auto l2 = prefer(e, T("<tool_call>"));
        s.sample(l2.data());
        auto l3 = prefer(e, T("Hello"), T("{"));
        llama_token t = s.sample(l3.data());
        double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
        std::printf("long prose: %.1f ms total, %.3f ms per token\n", ms, ms / 12002);
        CHECK(t == T("{"), "trigger after long prose still fires");
    }
    std::printf(failures ? "%d FAILURE(S)\n" : "ALL TOOL GRAMMAR TESTS PASSED\n", failures);
    llama_model_free(e.model);
    return failures ? 1 : 0;
}
