# A tiny random Jamba (attention + Mamba + MoE hybrid) for host tests of the engine:
# its memory is hybrid (recurrent layers refuse a partial cut), its MoE layers are an
# architecture the registry does not name (the expert layout is read from the file).
import sys, gguf, numpy as np
out = sys.argv[1]
rng = np.random.default_rng(1234)
n_embd, d_inner, d_conv, d_state, dt_rank = 64, 128, 4, 16, 4
n_layer, n_head, n_ff, n_expert = 4, 4, 128, 4
head_kv = [0, 4, 0, 4]
moe_layer = [False, True, False, True]
w = gguf.GGUFWriter(out, "jamba")
w.add_context_length(1024); w.add_embedding_length(n_embd); w.add_block_count(n_layer)
w.add_feed_forward_length(n_ff); w.add_head_count(n_head); w.add_head_count_kv(head_kv)
w.add_layer_norm_rms_eps(1e-5)
w.add_ssm_conv_kernel(d_conv); w.add_ssm_inner_size(d_inner); w.add_ssm_state_size(d_state); w.add_ssm_time_step_rank(dt_rank)
w.add_expert_count(n_expert); w.add_expert_used_count(2)
toks = ["<unk>", "<s>", "</s>", "<|im_start|>", "<|im_end|>"]
types = [2, 3, 3, 3, 3]
for c in range(32, 127):
    toks.append("▁" if c == 32 else chr(c)); types.append(1)
toks.append("<0x0A>"); types.append(6)
for word in ["user", "assistant", "system", "Hello", "▁there", "▁world", "▁the"]:
    toks.append(word); types.append(1)
n_vocab = len(toks)
w.add_tokenizer_model("llama")
w.add_token_list(toks); w.add_token_scores([0.0] * n_vocab); w.add_token_types(types)
w.add_bos_token_id(1); w.add_eos_token_id(4); w.add_unk_token_id(0); w.add_add_bos_token(False)
w.add_chat_template("{% for m in messages %}<|im_start|>{{ m['role'] }}\n{{ m['content'] }}<|im_end|>\n{% endfor %}{% if add_generation_prompt %}<|im_start|>assistant\n{% endif %}")
def t(name, ne, scale=0.08):
    w.add_tensor(name, (rng.standard_normal(tuple(reversed(ne))) * scale).astype(np.float32))
def ones(name, ne):
    w.add_tensor(name, np.ones(tuple(reversed(ne)), dtype=np.float32))
t("token_embd.weight", [n_embd, n_vocab], 0.5)
ones("output_norm.weight", [n_embd])
t("output.weight", [n_embd, n_vocab], 0.3)
for i in range(n_layer):
    b = f"blk.{i}."
    ones(b + "attn_norm.weight", [n_embd])
    if head_kv[i] == 0:
        t(b + "ssm_in.weight", [n_embd, 2 * d_inner])
        t(b + "ssm_conv1d.weight", [d_conv, d_inner], 0.3)
        t(b + "ssm_conv1d.bias", [d_inner], 0.1)
        t(b + "ssm_x.weight", [d_inner, dt_rank + 2 * d_state])
        ones(b + "ssm_dt_norm.weight", [dt_rank])
        t(b + "ssm_dt.weight", [dt_rank, d_inner], 0.3)
        t(b + "ssm_dt.bias", [d_inner], 0.1)
        ones(b + "ssm_b_norm.weight", [d_state]); ones(b + "ssm_c_norm.weight", [d_state])
        w.add_tensor(b + "ssm_a", (-np.exp(rng.standard_normal((d_inner, d_state)) * 0.5)).astype(np.float32))
        ones(b + "ssm_d", [d_inner])
        t(b + "ssm_out.weight", [d_inner, n_embd])
    else:
        t(b + "attn_q.weight", [n_embd, n_embd]); t(b + "attn_k.weight", [n_embd, n_embd])
        t(b + "attn_v.weight", [n_embd, n_embd]); t(b + "attn_output.weight", [n_embd, n_embd])
    ones(b + "ffn_norm.weight", [n_embd])
    if moe_layer[i]:
        t(b + "ffn_gate_inp.weight", [n_embd, n_expert], 0.5)
        t(b + "ffn_gate_exps.weight", [n_embd, n_ff, n_expert])
        t(b + "ffn_down_exps.weight", [n_ff, n_embd, n_expert])
        t(b + "ffn_up_exps.weight", [n_embd, n_ff, n_expert])
    else:
        t(b + "ffn_gate.weight", [n_embd, n_ff]); t(b + "ffn_down.weight", [n_ff, n_embd]); t(b + "ffn_up.weight", [n_embd, n_ff])
w.write_header_to_file(); w.write_kv_data_to_file(); w.write_tensors_to_file(); w.close()
print("vocab", n_vocab)
