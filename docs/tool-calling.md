# Web search & tool calling

## Web search modes (Settings → Web and image search; globe button in the chat)
The globe toggle is **off by default**. The `ALWAYS` mode below only applies after the user enables the globe.

* **Every message (default mode, `ALWAYS`)**: the loaded model first creates a short, hidden search query from the
  user's request and recent conversation context. The app searches that generated query, stores the results as a
  `TOOL` message, then runs the normal answer generation with `message + <tool_response>…`. If query generation
  fails or returns nothing, it safely falls back to a normalized version of the user's message. This adds one short
  model generation before each automatic search, but avoids blindly searching the raw message wording.
* **Only when the model asks (`AUTO`)**: the tool protocol below; small models often never call the tool.
* Provider order: Brave (if a key is set) → SearXNG (if an https URL is set) → **DuckDuckGo HTML (no account)**
  → Wikipedia search API. The DuckDuckGo path scrapes `html.duckduckgo.com/html/`; it can be rate-limited or
  change shape, which is why Wikipedia is the fallback and a failure is reported to the model as text.
* The UI shows "Searching the web for “…”", then a collapsible "Tool result" card with the sources. When Web Search is enabled, the model can also call `image_search`; this uses Wikimedia Commons, displays thumbnail cards in chat, and includes a source page plus available license/creator metadata. The image tool remains unavailable while the globe/Web Search toggle is off.

# Tool calling (Phase 7)

## Protocol (prompt plus an optional lazy grammar, see below)
System prompt (when "web search" is on) lists the tools and asks for exactly
`<tool_call>{"name": "...", "arguments": {...}}</tool_call>`. The app answers with a user turn
`<tool_response>\n[tool]\nresult\n</tool_response>`.

## Robustness
* `StreamOutputParser` hides tool-call blocks while streaming, including tags split across tokens.
* `ToolProtocol.parseCall` accepts code fences, surrounding prose, trailing commas, smart quotes, single quotes,
  `parameters`/`args` aliases, arguments encoded as a JSON string, and a truncated object.
* Invalid call → visible notice + a corrective `<tool_response>` telling the model to retry (`malformedFeedback`).
* At most 3 tool rounds and 3 calls per round; then a visible "limit reached" message.
* Tool errors, timeouts (20 s) and oversized results (6000 chars) are returned to the model as text; the app never
  throws because of a tool.
* Stop button ends the loop even while a tool is running.

## Grammar-checked tool calls (Snap fork)
Settings → Tools → Agent → "Grammar-checked tool calls" (on by default). `parser/ToolGrammar` builds a GBNF grammar for
`<tool_call>{"name": <one of the enabled tools>, "arguments": {<JSON object>}}</tool_call>` (up to 4 calls, whitespace
between, then only end-of-turn). `AgentController` sends it with every round as `GenerationRequest.toolGrammar`;
it reaches the engine as `GenerateRequest::tool_grammar` / `tool_grammar_trigger` (JNI `nativeGenerate`).

Engine side (`core/src/engine/tool_grammar.{h,cpp}`, `session.cpp`):
* A LAZY grammar (`llama_sampler_init_grammar_lazy_patterns`): free sampling until the output contains `<tool_call>`,
  then every token must continue the grammar. Prose before a call is unaffected.
* It wraps whatever the turn draws from (session chain, per-request chain, or argmax). Fast path: take the token the
  turn would take, check that single token against the grammar, and only on rejection resample with the grammar
  applied to the whole vocabulary.
* Speculative decoding: while the grammar is active, drafts are verified by drawing through it instead of argmax, so
  the grammar sees every emitted token in order; the draw that rejects a draft becomes the next token directly.
* Never accepts a token the grammar rejects or an end-of-generation inside an open call (llama.cpp aborts on both);
  a dead end ends the turn instead and the tolerant parser + retry take over.
* Skipped when the turn may reason first (`think` on, or `ThinkControl::None`): a `<tool_call>` mentioned inside a
  reasoning span would switch it on there. With "Think after tool results" off (default) this means every round
  after the first is constrained, and all rounds when Thinking is off.
* A grammar that does not parse is dropped with a log line; the turn runs unconstrained.
* The stateless API-server path is unchanged.

Tested on the host against the vendored llama.cpp with a vocab-only GGUF: valid call untouched, unknown tool name
overruled, end-of-turn refused mid-call, prose after a call refused, raw newline inside a JSON string refused, trigger
split over single-character tokens, 60,000 characters of prose before a call (about 0.5 ms per token on a desktop CPU
for the trigger search). Not yet run on a device.
