#include "tool_grammar.h"

#include <cmath>
#include <cstdio>
#include <regex>

namespace bmoe::detail {

std::string ToolGrammarSampler::trigger_pattern(const std::string & word) {
    static const std::regex special_chars("[.^$|()*+?\\[\\]{}\\\\]");
    // Searched anywhere in the output so far (llama.cpp only anchors patterns written as ^...$). No [\s\S]* around
    // the word: std::regex matches those recursively, which gets slow, and on a long answer can exhaust the stack.
    return "(" + std::regex_replace(word, special_chars, "\\$0") + ")";
}

ToolGrammarSampler::ToolGrammarSampler(const llama_vocab * vocab, const std::string & grammar,
                                       const std::string & trigger_word, llama_sampler * base)
    : vocab_(vocab), n_vocab_(llama_vocab_n_tokens(vocab)), base_(base) {
    if (grammar.empty() || trigger_word.empty()) return;
    const std::string pattern = trigger_pattern(trigger_word);
    const char * patterns[] = {pattern.c_str()};
    // nullptr when the grammar does not parse (llama.cpp logs the parse error itself).
    grammar_ = llama_sampler_init_grammar_lazy_patterns(vocab, grammar.c_str(), "root", patterns, 1, nullptr, 0);
    if (!grammar_) std::fprintf(stderr, "bmoe: tool-call grammar did not parse; this turn runs unconstrained\n");
    cur_.reserve((size_t) n_vocab_);
}

ToolGrammarSampler::~ToolGrammarSampler() {
    if (grammar_) llama_sampler_free(grammar_);
}

void ToolGrammarSampler::fill(const float * logits) {
    cur_.resize((size_t) n_vocab_);
    for (int v = 0; v < n_vocab_; ++v) cur_[(size_t) v] = llama_token_data{(llama_token) v, logits[v], 0.0f};
}

llama_token ToolGrammarSampler::pick_without_grammar(const float * logits, bool greedy) {
    if (base_ && !greedy) {
        fill(logits);
        llama_token_data_array arr{cur_.data(), cur_.size(), -1, false};
        llama_sampler_apply(base_, &arr);
        if (arr.selected >= 0 && (size_t) arr.selected < arr.size) return arr.data[arr.selected].id;
    }
    llama_token best = 0;
    for (int v = 1; v < n_vocab_; ++v)
        if (logits[v] > logits[best]) best = v;
    return best;
}

llama_token ToolGrammarSampler::sample(const float * logits, bool greedy) {
    llama_token id = pick_without_grammar(logits, greedy);

    // Cheap check of that one token (a lazy grammar that has not fired yet lets everything through).
    llama_token_data one{id, 1.0f, 0.0f};
    llama_token_data_array single{&one, 1, -1, false};
    llama_sampler_apply(grammar_, &single);

    if (std::isinf(one.logit) && one.logit < 0.0f) {
        ++corrections_;
        // Pick again from everything the grammar allows.
        fill(logits);
        llama_token_data_array arr{cur_.data(), cur_.size(), -1, false};
        llama_sampler_apply(grammar_, &arr);
        bool any = false;
        for (size_t i = 0; i < arr.size; ++i)
            if (!(std::isinf(arr.data[i].logit) && arr.data[i].logit < 0.0f)) { any = true; break; }
        if (!any) {
            // Nothing continues the call. Accepting anything now would abort inside llama.cpp, so end the turn here;
            // the app's tolerant parser and its "invalid tool call" feedback take it from there.
            dead_end_ = true;
            return llama_vocab_eos(vocab_);
        }
        if (base_ && !greedy) {
            llama_sampler_apply(base_, &arr);
            id = (arr.selected >= 0 && (size_t) arr.selected < arr.size) ? arr.data[arr.selected].id : -1;
        } else {
            id = -1;
        }
        if (id < 0) { // greedy, or a base chain that selected nothing: highest surviving logit
            size_t best = 0;
            bool have = false;
            for (size_t i = 0; i < arr.size; ++i) {
                const float l = arr.data[i].logit;
                if (std::isinf(l) && l < 0.0f) continue;
                if (!have || l > arr.data[best].logit) { best = i; have = true; }
            }
            id = arr.data[best].id;
        }
    }

    llama_sampler_accept(grammar_, id);
    if (base_) llama_sampler_accept(base_, id);
    return id;
}

} // namespace bmoe::detail
