// Which tensors of a model are its routed experts, read from the file's shapes
// (bmoe/expert_layout.h). Ported from upstream BigMoeOnEdge (#266, 0.34); the name parsing that
// upstream keeps in tensor_names.cpp is inlined here, since this engine has no such file.
#include "bmoe/expert_layout.h"

#include <cstring>
#include <map>
#include <set>
#include <string_view>

namespace bmoe {

namespace {

constexpr std::string_view k_block_prefix = "blk.";
constexpr std::string_view k_weight_tail = ".weight";
constexpr int k_layer_max = 1 << 20; // a layer index past this is a runaway digit run, not a layer

// For "blk.<il>.<suffix>.weight" the suffix, with the layer in `il_out`; an empty view for any other
// name (a block tensor with another tail such as ".scale" or ".bias", a tensor outside any block).
std::string_view weight_suffix(const char * name, int & il_out) {
    if (!name || std::strncmp(name, k_block_prefix.data(), k_block_prefix.size()) != 0) return {};
    const char * p = name + k_block_prefix.size();
    if (*p < '0' || *p > '9') return {};
    int il = 0;
    for (; *p >= '0' && *p <= '9'; ++p) {
        il = il * 10 + (*p - '0');
        if (il > k_layer_max) return {};
    }
    if (*p != '.') return {};
    const char * rest = p + 1;
    const size_t len = std::strlen(rest);
    if (len <= k_weight_tail.size() || k_weight_tail != rest + (len - k_weight_tail.size())) return {};
    il_out = il;
    return std::string_view(rest, len - k_weight_tail.size());
}

std::string braces(const std::set<std::string> & names) {
    std::string s = "{";
    for (const std::string & n : names)
        s += (s.size() > 1 ? ", " : "") + n;
    return s + "}";
}

} // namespace

int ExpertLayout::slot_of(const char * name, int & il_out) const {
    if (!ok) return -1;
    int il = -1;
    const std::string_view sfx = weight_suffix(name, il);
    if (sfx.empty()) return -1;
    for (int p = 0; p < n_slots; ++p) {
        if (sfx == suffix[p]) {
            il_out = il;
            return p;
        }
    }
    return -1;
}

ExpertLayout discover_expert_layout(const std::vector<TensorExtent> & tensors, int n_expert) {
    ExpertLayout out;
    out.n_expert = n_expert;
    if (n_expert <= 1) {
        out.error = "the file declares no routed experts (expert_count is " + std::to_string(n_expert) +
                    "): a dense model, which this engine runs as plain mmap and does not stream";
        return out;
    }

    // Per layer, the suffixes of the weights indexed by the expert count. Everything else a block
    // holds - a shared expert, the router and its bias, a per-expert scale - has another rank or
    // another tail and falls out here, by its shape and not by its name.
    std::map<int, std::set<std::string>> per_layer;
    for (const TensorExtent & t : tensors) {
        if (t.third != (uint64_t) n_expert) continue;
        int il = -1;
        const std::string_view sfx = weight_suffix(t.name.c_str(), il);
        if (sfx.empty()) continue;
        per_layer[il].insert(std::string(sfx));
    }
    if (per_layer.empty()) {
        out.error = "the file declares " + std::to_string(n_expert) +
                    " experts but no block holds a 3-D weight indexed by them: no routed expert tensor this "
                    "engine recognises";
        return out;
    }

    // One layout per file: a slot must mean the same tensor in every MoE layer, since the streamer's
    // buffers and the arena's regions are per slot. A file whose layers disagree is refused rather
    // than streamed by the first layer's rule.
    const auto first = per_layer.begin();
    for (const auto & kv : per_layer) {
        if (kv.second != first->second) {
            out.error = "layer " + std::to_string(kv.first) + " names expert tensors " + braces(kv.second) +
                        " where layer " + std::to_string(first->first) + " names " + braces(first->second) +
                        ": the layers disagree on what an expert is, and this engine streams one layout per file";
            return out;
        }
        out.layers.push_back(kv.first);
    }
    if ((int) first->second.size() > ExpertLayout::max_slots) {
        out.error = "a layer names " + std::to_string(first->second.size()) + " expert tensors " +
                    braces(first->second) + ", more than the " + std::to_string(ExpertLayout::max_slots) +
                    " this engine streams";
        return out;
    }

    out.n_slots = (int) first->second.size();
    int p = 0;
    for (const std::string & s : first->second)
        out.suffix[p++] = s; // a set: sorted, so the order is the same in every layer
    out.ok = true;
    return out;
}

} // namespace bmoe
