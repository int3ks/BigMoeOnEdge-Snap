#pragma once

// Wiring for llama.cpp's reasoning parser. Kept separate from session.cpp so the seam that
// was silently broken (issue #49) is unit-testable without a model or a live session.
//
// Internal header: includes llama.cpp's `common` (not the stable public API), so it must not
// be pulled into core/include/bmoe/. See docs/seam.md.

#include "chat.h"

#include <string>

namespace bmoe::detail {

// Build reasoning-parser params from an applied chat template.
//
// The `common_chat_parser_params(const common_chat_params &)` convenience constructor copies
// only `format` and `generation_prompt` (chat.h) — it leaves the PEG `parser` arena empty, so
// `common_chat_parse` throws on the first token and reasoning markers leak into the answer.
// The serialized grammar has to be loaded explicitly (this is what the upstream server does).
common_chat_parser_params build_parse_params(const common_chat_params & cp);

// Warn exactly once that reasoning parsing failed and the raw stream is being shown instead.
// Returning the raw text is the right degradation, but doing it silently is how #49 stayed
// invisible; the next wiring regression should be noticed.
void warn_parse_failed_once(const char * what);

// Remove empty reasoning spans (`<think>` + only whitespace + `</think>`) and the whitespace after
// them. A model asked not to think can echo the empty span the template placed in its prompt; the
// parser, built for an answer without one, then leaves the tags in the shown text.
std::string strip_empty_think(const std::string & s);

// Whether `raw` is nothing but empty reasoning spans and whitespace. `spans` gets how many there
// were; an unclosed `<think>` at the end counts as one more (the model is opening the next).
bool only_empty_think(const std::string & raw, int & spans);

} // namespace bmoe::detail
