# Open: Qwen3.8 Flash Next (qwen4exp) answers break off (postponed 2026-11-21)

File: `Huihui-Qwen3.8-Flash-Next-abliterated.i1-Q2_K.gguf` (125B, MagicPad, cache 4-5 GB).

## Symptom
Answers end after 1-30 tokens ("Ja,", "Hallo", "Jazazaz"), switch language (Japanese, French) or loop
empty `<think></think>`; twice `graph_compute ... failed with error 1` / `decode failed`. Sometimes a
turn is fine (478 / 156 tokens). Perf logs 2026-11-21 04:20, 04:26, 10:58, 11:10.

## Ruled out (tested on the device)
- NPU prefill on/off, NGRAM on/off, Drop cold experts 0, droppable cache off.
- Context checkpoint restore: makes it more frequent, but a fresh full prefill (reuse 0) breaks too.
- Thinking off: the empty `<think>` spans are a symptom (v7.15/7.16 retries removed in 7.17).

## Checked in the code, nothing found
- Per-expert `*.scale` tensors: llama.cpp indexes them by the real expert id; streaming keeps ids.
- `per_layer_token_embd` (27 GB n-gram table): rowStream off, so it is read through the mapping.
- dense-weights anon: only clean file pages are dropped after the copy.

## Next steps when it is picked up
- First after moving to upstream's newer llama.cpp (upstream 29b52d3, 386 commits past ours);
  upstream runs Flash-Next Q2_K fine (community benchmarks #460/#462).
- Suspect left: the expert cache under extreme pressure (4 GB cache for ~40 GB of experts; LFU,
  cold admission, cache brake). A diagnosis mode would compare first-token top-k with a freshly
  read expert set / checksums of loaded experts.
