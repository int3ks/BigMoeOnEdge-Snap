#pragma once

// Context checkpoints, the way llama.cpp's server keeps them (tools/server, --ctx-checkpoints).
// Ported from upstream BigMoeOnEdge 0.36; this engine adds a byte budget over all of them.
//
// A chat turn reuses the KV prefix it shares with the last one and removes the rest. Not every
// memory can be cut at an arbitrary position: a recurrent state (Mamba, Gated DeltaNet) holds only
// the latest step, and a sliding-window cache has already dropped what is out of its window. When
// the cut is refused the context has to start again from nothing, and a coding agent's request
// diverges from the cached one on most turns: a client resends the answer as text and the template
// renders it back into tokens that need not be the ones the model sampled. On a hybrid model that
// cost a full re-prefill of a 12k-token prompt on five requests of eight.
//
// So the part of the state that cannot be cut is copied out at points of the prompt, through the
// public state API with LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY (common_prompt_checkpoint, the server's
// own type), and a refused cut rolls back to the newest checkpoint at or before it. Nothing here asks
// what kind of model this is: a memory that cuts anywhere is never asked for a checkpoint's help,
// and has no partial state to copy, so its checkpoints are empty and none is kept.

#include "common.h"
#include "llama.h"

#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <vector>

struct common_speculative;

namespace bmoe::detail {

// The contexts a checkpoint covers: the target, and, under MTP speculation, the draft context that
// mirrors its positions plus the speculative driver's own state.
struct CheckpointContexts {
    llama_context * tgt = nullptr;
    llama_context * dft = nullptr;
    common_speculative * spec = nullptr;
};

// How many tokens before a prompt's end the last checkpoints sit (llama.cpp's server, upstream #20288):
// a template renders a past turn's header and reasoning envelope again differently, a few tokens
// before the end of the prompt that sampled it, and a checkpoint has to be at or before that point.
inline constexpr int k_checkpoint_tail_tokens = 4;
// The checkpoints a turn needs at its end for the next request to reuse it: the one
// k_checkpoint_tail_tokens before the prompt's end, and the one at the end. The preamble's checkpoint
// is held back from eviction only when the list has room for it and for these.
inline constexpr int k_checkpoint_turn_slots = 2;
// Snap: when the list is full, a checkpoint an earlier request took this close behind the one before it
// goes first (the pair 4 tokens before and at a prompt's end). Upstream measures "close" with
// min_step (8192), so with 4 kept every older checkpoint but the preamble's counted as close and went:
// a second Regenerate found only the preamble and prefilled the whole conversation again.
inline constexpr int k_checkpoint_close_tokens = 4 * k_checkpoint_tail_tokens;

class ContextCheckpoints {
public:
    // max_count: checkpoints kept, the oldest dropped first (0 = none). min_step: the least spacing
    // between two of them, in tokens, except for the one at the end of a prompt. n_swa: the model's
    // sliding window (llama_model_n_swa), which decides how far back a checkpoint may reach.
    // max_bytes: all checkpoints together, the oldest dropped first while over it (0 = no limit); the
    // newest is always kept, since it is the one an edit or a regenerate restores.
    void configure(int max_count, int min_step, int n_swa, size_t max_bytes = 0);

    bool enabled() const { return max_count_ > 0; }
    // Whether a checkpoint after n_tokens is worth taking: the end of a prompt always is, a point
    // inside one only min_step past the last.
    bool wanted(int64_t n_tokens, bool prompt_end) const;
    // Where the prefill stops for a checkpoint, the way llama.cpp's server places them: at the start
    // of a user message (the last one always, an earlier one only min_step past the newest checkpoint)
    // and n_ubatch + k_checkpoint_tail_tokens and k_checkpoint_tail_tokens before the prompt's end. A template renders
    // a past turn again differently from how it was sampled (Qwen3.6 drops its think block), so the next request
    // diverges a few tokens before the end of this prompt, and a checkpoint only at the end is past
    // the divergence. The start of the first user message is also the end of the preamble a new
    // conversation shares. user_starts: token indices, ascending, from the template's own message
    // delimiters (common_chat_msg_spans); empty when the template gave none.
    struct Stops {
        const std::vector<size_t> * user_starts = nullptr;
        int64_t n_prompt = 0;
        int n_batch = 0;  // the prefill's piece
        int n_ubatch = 0; // the context's ubatch
    };
    // The first stop strictly inside (i, i + chunk), or i + chunk when there is none.
    int64_t next_stop(const Stops & s, int64_t i, int64_t chunk) const;
    // Whether a piece [i, i + chunk) starts where a checkpoint is taken (llama.cpp's do_checkpoint).
    bool wanted_at(const Stops & s, int64_t i, int64_t chunk) const;
    // Copy the cut-resistant state after the first n_tokens tokens of seq 0. Kept only when there is
    // such a state; returns whether one was kept.
    // turn: which request took it. When the list is full, a checkpoint an earlier request took within
    // min_step of the one before it goes first, then the oldest (llama.cpp's create_checkpoint), except
    // the preamble's. preamble: this one is at the start of the prompt's first user message, the end of
    // the system prompt and tools a new conversation shares. It is the most valuable checkpoint and the
    // oldest, so the oldest-first rule dropped it first: with 4 kept, a third new conversation prefilled
    // the preamble again. The newest such checkpoint goes last, when the list holds more than it and
    // k_checkpoint_turn_slots; one at another preamble takes its place.
    bool create(const CheckpointContexts & c, int64_t n_tokens, int turn = -1, bool preamble = false);
    // The context refused to drop everything from position n: restore the newest checkpoint that
    // leaves the first `kept` tokens reusable, kept <= n, and drop the context past it. Returns kept,
    // 0 when no checkpoint helps (the caller then clears the context).
    int64_t restore(const CheckpointContexts & c, int64_t n);
    // The context now holds fewer than n tokens of what the checkpoints describe.
    void drop_after(int64_t n);
    void clear() {
        list_.clear();
        preamble_ = -1;
    }

    size_t count() const { return list_.size(); }

    // For the KV snapshot on disk (Session::save_kv / load_kv): append the list to an open file, or
    // replace the list with one read back (kept within what configure() allows; nothing is kept when
    // checkpoints are off). Both return false on an I/O error or a malformed record.
    bool write(std::FILE * f) const;
    bool read(std::FILE * f);
    size_t bytes() const;

private:
    std::vector<common_prompt_checkpoint> list_;
    int max_count_ = 0;
    int min_step_ = 0;
    int n_swa_ = 0;
    size_t max_bytes_ = 0;
    int64_t preamble_ = -1; // n_tokens of the preamble's checkpoint, -1 when there is none
};

} // namespace bmoe::detail
