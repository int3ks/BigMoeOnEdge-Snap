#pragma once

// Grammar-constrained tool calls for the app's chat path (see GenerateRequest::tool_grammar).
//
// A lazy llama.cpp grammar sampler sits in front of whatever the turn would otherwise draw from: the session's own
// sampling chain, a request-scoped one, or plain argmax. Until the trigger word appears in the output the grammar
// does nothing; from then on every token has to continue the grammar.
//
// Two properties the decode loop relies on:
//   * the grammar sees EVERY emitted token through sample(), in order, including tokens confirmed from a
//     speculative draft (the caller verifies drafts with sample() instead of argmax while this is active);
//   * it never calls llama_sampler_accept() with a token the grammar rejects, and never with an end-of-generation
//     token while a call is open (llama.cpp aborts the process on either). A dead end is reported instead.
//
// Uses only the public llama_sampler_* API.

#include "llama.h"

#include <string>
#include <vector>

namespace bmoe::detail {

class ToolGrammarSampler {
public:
    // `base` is borrowed (may be null = greedy) and must outlive this object. Builds nothing when `grammar` is empty
    // or does not parse; check active().
    ToolGrammarSampler(const llama_vocab * vocab, const std::string & grammar, const std::string & trigger_word,
                       llama_sampler * base);
    ~ToolGrammarSampler();
    ToolGrammarSampler(const ToolGrammarSampler &) = delete;
    ToolGrammarSampler & operator=(const ToolGrammarSampler &) = delete;

    bool active() const { return grammar_ != nullptr; }

    // The token to emit after logits row `logits` (n_vocab floats). Fast path: take what the turn would take anyway
    // and keep it when the grammar allows it; otherwise pick again with the grammar applied first. Accepts the
    // result into the grammar and the base chain. Sets dead_end() and returns an end-of-generation token without
    // accepting anything when no token can continue the grammar.
    llama_token sample(const float * logits);

    bool dead_end() const { return dead_end_; }

    // How often the grammar had to overrule the token the turn would have taken. For logs/telemetry.
    int corrections() const { return corrections_; }

    // Regex for llama_sampler_init_grammar_lazy_patterns that fires at the first occurrence of `word`, with the
    // grammar fed from the start of the word.
    static std::string trigger_pattern(const std::string & word);

private:
    void fill(const float * logits);
    llama_token pick_without_grammar(const float * logits);

    const llama_vocab * vocab_;
    int n_vocab_;
    llama_sampler * grammar_ = nullptr;
    llama_sampler * base_ = nullptr;
    std::vector<llama_token_data> cur_;
    bool dead_end_ = false;
    int corrections_ = 0;
};

} // namespace bmoe::detail
