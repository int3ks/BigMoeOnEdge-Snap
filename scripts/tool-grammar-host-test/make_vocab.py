import sys, gguf, numpy as np
out = sys.argv[1]
w = gguf.GGUFWriter(out, "llama")
w.add_context_length(256); w.add_embedding_length(64); w.add_block_count(1)
w.add_feed_forward_length(128); w.add_head_count(4); w.add_head_count_kv(4)
w.add_layer_norm_rms_eps(1e-5)
toks = ["<unk>", "<s>", "</s>"]
types = [2, 3, 3]
for c in range(32, 127):
    t = "▁" if c == 32 else chr(c)
    toks.append(t); types.append(1)
extra = ["<tool_call>", "</tool_call>", "{\"name\":▁\"", "run_shell", "read_file", "Hello", "\n", "\"arguments\":▁{", "bash", "\"command\":▁\"", "ls▁-la"]
for t in extra:
    toks.append(t); types.append(1)
w.add_tokenizer_model("llama")
w.add_token_list(toks); w.add_token_scores([0.0] * len(toks)); w.add_token_types(types)
w.add_bos_token_id(1); w.add_eos_token_id(2); w.add_unk_token_id(0)
w.write_header_to_file(); w.write_kv_data_to_file(); w.write_tensors_to_file(); w.close()
print(len(toks))
