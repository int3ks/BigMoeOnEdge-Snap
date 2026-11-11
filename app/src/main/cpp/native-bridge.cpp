// JNI bridge between the Kotlin app and BigMoeOnEdge's bmoe::Session.
//
// API facts this file relies on (verified against core/include/bmoe/session.h and
// core/src/engine/session.cpp in this repo, see docs/bmoe_real_api_notes.md and patches/):
//   * Session::open(cfg, error) is the only way to get a session; everything in SessionConfig
//     (sampling, cache, dense weights, n_ctx, threads) is fixed for the session's lifetime.
//   * Session::generate() BLOCKS and invokes on_token synchronously on the calling thread, so the
//     JNIEnv of the calling (Kotlin IO) thread is valid inside the callback.
//   * Session::cancel() is a thread-safe atomic flag; a cancelled generate() rolls the whole turn back.
//   * TokenMetrics::text / ::reasoning are CUMULATIVE strings and may end in a partial UTF-8 sequence.
//
// Strings cross JNI as raw UTF-8 byte arrays, never as jstring: NewStringUTF/GetStringUTFChars use
// *modified* UTF-8, which mangles or aborts (CheckJNI) on 4-byte sequences such as emoji and on any
// multi-byte character split across tokens.

#include <jni.h>

#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <exception>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include <sched.h>
#include <unistd.h>

#include "bmoe/session.h"
#include "bmoe/load_progress.h"
#include "ggml-backend.h"
#include "llama.h" // llama_set_graph_compute_hook (local patch, see patches/0007)
#include "ggml-cpu.h" // ggml_cpu_has_* : one-time CPU feature line in the log (diagnostic only)
#ifdef BMOE_PIN_FAST_CORES
#include "perf_tuning.h"
#endif

#define LOG_TAG "OnEdge-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ---- CPU cores chosen in Settings ---------------------------------------------------------------------------
// Bit i = cpu i, one mask for decode (single-token graphs) and one for prefill (batched graphs); 0 = let Android
// place the threads. ggml creates each graph's compute threads from the calling thread (a disposable pool, default
// empty cpumask), so they inherit its affinity: llama.cpp's graph-compute hook sets the calling thread to the
// decode or prefill cores right before every graph, and ScopedCpuMask restores the thread's own affinity afterwards.
static std::atomic<unsigned long long> g_decode_mask{0};
static std::atomic<unsigned long long> g_prefill_mask{0};

// The cores Android lets this process run on: the main thread's affinity (cpusets apply process-wide).
static unsigned long long allowed_cpu_mask() {
    cpu_set_t set;
    CPU_ZERO(&set);
    if (sched_getaffinity(getpid(), sizeof(set), &set) != 0) return 0;
    unsigned long long m = 0;
    for (int i = 0; i < 64 && i < CPU_SETSIZE; i++)
        if (CPU_ISSET(i, &set)) m |= 1ULL << i;
    return m;
}

// Puts the calling thread on `want` (limited to the allowed cores); 0 or nothing allowed = all allowed cores.
static void place_thread(unsigned long long want) {
    const unsigned long long allowed = allowed_cpu_mask();
    unsigned long long use = want & allowed;
    if (use == 0) use = allowed;
    if (use == 0) return;
    cpu_set_t set;
    CPU_ZERO(&set);
    for (int i = 0; i < 64 && i < CPU_SETSIZE; i++)
        if ((use >> i) & 1ULL) CPU_SET(i, &set);
    sched_setaffinity(0, sizeof(set), &set);
}

// Called by llama.cpp before every graph compute, on the computing thread.
static void on_graph_compute(bool batched) {
    const unsigned long long want = batched ? g_prefill_mask.load() : g_decode_mask.load();
    if (want != 0) place_thread(want);
}

// Restores the calling thread's affinity when a JNI call ends, so a pooled Java thread does not keep the engine's
// cores. While the call runs, threads created outside a graph (the streaming loaders at load) get both core sets.
class ScopedCpuMask {
public:
    ScopedCpuMask() {
        const unsigned long long d = g_decode_mask.load(), p = g_prefill_mask.load();
        if ((d | p) == 0) return;
        if (sched_getaffinity(0, sizeof(old_), &old_) != 0) return;
        saved_ = true;
        place_thread(d | p);
        static std::atomic<unsigned long long> last_logged{~0ULL};
        const unsigned long long key = d ^ (p << 32);
        if (last_logged.exchange(key) != key) {
            LOGI("CPU cores: decode 0x%llx, prefill 0x%llx, allowed 0x%llx", d, p, allowed_cpu_mask());
        }
    }
    ~ScopedCpuMask() {
        if (saved_) sched_setaffinity(0, sizeof(old_), &old_);
    }
    ScopedCpuMask(const ScopedCpuMask &) = delete;
    ScopedCpuMask & operator=(const ScopedCpuMask &) = delete;

private:
    cpu_set_t old_;
    bool saved_ = false;
};

#ifdef BMOE_PIN_FAST_CORES
// Compute thread count of the open session, so the fast-core set is never smaller than it.
static std::atomic<int> g_n_threads{4};
// Slightly favoured scheduling weight for the engine thread (nice 0 -> -4). The UI's own threads run at -10.
static constexpr int kEngineNice = -4;

// Applies the fast-core mask + priority boost to the calling (engine worker) thread for one generate call;
// ggml's compute threads are created from this thread and inherit both. Logs the outcome the first time and
// whenever it changes.
class EnginePerfScope {
public:
    EnginePerfScope()
        : plan_(bmoe_perf::plan_fast_cores("/sys/devices/system/cpu", static_cast<int>(sysconf(_SC_NPROCESSORS_CONF)),
                                           g_n_threads.load())),
          guard_(plan_, kEngineNice) {
        char buf[200];
        std::snprintf(buf, sizeof(buf), "%s | affinity=%d nice=%d", plan_.note.c_str(), guard_.affinity_applied() ? 1 : 0,
                      guard_.nice_applied() ? kEngineNice : 0);
        static std::mutex mu;
        static std::string last;
        std::lock_guard<std::mutex> lk(mu);
        if (last != buf) {
            last = buf;
            LOGI("Engine thread tuning: %s", buf);
        }
    }

private:
    bmoe_perf::CorePlan plan_;
    bmoe_perf::ScopedFastCores guard_;
};
#endif

// Diagnostic only (no behaviour change): one logcat line per generation with the engine's own per-token
// decomposition. cores_busy = cpu_s/token / wall_s/token: close to the thread count means compute-bound;
// much lower means cores were waiting (flash stall) or throttled/preempted. Read with:
//   adb logcat -s OnEdge-JNI
static void log_run_stats(const bmoe::RunSummary & s, bool npu_requested) {
    const double cores_busy = s.s_per_token > 0.0 ? s.cpu_s_per_token / s.s_per_token : 0.0;
    LOGI("Run stats: n_gen=%d tok/s=%.3f s/tok=%.3f compute_s/tok=%.3f io_s/tok=%.3f stall_s/tok=%.3f "
         "mgmt_s/tok=%.3f cpu_s/tok=%.3f cores_busy=%.2f majflt/tok=%.1f loop_overhead_s/tok=%.4f "
         "cache_hit=%.1f%% read_mib=%.0f cache_mib=%.0f kv_reuse=%d n_reused=%d",
         s.n_generated, s.tokens_per_second, s.s_per_token, s.moe_compute_s_per_token, s.moe_io_s_per_token,
         s.moe_stall_s_per_token, s.moe_mgmt_s_per_token, s.cpu_s_per_token, cores_busy, s.majflt_per_token,
         s.loop_overhead_s_per_token, s.cache_hit_pct, s.moe_read_mib, s.cache_budget_mib, s.kv_reuse, s.n_reused);
    if (npu_requested) {
        LOGI("NPU prefill evidence: device_tokens=%d device_nodes=%lld device_read_mib=%.2f device_stall_s=%.3f",
             s.prefill_device_tokens, s.prefill_device_nodes, s.prefill_device_read_mib, s.prefill_device_stall_seconds);
        if (s.prefill_device_nodes <= 0) {
            LOGW("NPU prefill was requested but this run reported no HTP graph nodes; this is NOT proof of NPU execution. "
                 "The prompt may be too short/reused, or no graph ops/shapes were eligible.");
        }
    }
}

// Layout of the int/float arrays passed to nativeLoadModel. MUST stay in sync with
// LoadConfig.toIntArray()/toFloatArray() in EngineTypes.kt (a unit test pins the Kotlin side).
enum IntIdx {
    I_N_CTX = 0, I_N_BATCH, I_N_UBATCH, I_N_THREADS, I_TOP_K,
    I_CACHE_MODE,      // 0 auto, 1 fixed, 2 off
    I_CACHE_MB, I_CACHE_FLOOR_MB, I_CACHE_CEIL_MB, I_IO_THREADS,
    I_DENSE_MODE,      // 0 mmap, 1 warm, 2 anon, 3 pinned
    I_N_EXPERT_USED, I_DROP_COLD_PCT, I_SUBSTITUTE_PCT, I_PREFETCH_LAYERS, I_PREDICT_PREFETCH,
    I_PREDICT_SPEC_MAX, I_ROUTE_AHEAD,
    I_SPEC_SOURCE,     // 0 off, 1 mtp, 2 ngram
    I_DRAFT_MAX, I_MTP_P_MIN_PCT, I_O_DIRECT, I_OVERLAP, I_ROW_STREAM, I_RELEASE_MMAP,
    I_MMAP_BASELINE,   // 1 = no streaming at all (llama.cpp's ordinary mmap load)
    I_NPU_PREFILL,      // 1 = optional Hexagon/NPU prefill
    I_NPU_LOADERS,      // loader threads for the prefill device
    I_N_THREADS_BATCH, I_BATCH_WIDTH, I_SEED,
    I_IO_TWO_WAVE, I_ROW_STREAM_MB,
    I_NPU_MIN_TOKENS, I_NPU_ROUTED, I_NPU_ROUTED_FULL_PCT,
    I_NGRAM_MIN_MATCH, I_NGRAM_MAX_MATCH,
    I_DROP_RENORM, I_DROP_IN_PREFILL, I_PREDICTION_PROBE, I_LOAD_ALL_EXPERTS,
    I_DECIDE_PREFIX_CACHE, I_FORCE_CACHE,
    I_KV_CACHE_TYPE,   // 0 f16, 1 q8_0, 2 q4_0 (per model)
    I_REPEAT_LAST_N,   // repetition-penalty window: 0 off, < 0 whole context
    I_COUNT
};
enum FloatIdx { F_TEMPERATURE = 0, F_TOP_P, F_MIN_P, F_REPEAT_PENALTY, F_FREQ_PENALTY, F_PRESENCE_PENALTY, F_COUNT };

namespace {

// Guards g_session (the pointer), never held while generating.
std::mutex g_session_mu;
std::shared_ptr<bmoe::Session> g_session;
// Held for the whole duration of a generate() call. unload/reload takes it (after cancel()) so the
// session is never destroyed underneath a running generation.
std::mutex g_generate_mu;
std::string g_model_info;
std::atomic<bool> g_npu_prefill_requested{false};
// Optional per-token CSV writer (Settings -> Metrics CSV). Owned here, lives as long as the session.
std::unique_ptr<bmoe::IMetricsSink> g_csv_sink;

struct NpuRuntimeStatus {
    bool available = false;
    std::string message;
};

// Cache one real backend initialization probe. A build flag alone must never enable the settings UI.
const NpuRuntimeStatus & npu_runtime_status() {
    static const NpuRuntimeStatus status = [] {
        NpuRuntimeStatus out;
#ifdef BMOE_HAVE_HEXAGON
        ggml_backend_dev_t dev = ggml_backend_dev_by_name("HTP0");
        if (!dev) {
            std::string devices;
            for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
                if (!devices.empty()) devices += ", ";
                devices += ggml_backend_dev_name(ggml_backend_dev_get(i));
            }
            out.message = "Unavailable: this engine was built with Hexagon, but HTP0 was not registered (devices: " +
                          (devices.empty() ? std::string("none") : devices) +
                          "). Check backend registration and the Qualcomm FastRPC runtime.";
            return out;
        }
        ggml_backend_t probe = nullptr;
        std::string reason = "ggml_backend_dev_init returned null; inspect FastRPC/DSP messages in logcat";
        try {
            probe = ggml_backend_dev_init(dev, nullptr);
        } catch (const std::exception & e) {
            reason = e.what();
        } catch (...) {
            reason = "unknown exception while opening HTP0";
        }
        if (!probe) {
            out.message = "Unavailable: HTP0 is registered but its FastRPC/DSP session could not open: " + reason +
                          ". Verify the matching DSP skeleton and ADSP_LIBRARY_PATH.";
            return out;
        }
        ggml_backend_free(probe);
        out.available = true;
        out.message = "Available: HTP0 opened successfully. This proves runtime availability, not that a model graph ran on HTP; check the NPU prefill evidence log after a prompt.";
#else
        out.message = "Unavailable: this APK's native engine was built without Hexagon support (BMOE_HAVE_HEXAGON is not defined).";
#endif
        return out;
    }();
    return status;
}

std::shared_ptr<bmoe::Session> current_session() {
    std::lock_guard<std::mutex> lk(g_session_mu);
    return g_session;
}

std::string from_bytes(JNIEnv * env, jbyteArray arr) {
    if (arr == nullptr) return std::string();
    const jsize n = env->GetArrayLength(arr);
    std::string out(static_cast<size_t>(n), '\0');
    if (n > 0) env->GetByteArrayRegion(arr, 0, n, reinterpret_cast<jbyte *>(&out[0]));
    return out;
}

jbyteArray to_bytes(JNIEnv * env, const std::string & s) {
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(s.size()));
    if (arr != nullptr && !s.empty()) {
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte *>(s.data()));
    }
    return arr;
}

// Length of the longest prefix of `s` that does not end inside a multi-byte UTF-8 sequence.
// Bytes after that prefix are held back until the following token completes the character.
size_t utf8_complete_len(const std::string & s) {
    const size_t n = s.size();
    size_t i = n;
    for (int back = 0; i > 0 && back < 4; ++back) {
        const unsigned char c = static_cast<unsigned char>(s[i - 1]);
        if ((c & 0xC0) == 0x80) { // continuation byte: keep looking for its lead byte
            --i;
            continue;
        }
        size_t need = 1;
        if ((c & 0xE0) == 0xC0) need = 2;
        else if ((c & 0xF0) == 0xE0) need = 3;
        else if ((c & 0xF8) == 0xF0) need = 4;
        const size_t have = n - (i - 1);
        return have < need ? i - 1 : n;
    }
    return n; // malformed (only continuation bytes): pass through, Kotlin's decoder substitutes U+FFFD
}

// If `cur` (complete-UTF-8 part) extends what was already sent, return the new suffix and advance `sent`.
// A re-parse can momentarily disagree with an earlier partial parse; text already sent cannot be
// retracted, so in that case wait until the cumulative text extends `sent` again. The authoritative
// final text is delivered in onFinished.
bool next_delta(std::string & sent, const std::string & cur, std::string & delta) {
    const size_t safe = utf8_complete_len(cur);
    if (safe <= sent.size()) return false;
    if (std::memcmp(cur.data(), sent.data(), sent.size()) != 0) return false;
    const size_t start = sent.size();
    const size_t added = safe - start;
    delta.assign(cur.data() + start, added);
    // `cur` has already been proven to extend the exact bytes sent to Kotlin. Append only
    // the new suffix; assigning the whole cumulative answer here copied O(answer length) bytes
    // for every token even though the old prefix can no longer change once this check passes.
    sent.append(cur.data() + start, added);
    return true;
}

void release_session_blocking() {
    std::shared_ptr<bmoe::Session> s = current_session();
    if (s) s->cancel();
    // Wait for an in-flight generate() to unwind before dropping our reference.
    std::lock_guard<std::mutex> gen_lk(g_generate_mu);
    std::lock_guard<std::mutex> lk(g_session_mu);
    g_session.reset();
    g_csv_sink.reset();
    g_model_info.clear();
    g_npu_prefill_requested.store(false, std::memory_order_release);
}

} // namespace

extern "C" {

// ---------------------------------------------------------------------------------------------
// Model lifecycle
// ---------------------------------------------------------------------------------------------

JNIEXPORT void JNICALL
Java_com_bigmoe_onedge_core_EngineNativeBridge_nativeFreeModel(JNIEnv *, jobject) {
    LOGI("Releasing Session");
    release_session_blocking();
}

// Returns an EMPTY byte array on success, otherwise the UTF-8 error message.
// Every option is applied exactly as the original BigMoeOnEdge Android app's argv builder does
// (AppSettings.sessionArgv): options that need the LRU cache are only set when the cache is on, etc.
JNIEXPORT jbyteArray JNICALL
Java_com_bigmoe_onedge_core_EngineNativeBridge_nativeLoadModel(
    JNIEnv * env, jobject /* thiz */,
    jbyteArray j_model_path, jintArray j_ints, jfloatArray j_floats, jbyteArray j_csv_path,
    jbyteArray j_mmproj_path, jbyteArray j_draft_path, jbyteArray j_lora_path, jfloat j_lora_scale)
{
    const ScopedCpuMask cpu_mask_scope; // the cores chosen in Settings, for this call's threads
    bmoe::load_progress_clear(); // a poll before Session::open reports must not show the last load's end
    const std::string model_path = from_bytes(env, j_model_path);
    if (model_path.empty()) return to_bytes(env, "model path is empty");
    if (j_ints == nullptr || env->GetArrayLength(j_ints) < I_COUNT ||
        j_floats == nullptr || env->GetArrayLength(j_floats) < F_COUNT) {
        return to_bytes(env, "internal error: bad config array size");
    }
    jint iv[I_COUNT];
    jfloat fv[F_COUNT];
    env->GetIntArrayRegion(j_ints, 0, I_COUNT, iv);
    env->GetFloatArrayRegion(j_floats, 0, F_COUNT, fv);
    const std::string csv_path = from_bytes(env, j_csv_path);
    const std::string mmproj_path = from_bytes(env, j_mmproj_path);
    const std::string draft_path = from_bytes(env, j_draft_path);
    const std::string lora_path = from_bytes(env, j_lora_path);

    release_session_blocking();

    const bool npu_requested = iv[I_NPU_PREFILL] != 0;
    // Guess ahead (MTP / n-gram) is compatible: the engine keeps the draft context on the CPU and only
    // requires the NPU threshold to clear the verify width (applied below, once draft_max is known).
    if (npu_requested && iv[I_ROW_STREAM] != 0) {
        return to_bytes(env, "NPU prefill was requested together with Row-stream tables. Disable one of these conflicting settings and reload.");
    }
#ifndef BMOE_HAVE_HEXAGON
    if (npu_requested) {
        return to_bytes(env, "NPU prefill was requested, but this APK was built without the Hexagon backend. Turn NPU prefill off or install an NPU-enabled build.");
    }
#else
    if (npu_requested && !npu_runtime_status().available) {
        return to_bytes(env, "NPU prefill was requested but HTP is unavailable. " + npu_runtime_status().message);
    }
#endif

    bmoe::SessionConfig cfg;
    cfg.model_path = model_path;
    cfg.n_ctx = std::max<int>(256, iv[I_N_CTX]);
    cfg.n_batch = std::clamp<int>(iv[I_N_BATCH], 1, cfg.n_ctx);
    cfg.n_ubatch = std::clamp<int>(iv[I_N_UBATCH], 0, cfg.n_batch);
    cfg.n_threads = std::max<int>(1, iv[I_N_THREADS]);
    cfg.n_threads_batch = std::clamp<int>(iv[I_N_THREADS_BATCH], 1, 32);
    cfg.n_ubatch = std::clamp<int>(iv[I_BATCH_WIDTH], 32, cfg.n_ctx);
    cfg.chatml = true; // Session renders the model's own chat template over the whole conversation
    cfg.n_expert_used = std::max<int>(0, iv[I_N_EXPERT_USED]); // 0 = the model's own top-k
    cfg.mmproj_path = mmproj_path; // patch 0003; empty = text only
    cfg.spec.draft_model_path = draft_path;
    cfg.lora_path = lora_path; // empty = no LoRA adapter
    cfg.lora_scale = std::clamp<float>((float) j_lora_scale, 0.0f, 4.0f);
    cfg.kv_cache_type = std::clamp<int>(iv[I_KV_CACHE_TYPE], 0, 2);
#ifdef BMOE_HAVE_HEXAGON
    if (npu_requested) {
        cfg.prefill.device = "HTP0";
        cfg.prefill.load_threads = std::clamp<int>(iv[I_NPU_LOADERS], 1, bmoe::PrefillDeviceConfig::load_threads_max);
        cfg.prefill.min_tokens = std::clamp<int>(iv[I_NPU_MIN_TOKENS], bmoe::PrefillDeviceConfig::min_tokens_floor,
                                                 std::max<int>(bmoe::PrefillDeviceConfig::min_tokens_floor, cfg.n_ubatch));
        cfg.prefill.routed = iv[I_NPU_ROUTED] != 0;
        cfg.prefill.routed_full_frac = std::clamp<float>(static_cast<float>(iv[I_NPU_ROUTED_FULL_PCT]) / 100.0f,
                                                         0.01f, 1.0f);
    }
#endif
    cfg.decide.enabled = true; // required by the app's existing scored-choice API
    switch (iv[I_DECIDE_PREFIX_CACHE]) {
        case 1: cfg.decide.prefix_cache = bmoe::PrefixCacheMode::On; break;
        case 2: cfg.decide.prefix_cache = bmoe::PrefixCacheMode::Off; break;
        default: cfg.decide.prefix_cache = bmoe::PrefixCacheMode::Auto; break;
    }

    cfg.sampling.temp = fv[F_TEMPERATURE];
    cfg.sampling.top_p = fv[F_TOP_P];
    cfg.sampling.top_k = std::max<int>(0, iv[I_TOP_K]);
    cfg.sampling.seed = static_cast<uint32_t>(iv[I_SEED]); // -1 == random per run (UINT32_MAX)
    cfg.sampling.min_p = fv[F_MIN_P];
    cfg.sampling.repeat_penalty = fv[F_REPEAT_PENALTY];
    cfg.sampling.repeat_last_n = iv[I_REPEAT_LAST_N];
    cfg.sampling.freq_penalty = fv[F_FREQ_PENALTY];
    cfg.sampling.presence_penalty = fv[F_PRESENCE_PENALTY];

    const bool streaming = iv[I_MMAP_BASELINE] == 0;
    if (streaming) {
        bmoe::MoeStreamConfig & m = cfg.moe;
        m.enabled = true;
        m.io_threads = std::clamp<int>(iv[I_IO_THREADS], 1, bmoe::MoeStreamConfig::io_threads_max);
        m.o_direct = iv[I_O_DIRECT] != 0;
        m.io_two_wave = iv[I_IO_TWO_WAVE] != 0;
        m.row_stream_mb = std::max<int>(0, iv[I_ROW_STREAM_MB]);
        m.drop_renorm = iv[I_DROP_RENORM] != 0;
        m.drop_prefill = iv[I_DROP_IN_PREFILL] != 0;
        m.predict_log = iv[I_PREDICTION_PROBE] != 0;
        m.load_all = iv[I_LOAD_ALL_EXPERTS] != 0;
        bool cache_on = true;
        switch (iv[I_CACHE_MODE]) {
            case 1: { // fixed budget
                const int mb = std::max<int>(0, iv[I_CACHE_MB]);
                m.cache_auto = false;
                m.cache_mb = mb;
                // Budgets in the pathological band are only accepted when the caller means it.
                m.force_cache = iv[I_FORCE_CACHE] != 0;
                cache_on = mb > 0;
                break;
            }
            case 2: // cache off: experts re-read from flash every token
                m.cache_auto = false;
                m.cache_mb = 0;
                cache_on = false;
                break;
            default: // auto: sized once from free RAM, mutually exclusive with cache_mb
                m.cache_auto = true;
                m.cache_mb = 0;
                m.cache_floor_mb = std::max<int>(0, iv[I_CACHE_FLOOR_MB]);
                m.cache_ceil_mb = std::max<int>(0, iv[I_CACHE_CEIL_MB]);
                break;
        }
        switch (iv[I_DENSE_MODE]) {
            case 0: m.dense_weights = bmoe::DenseWeightsMode::Mmap; break;
            case 1: m.dense_weights = bmoe::DenseWeightsMode::Warmed; break;
            case 3: m.dense_weights = bmoe::DenseWeightsMode::Pinned; break;
            default: m.dense_weights = bmoe::DenseWeightsMode::Anonymous; break;
        }
#ifdef BMOE_HAVE_EXPERT_READY_HOOK
        m.overlap = iv[I_OVERLAP] != 0;
#else
        m.overlap = false; // the ggml-cpu expert-ready hook is not compiled in (patch 0002 missing)
#endif
        if (!m.overlap || !cache_on) m.io_two_wave = false;
        const int prefetch = std::clamp<int>(iv[I_PREFETCH_LAYERS], 0, bmoe::MoeStreamConfig::prefetch_layers_max);
        if (prefetch > 0 && cache_on) m.prefetch_layers = prefetch;
        if (iv[I_PREDICT_PREFETCH] != 0 && cache_on && m.prefetch_layers == 0) {
            m.predict_prefetch = true;
            m.predict_spec_max = std::max<int>(0, iv[I_PREDICT_SPEC_MAX]);
        }
        const bool spec_on = iv[I_SPEC_SOURCE] != 0;
        const int route_ahead = std::clamp<int>(iv[I_ROUTE_AHEAD], 0, bmoe::MoeStreamConfig::route_ahead_max);
        if (route_ahead > 0 && m.prefetch_layers == 0 && !m.predict_prefetch && !spec_on) m.route_ahead = route_ahead;
        if (iv[I_DROP_COLD_PCT] > 0 && cache_on) {
            m.drop_cold_frac = std::clamp<float>(static_cast<float>(iv[I_DROP_COLD_PCT]) / 100.0f, 0.0f, 1.0f);
        }
        m.row_stream = iv[I_ROW_STREAM] != 0;
        if (iv[I_RELEASE_MMAP] != 0 && (m.dense_weights == bmoe::DenseWeightsMode::Anonymous ||
                                        m.dense_weights == bmoe::DenseWeightsMode::Pinned)) {
            m.release_mmap = true;
        }
        if (iv[I_SUBSTITUTE_PCT] > 0 && cache_on) {
            m.substitute_lambda = std::clamp<float>(static_cast<float>(iv[I_SUBSTITUTE_PCT]) / 100.0f, 0.0f, 1.0f);
        }
    }
    // Speculation is a decode-loop change, not a residency policy: applies to the mmap baseline too.
    if (iv[I_SPEC_SOURCE] == 1) {
        cfg.spec.source = bmoe::DraftSource::mtp;
        cfg.spec.draft_max = std::clamp<int>(iv[I_DRAFT_MAX], 1, bmoe::SpecConfig::draft_max_limit);
        if (iv[I_MTP_P_MIN_PCT] > 0) cfg.spec.draft_p_min = static_cast<float>(iv[I_MTP_P_MIN_PCT]) / 100.0f;
    } else if (iv[I_SPEC_SOURCE] == 2) {
        cfg.spec.source = bmoe::DraftSource::ngram;
        cfg.spec.draft_max = std::clamp<int>(iv[I_DRAFT_MAX], 1, bmoe::SpecConfig::draft_max_limit);
        const int ngram_max = std::clamp<int>(iv[I_NGRAM_MAX_MATCH], 1, bmoe::SpecConfig::ngram_match_limit);
        cfg.spec.ngram_max_match = ngram_max;
        cfg.spec.ngram_min_match = std::clamp<int>(iv[I_NGRAM_MIN_MATCH], 1, ngram_max);
    }
#ifdef BMOE_HAVE_HEXAGON
    // With speculation the CPU decode graph is 1 + draft_max positions wide, not one token. Every device
    // graph has to be wider than that or llama.cpp could reuse a CPU-scheduled graph for a device one
    // (see PrefillDeviceConfig). Raise the user's threshold rather than refuse the load over a tuning knob.
    if (npu_requested && cfg.spec.enabled() && cfg.prefill.min_tokens <= cfg.spec.draft_max + 1) {
        const int raised = cfg.spec.draft_max + 2;
        LOGW("NPU prefill min tokens %d is not above the speculative verify width %d; using %d",
             cfg.prefill.min_tokens, cfg.spec.draft_max + 1, raised);
        cfg.prefill.min_tokens = raised;
    }
#endif

#ifdef BMOE_PIN_FAST_CORES
    g_n_threads.store(cfg.n_threads > 0 ? cfg.n_threads : 4);
#endif
    LOGI("Opening Session: n_ctx=%d n_batch=%d ubatch=%d threads=%d streaming=%d cache_mode=%d cache_mb=%d "
         "dense=%d overlap=%d drop=%d topk_override=%d spec=%d",
         cfg.n_ctx, cfg.n_batch, cfg.n_ubatch, cfg.n_threads, streaming ? 1 : 0, static_cast<int>(iv[I_CACHE_MODE]),
         cfg.moe.cache_mb, static_cast<int>(iv[I_DENSE_MODE]), cfg.moe.overlap ? 1 : 0, static_cast<int>(iv[I_DROP_COLD_PCT]),
         cfg.n_expert_used, static_cast<int>(iv[I_SPEC_SOURCE]));

    std::string error;
    std::unique_ptr<bmoe::Session> opened;
    try {
        opened = bmoe::Session::open(cfg, error);
    } catch (const std::exception & e) {
        // Last line of defence: an exception must never cross the JNI boundary (it aborts the process).
        error = std::string("model load threw: ") + e.what();
    } catch (...) {
        error = "model load threw an unknown exception";
    }
    if (!opened) {
        LOGE("Session::open failed: %s", error.c_str());
        return to_bytes(env, error.empty() ? std::string("Session::open failed (no message)") : error);
    }

    std::string info;
    info += "arch=" + opened->arch() + "\n";
    info += "n_ctx=" + std::to_string(opened->n_ctx()) + "\n";
    info += "n_expert_used=" + std::to_string(opened->n_expert_used()) + "\n";
    info += "load_seconds=" + std::to_string(opened->load_seconds()) + "\n";
    info += std::string("think_control=") + bmoe::think_control_name(opened->think_control()) + "\n";
    info += std::string("overlap=") + (cfg.moe.overlap ? "1" : "0") + "\n";
    info += std::string("streaming=") + (streaming ? "1" : "0") + "\n";
    info += std::string("vision=") + (opened->supports_vision() ? "1" : "0") + "\n";
    // Session::open fails if the HTP path was requested but could not initialise. `ready` therefore
    // means the loaded model has an initialised prefill path, not that every prompt op will offload.
    info += std::string("npu_prefill=") + (cfg.prefill.enabled() ? "ready" : "off") + "\n";

    std::unique_ptr<bmoe::IMetricsSink> csv;
    if (!csv_path.empty()) {
        csv.reset(bmoe::make_csv_metrics_sink(csv_path));
        if (!csv) LOGW("could not open metrics CSV at %s", csv_path.c_str());
    }

    {
        std::lock_guard<std::mutex> lk(g_session_mu);
        g_session = std::shared_ptr<bmoe::Session>(std::move(opened));
        g_csv_sink = std::move(csv);
        g_model_info = info;
        g_npu_prefill_requested.store(cfg.prefill.enabled(), std::memory_order_release);
    }
    LOGI("Session opened");
    LOGI("CPU features compiled in: neon=%d fp16_va=%d dotprod=%d i8mm=%d", ggml_cpu_has_neon(), ggml_cpu_has_fp16_va(),
         ggml_cpu_has_dotprod(), ggml_cpu_has_matmul_int8());
    return to_bytes(env, std::string());
}

// How far the running nativeLoadModel has got: step * 1000 + permille within the step (bmoe/load_progress.h),
// 0 when no load has reported yet. One int, read lock-free from any thread while the load runs.
JNIEXPORT jint JNICALL
Java_com_bigmoe_onedge_core_EngineNativeBridge_nativeLoadProgress(JNIEnv *, jobject) {
    const bmoe::LoadProgress p = bmoe::load_progress_get();
    return (jint) p.step * 1000 + (jint) (p.frac * 999.0f + 0.5f);
}

// Bit 0: libonedge-engine was built with the expert-ready hook (I/O-compute overlap possible).
// Bit 1: built with multimodal support (libmtmd).
JNIEXPORT jint JNICALL
Java_com_bigmoe_onedge_core_EngineNativeBridge_nativeCapabilities(JNIEnv *, jobject) {
    jint caps = 0;
#ifdef BMOE_HAVE_EXPERT_READY_HOOK
    caps |= 1;
#endif
#ifdef BMOE_HAVE_MTMD
    caps |= 2;
#endif
#ifdef BMOE_HAVE_HEXAGON
    if (npu_runtime_status().available) caps |= 4;
#endif
    return caps;
}

JNIEXPORT jbyteArray JNICALL
Java_com_bigmoe_onedge_core_EngineNativeBridge_nativeNpuStatus(JNIEnv * env, jobject) {
    return to_bytes(env, npu_runtime_status().message);
}

JNIEXPORT jbyteArray JNICALL
Java_com_bigmoe_onedge_core_EngineNativeBridge_nativeModelInfo(JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> lk(g_session_mu);
    return to_bytes(env, g_model_info);
}

// ---------------------------------------------------------------------------------------------
// Generation
// ---------------------------------------------------------------------------------------------

// Raised by nativeStopGeneration, cleared when a generate call enters the bridge. The session's own cancel latch is
// reset when generate() starts, so a Stop that came between the call's entry and that reset (waiting for the
// generation lock, rendering the template, reading images) was lost and the turn ran to the end. The session reads
// this flag alongside its latch (GenerateRequest::stop_flag; upstream #495).
static std::atomic<bool> g_stop_requested{false};

JNIEXPORT void JNICALL
Java_com_bigmoe_onedge_core_EngineNativeBridge_nativeStopGeneration(JNIEnv *, jobject) {
    g_stop_requested.store(true, std::memory_order_relaxed);
    std::shared_ptr<bmoe::Session> s = current_session();
    if (s) s->cancel();
}

// system_prompt + history_flat ([role0, content0, role1, content1, ...]) only take effect when
// clear_kv is true (see patches/0001-session-chat-context.patch).
// Returns JNI_TRUE when the call completed (including a user cancel); the outcome, error text and
// telemetry are delivered through callback.onFinished().
JNIEXPORT jboolean JNICALL
Java_com_bigmoe_onedge_core_EngineNativeBridge_nativeGenerate(
    JNIEnv * env, jobject /* thiz */,
    jbyteArray j_system, jobjectArray j_history_flat, jbyteArray j_prompt,
    jint max_tokens, jboolean clear_kv, jboolean think,
    jintArray j_image_dims, jobjectArray j_image_data,
    jbyteArray j_tool_grammar, jbyteArray j_tool_grammar_trigger,
    jboolean override_sampling, jfloat temperature, jfloat top_p, jint top_k, jint seed,
    jfloat min_p, jfloat repeat_penalty, jint repeat_last_n, jfloat freq_penalty, jfloat presence_penalty,
    jobject callback)
{
    // A Stop from here on belongs to this call (see g_stop_requested).
    g_stop_requested.store(false, std::memory_order_relaxed);
    const ScopedCpuMask cpu_mask_scope; // the cores chosen in Settings, for this call's threads
    if (callback == nullptr) return JNI_FALSE;
#ifdef BMOE_PIN_FAST_CORES
    const EnginePerfScope perf_scope;
#endif

    jclass cb_cls = env->GetObjectClass(callback);
    jmethodID on_token_mid = env->GetMethodID(cb_cls, "onToken", "([B[BI)Z");
    jmethodID on_finished_mid = env->GetMethodID(cb_cls, "onFinished", "(ZZ[B[B[BIIIDDD)V");
    if (on_token_mid == nullptr || on_finished_mid == nullptr) {
        LOGE("callback is missing onToken/onFinished");
        return JNI_FALSE;
    }

    auto finish = [&](bool ok, bool cancelled, const std::string & error, const std::string & answer,
                      const std::string & reasoning, int n_gen, int n_prompt, int n_past, double tps,
                      double prefill_s, double cache_hit) {
        jbyteArray j_err = to_bytes(env, error);
        jbyteArray j_ans = to_bytes(env, answer);
        jbyteArray j_rea = to_bytes(env, reasoning);
        env->CallVoidMethod(callback, on_finished_mid, static_cast<jboolean>(ok), static_cast<jboolean>(cancelled),
                            j_err, j_ans, j_rea, static_cast<jint>(n_gen), static_cast<jint>(n_prompt),
                            static_cast<jint>(n_past), static_cast<jdouble>(tps), static_cast<jdouble>(prefill_s),
                            static_cast<jdouble>(cache_hit));
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(j_err);
        env->DeleteLocalRef(j_ans);
        env->DeleteLocalRef(j_rea);
    };

    std::shared_ptr<bmoe::Session> session = current_session();
    if (!session) {
        finish(false, false, "No model is loaded", "", "", 0, 0, 0, 0.0, 0.0, -1.0);
        return JNI_TRUE;
    }

    bmoe::GenerateRequest req;
    req.prompt = from_bytes(env, j_prompt);
    req.n_predict = max_tokens > 0 ? static_cast<int>(max_tokens) : 0; // 0 = fill the remaining context (patch)
    req.think = (think == JNI_TRUE);
    req.clear_kv = (clear_kv == JNI_TRUE);
    req.render_text = true; // engine-side, template-aware split of answer vs reasoning
    // Lazy tool-call grammar (parser/ToolGrammar.kt); empty = none. The engine decides whether it applies this turn.
    if (j_tool_grammar != nullptr) req.tool_grammar = from_bytes(env, j_tool_grammar);
    if (j_tool_grammar_trigger != nullptr) req.tool_grammar_trigger = from_bytes(env, j_tool_grammar_trigger);
    req.stop_flag = &g_stop_requested;
    // Per-request sampling: the app sends its current values with every chat turn, so changing them needs no reload.
    req.sampling_override = (override_sampling == JNI_TRUE);
    if (req.sampling_override) {
        req.sampling.temp = static_cast<float>(temperature);
        req.sampling.top_p = static_cast<float>(top_p);
        req.sampling.top_k = std::max<int>(0, static_cast<int>(top_k));
        req.sampling.seed = static_cast<uint32_t>(seed); // -1 = random per generation
        req.sampling.min_p = std::clamp<float>(static_cast<float>(min_p), 0.0f, 1.0f);
        req.sampling.repeat_penalty = repeat_penalty > 0.0f ? static_cast<float>(repeat_penalty) : 1.0f;
        req.sampling.repeat_last_n = static_cast<int>(repeat_last_n);
        req.sampling.freq_penalty = static_cast<float>(freq_penalty);
        req.sampling.presence_penalty = static_cast<float>(presence_penalty);
    }
    if (req.clear_kv) {
        req.system_prompt = from_bytes(env, j_system);
        if (j_history_flat != nullptr) {
            const jsize n = env->GetArrayLength(j_history_flat);
            for (jsize i = 0; i + 1 < n; i += 2) {
                auto role = static_cast<jbyteArray>(env->GetObjectArrayElement(j_history_flat, i));
                auto content = static_cast<jbyteArray>(env->GetObjectArrayElement(j_history_flat, i + 1));
                bmoe::ChatTurn turn;
                turn.role = from_bytes(env, role);
                turn.content = from_bytes(env, content);
                req.history.push_back(std::move(turn));
                env->DeleteLocalRef(role);
                env->DeleteLocalRef(content);
            }
        }
    }

    // Images for this turn: dims = [w0, h0, w1, h1, ...], data[i] = w*h*3 RGB bytes (patch 0003).
    if (j_image_dims != nullptr && j_image_data != nullptr) {
        const jsize n_img = env->GetArrayLength(j_image_data);
        std::vector<jint> dims(static_cast<size_t>(n_img) * 2u, 0);
        if (env->GetArrayLength(j_image_dims) >= n_img * 2) env->GetIntArrayRegion(j_image_dims, 0, n_img * 2, dims.data());
        for (jsize i = 0; i < n_img; ++i) {
            auto data = static_cast<jbyteArray>(env->GetObjectArrayElement(j_image_data, i));
            bmoe::ImageRGB img;
            img.width = dims[static_cast<size_t>(i) * 2u];
            img.height = dims[static_cast<size_t>(i) * 2u + 1u];
            const std::string raw = from_bytes(env, data);
            img.rgb.assign(raw.begin(), raw.end());
            req.images.push_back(std::move(img));
            env->DeleteLocalRef(data);
        }
    }

    std::string sent_answer;
    std::string sent_reasoning;
    bool callback_failed = false;

    // Runs on this thread, inside generate(): env is valid here (see file header).
    auto on_token = [&](const bmoe::TokenMetrics & m) {
        if (callback_failed) return;
        std::string answer_delta, reasoning_delta;
        const bool a = next_delta(sent_answer, m.text, answer_delta);
        const bool r = next_delta(sent_reasoning, m.reasoning, reasoning_delta);
        // Call Java for every confirmed token, even when template parsing produces no visible text.
        // This keeps progress accounting authoritative for hidden/special tokens too.

        jbyteArray j_a = to_bytes(env, answer_delta);
        jbyteArray j_r = to_bytes(env, reasoning_delta);
        const jboolean keep_going = env->CallBooleanMethod(callback, on_token_mid, j_a, j_r, (jint) m.n_ctx_used);
        env->DeleteLocalRef(j_a);
        env->DeleteLocalRef(j_r);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            callback_failed = true;
            session->cancel();
            return;
        }
        if (!keep_going) session->cancel();
    };

    bmoe::RunResult result;
    {
        std::lock_guard<std::mutex> gen_lk(g_generate_mu);
        result = session->generate(req, on_token, g_csv_sink.get());
    }

    if (!result.ok) {
        LOGE("Session::generate failed: %s", result.error.c_str());
        finish(false, false, result.error.empty() ? std::string("generate failed (no message)") : result.error, "",
               "", 0, 0, 0, 0.0, 0.0, -1.0);
        return JNI_TRUE;
    }

    const bmoe::RunSummary & s = result.summary;
    log_run_stats(s, g_npu_prefill_requested.load(std::memory_order_acquire));
    finish(true, result.cancelled, "", result.generated_text, result.reasoning_text, s.n_generated, s.n_prompt,
           s.n_past, s.tokens_per_second, s.prefill_seconds, s.cache_hit_pct);
    return JNI_TRUE;
}


// ---------------------------------------------------------------------------------------------
// Stateless chat generation (patch 0004; the local API server). Same blocking/callback contract as
// nativeGenerate, but the whole conversation arrives as OpenAI-format JSON, the KV prefix is reused
// from the previous request, and the result also carries parsed tool calls and a stop reason.
// nativeGenerate above is deliberately untouched.
// ---------------------------------------------------------------------------------------------
JNIEXPORT jboolean JNICALL
Java_com_bigmoe_onedge_core_EngineNativeBridge_nativeGenerateChat(
    JNIEnv * env, jobject /* thiz */,
    jbyteArray j_messages_json, jbyteArray j_tools_json, jbyteArray j_tool_choice,
    jobjectArray j_stop, jint max_tokens, jboolean think,
    jboolean override_sampling, jfloat temperature, jfloat top_p, jint top_k, jint seed,
    jfloat min_p, jfloat repeat_penalty, jint repeat_last_n, jfloat freq_penalty, jfloat presence_penalty,
    jobject callback)
{
    g_stop_requested.store(false, std::memory_order_relaxed);
    const ScopedCpuMask cpu_mask_scope; // the cores chosen in Settings, for this call's threads
    if (callback == nullptr) return JNI_FALSE;
#ifdef BMOE_PIN_FAST_CORES
    const EnginePerfScope perf_scope;
#endif

    jclass cb_cls = env->GetObjectClass(callback);
    jmethodID on_token_mid = env->GetMethodID(cb_cls, "onToken", "([B[B)Z");
    jmethodID on_finished_mid = env->GetMethodID(cb_cls, "onFinished", "(ZZ[B[B[B[[B[B[BIIIIDDD)V");
    if (on_token_mid == nullptr || on_finished_mid == nullptr) {
        LOGE("chat callback is missing onToken/onFinished");
        return JNI_FALSE;
    }
    jclass byte_arr_cls = env->FindClass("[B");

    auto finish = [&](bool ok, bool cancelled, const std::string & error, const std::string & answer,
                      const std::string & reasoning, const std::vector<bmoe::ChatToolCall> & calls,
                      const std::string & stop_reason, const std::string & stop_sequence, int n_gen,
                      int n_prompt_total, int n_reused, int n_past, double tps, double prefill_s,
                      double cache_hit) {
        jbyteArray j_err = to_bytes(env, error);
        jbyteArray j_ans = to_bytes(env, answer);
        jbyteArray j_rea = to_bytes(env, reasoning);
        jbyteArray j_sr = to_bytes(env, stop_reason);
        jbyteArray j_ss = to_bytes(env, stop_sequence);
        // Tool calls travel as a flat [id0, name0, arguments0, id1, ...] array of UTF-8 byte arrays.
        jobjectArray j_calls = env->NewObjectArray(static_cast<jsize>(calls.size() * 3u), byte_arr_cls, nullptr);
        for (size_t i = 0; i < calls.size(); ++i) {
            const std::string * f[3] = {&calls[i].id, &calls[i].name, &calls[i].arguments};
            for (size_t k = 0; k < 3; ++k) {
                jbyteArray b = to_bytes(env, *f[k]);
                env->SetObjectArrayElement(j_calls, static_cast<jsize>(i * 3u + k), b);
                env->DeleteLocalRef(b);
            }
        }
        env->CallVoidMethod(callback, on_finished_mid, static_cast<jboolean>(ok), static_cast<jboolean>(cancelled),
                            j_err, j_ans, j_rea, j_calls, j_sr, j_ss, static_cast<jint>(n_gen),
                            static_cast<jint>(n_prompt_total), static_cast<jint>(n_reused), static_cast<jint>(n_past),
                            static_cast<jdouble>(tps), static_cast<jdouble>(prefill_s), static_cast<jdouble>(cache_hit));
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(j_err);
        env->DeleteLocalRef(j_ans);
        env->DeleteLocalRef(j_rea);
        env->DeleteLocalRef(j_calls);
        env->DeleteLocalRef(j_sr);
        env->DeleteLocalRef(j_ss);
    };

    std::shared_ptr<bmoe::Session> session = current_session();
    if (!session) {
        finish(false, false, "No model is loaded", "", "", {}, "", "", 0, 0, 0, 0, 0.0, 0.0, -1.0);
        return JNI_TRUE;
    }

    bmoe::GenerateRequest req;
    req.chat_messages_json = from_bytes(env, j_messages_json);
    req.chat_tools_json = from_bytes(env, j_tools_json);
    req.chat_tool_choice = from_bytes(env, j_tool_choice);
    if (req.chat_messages_json.empty()) {
        finish(false, false, "empty messages", "", "", {}, "", "", 0, 0, 0, 0, 0.0, 0.0, -1.0);
        return JNI_TRUE;
    }
    req.n_predict = max_tokens > 0 ? static_cast<int>(max_tokens) : 0;
    req.think = (think == JNI_TRUE);
    req.clear_kv = false; // ignored in stateless mode; stated for clarity
    req.render_text = true;
    if (j_stop != nullptr) {
        const jsize n = env->GetArrayLength(j_stop);
        for (jsize i = 0; i < n; ++i) {
            auto b = static_cast<jbyteArray>(env->GetObjectArrayElement(j_stop, i));
            std::string st = from_bytes(env, b);
            env->DeleteLocalRef(b);
            if (!st.empty()) req.stop.push_back(std::move(st));
        }
    }
    req.stop_flag = &g_stop_requested;
    req.sampling_override = (override_sampling == JNI_TRUE);
    if (req.sampling_override) {
        req.sampling.temp = static_cast<float>(temperature);
        req.sampling.top_p = static_cast<float>(top_p);
        req.sampling.top_k = static_cast<int>(top_k);
        req.sampling.seed = static_cast<uint32_t>(seed); // -1 -> LLAMA_DEFAULT_SEED (random)
        req.sampling.min_p = std::clamp<float>(static_cast<float>(min_p), 0.0f, 1.0f);
        req.sampling.repeat_penalty = repeat_penalty > 0.0f ? static_cast<float>(repeat_penalty) : 1.0f;
        req.sampling.repeat_last_n = static_cast<int>(repeat_last_n);
        req.sampling.freq_penalty = static_cast<float>(freq_penalty);
        req.sampling.presence_penalty = static_cast<float>(presence_penalty);
    }

    std::string sent_answer;
    std::string sent_reasoning;
    bool callback_failed = false;

    auto on_token = [&](const bmoe::TokenMetrics & m) {
        if (callback_failed) return;
        std::string answer_delta, reasoning_delta;
        next_delta(sent_answer, m.text, answer_delta);
        next_delta(sent_reasoning, m.reasoning, reasoning_delta);
        jbyteArray j_a = to_bytes(env, answer_delta);
        jbyteArray j_r = to_bytes(env, reasoning_delta);
        const jboolean keep_going = env->CallBooleanMethod(callback, on_token_mid, j_a, j_r);
        env->DeleteLocalRef(j_a);
        env->DeleteLocalRef(j_r);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            callback_failed = true;
            session->cancel();
            return;
        }
        if (!keep_going) session->cancel();
    };

    bmoe::RunResult result;
    {
        std::lock_guard<std::mutex> gen_lk(g_generate_mu);
        result = session->generate(req, on_token, g_csv_sink.get());
    }

    if (!result.ok) {
        LOGE("Session::generate (chat) failed: %s", result.error.c_str());
        finish(false, false, result.error.empty() ? std::string("generate failed (no message)") : result.error, "", "",
               {}, "", "", 0, 0, 0, 0, 0.0, 0.0, -1.0);
        return JNI_TRUE;
    }
    const bmoe::RunSummary & sm = result.summary;
    log_run_stats(sm, g_npu_prefill_requested.load(std::memory_order_acquire));
    finish(true, result.cancelled, "", result.generated_text, result.reasoning_text, result.tool_calls,
           result.stop_reason, result.stop_sequence, sm.n_generated, result.n_prompt_total, result.n_reused, sm.n_past,
           sm.tokens_per_second, sm.prefill_seconds, sm.cache_hit_pct);
    return JNI_TRUE;
}

static std::string json_escape(const std::string & s) {
    std::string out;
    out.reserve(s.size() + 8);
    for (unsigned char c : s) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\b': out += "\\b"; break;
            case '\f': out += "\\f"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    char buf[7];
                    std::snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else out.push_back(static_cast<char>(c));
        }
    }
    return out;
}

JNIEXPORT jbyteArray JNICALL
Java_com_bigmoe_onedge_core_EngineNativeBridge_nativeDecide(
    JNIEnv * env, jobject /* thiz */,
    jbyteArray j_prefix, jbyteArray j_suffix, jobjectArray j_choices, jboolean reuse_prefix)
{
    const ScopedCpuMask cpu_mask_scope; // the cores chosen in Settings, for this call's threads
    std::shared_ptr<bmoe::Session> session = current_session();
    if (!session) return to_bytes(env, "{\"ok\":false,\"error\":\"No model is loaded\"}");

    bmoe::DecideRequest req;
    req.prefix = from_bytes(env, j_prefix);
    req.suffix = from_bytes(env, j_suffix);
    req.reuse_prefix = reuse_prefix == JNI_TRUE;
    if (j_choices != nullptr) {
        const jsize n = env->GetArrayLength(j_choices);
        req.choices.reserve(static_cast<size_t>(n));
        for (jsize i = 0; i < n; ++i) {
            auto item = static_cast<jbyteArray>(env->GetObjectArrayElement(j_choices, i));
            req.choices.push_back(from_bytes(env, item));
            env->DeleteLocalRef(item);
        }
    }

    bmoe::DecideResult result;
    {
        std::lock_guard<std::mutex> gen_lk(g_generate_mu);
        result = session->decide(req);
    }

    std::string out = "{\"ok\":";
    out += result.ok ? "true" : "false";
    out += ",\"cancelled\":";
    out += result.cancelled ? "true" : "false";
    out += ",\"fatal\":";
    out += result.fatal ? "true" : "false";
    out += ",\"error\":\"" + json_escape(result.error) + "\"";
    out += ",\"best\":" + std::to_string(result.best);
    out += ",\"choice_logp\":[";
    for (size_t i = 0; i < result.choice_logp.size(); ++i) {
        if (i) out += ",";
        char buf[64];
        std::snprintf(buf, sizeof(buf), "%.17g", result.choice_logp[i]);
        out += buf;
    }
    out += "]";
    char buf[128];
    std::snprintf(buf, sizeof(buf), ",\"prefill_s\":%.6f,\"n_prefilled\":%d,\"n_reused\":%d,\"prefix_state_mib\":%.3f",
                  result.prefill.seconds, result.n_prefilled, result.n_reused,
                  static_cast<double>(result.prefix_state_bytes) / (1024.0 * 1024.0));
    out += buf;
    out += "}";
    return to_bytes(env, out);
}

} // extern "C"

extern "C" JNIEXPORT void JNICALL
Java_com_bigmoe_onedge_core_EngineNativeBridge_nativeSetCpuMasks(JNIEnv *, jobject, jlong decode, jlong prefill) {
    g_decode_mask.store(static_cast<unsigned long long>(decode));
    g_prefill_mask.store(static_cast<unsigned long long>(prefill));
    // Installed once and left in place: with both masks 0 it does nothing.
    static std::once_flag hook_once;
    std::call_once(hook_once, [] { llama_set_graph_compute_hook(&on_graph_compute); });
}

// Live tuning between generations (the device tuner): compute threads and the expert-cache budget of the loaded
// session, without a reload. No-ops without a session. Must not be called while a generation runs.
extern "C" JNIEXPORT void JNICALL
Java_com_bigmoe_onedge_core_EngineNativeBridge_nativeSetThreads(JNIEnv *, jobject, jint decode, jint prefill) {
    std::shared_ptr<bmoe::Session> s = current_session();
    if (s) s->set_threads(decode, prefill);
}

extern "C" JNIEXPORT void JNICALL
Java_com_bigmoe_onedge_core_EngineNativeBridge_nativeSetCacheBudget(JNIEnv *, jobject, jint mib) {
    std::shared_ptr<bmoe::Session> s = current_session();
    if (s && mib > 0) s->set_cache_budget_mb(mib);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_bigmoe_onedge_core_EngineNativeBridge_nativeAllowedCpus(JNIEnv *, jobject) {
    return static_cast<jlong>(allowed_cpu_mask());
}

// ---- In-app log ------------------------------------------------------------------------------------------------
// llama.cpp's default logger and the engine's "bmoe: ..." lines write to stderr, which an Android app does not see
// anywhere. While recording is on (Log page switch, off by default), stderr points at a pipe; a reader thread cuts it
// into lines, stamps them with the time and keeps the newest kLogMaxLines in memory for the Log page.
// Lines are numbered; the page asks for everything after the number it already has.
//
// It must never slow the engine down or stop it: the pipe's write end is non-blocking (a full pipe drops text instead of
// making the writer wait), the pipe is large, the reader runs at a low priority, nothing is forwarded to logcat, and
// switching it off puts the original stderr back.
#include <cerrno>
#include <ctime>
#include <deque>
#include <thread>
#include <fcntl.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <sys/time.h>

namespace {
constexpr size_t kLogMaxLines = 6000;
constexpr size_t kLogMaxLineChars = 2000;
std::mutex g_log_mu;
std::deque<std::string> g_log_lines; // oldest first; the newest has sequence number g_log_next_seq - 1
uint64_t g_log_next_seq = 1;
std::mutex g_cap_mu;
int g_saved_stderr = -1;
bool g_capturing = false;

// Optional log file next to the in-memory ring: every line is written with write() (O_APPEND) the moment the reader
// has it, so the file survives a crash or a kill of the process (the data is in the kernel's page cache then).
constexpr uint64_t kLogFileMaxBytes = 64ull << 20; // a runaway log must not fill the tablet
std::mutex g_file_mu;
int g_log_file_fd = -1;
uint64_t g_log_file_bytes = 0;

void log_file_write(const std::string & stamped) {
    std::lock_guard<std::mutex> lock(g_file_mu);
    if (g_log_file_fd < 0) return;
    if (g_log_file_bytes + stamped.size() + 1 > kLogFileMaxBytes) {
        close(g_log_file_fd);
        g_log_file_fd = -1;
        return;
    }
    std::string out = stamped;
    out.push_back('\n');
    const char * p = out.data();
    size_t left = out.size();
    while (left > 0) {
        const ssize_t w = write(g_log_file_fd, p, left);
        if (w < 0 && errno == EINTR) continue;
        if (w <= 0) { // disk full or the file vanished: stop writing, keep the ring going
            close(g_log_file_fd);
            g_log_file_fd = -1;
            return;
        }
        p += w;
        left -= static_cast<size_t>(w);
        g_log_file_bytes += static_cast<uint64_t>(w);
    }
}

void log_push_line(const std::string & line) {
    if (line.empty()) return;
    timeval tv{};
    gettimeofday(&tv, nullptr);
    time_t sec = tv.tv_sec;
    tm local{};
    localtime_r(&sec, &local);
    char stamp[24];
    std::snprintf(stamp, sizeof(stamp), "%02d:%02d:%02d.%03d ", local.tm_hour, local.tm_min, local.tm_sec,
                  static_cast<int>(tv.tv_usec / 1000));
    std::string stamped = stamp;
    stamped += line;
    log_file_write(stamped);
    std::lock_guard<std::mutex> lock(g_log_mu);
    g_log_lines.push_back(std::move(stamped));
    g_log_next_seq++;
    while (g_log_lines.size() > kLogMaxLines) g_log_lines.pop_front();
}

// Ends (and closes the pipe) when the last write end is gone, i.e. when recording is switched off.
void log_reader(int fd) {
    setpriority(PRIO_PROCESS, static_cast<id_t>(syscall(SYS_gettid)), 10); // never compete with the compute threads
    std::string pending;
    char buf[8192];
    for (;;) {
        const ssize_t n = read(fd, buf, sizeof(buf));
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) break;
        for (ssize_t i = 0; i < n; i++) {
            const char c = buf[i];
            if (c == '\n') {
                log_push_line(pending);
                pending.clear();
            } else if (c != '\r') {
                pending.push_back(c);
                if (pending.size() >= kLogMaxLineChars) {
                    log_push_line(pending);
                    pending.clear();
                }
            }
        }
    }
    log_push_line(pending);
    close(fd);
}
} // namespace

// Idempotent. Nothing is captured while it is off. [path] (may be null) is a log file that every line is appended to.
extern "C" JNIEXPORT void JNICALL
Java_com_bigmoe_onedge_core_EngineNativeBridge_nativeLogStart(JNIEnv * env, jobject, jstring path) {
    std::lock_guard<std::mutex> cap(g_cap_mu);
    if (g_capturing) return;
    int fds[2];
    if (pipe2(fds, O_CLOEXEC) != 0) return;
    // The write end is shared by stderr (same open file description): a full pipe must drop, never block the engine.
    fcntl(fds[1], F_SETFL, fcntl(fds[1], F_GETFL) | O_NONBLOCK);
#ifdef F_SETPIPE_SZ
    fcntl(fds[1], F_SETPIPE_SZ, 1 << 20); // best effort; the default is 64 KiB
#endif
    std::fflush(stderr);
    const int saved = dup(STDERR_FILENO);
    if (saved < 0 || dup2(fds[1], STDERR_FILENO) < 0) {
        if (saved >= 0) close(saved);
        close(fds[0]);
        close(fds[1]);
        return;
    }
    close(fds[1]); // stderr keeps the write end open
    if (path != nullptr) {
        const char * chars = env->GetStringUTFChars(path, nullptr);
        if (chars != nullptr) {
            const int fd = open(chars, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0644);
            env->ReleaseStringUTFChars(path, chars);
            if (fd >= 0) {
                std::lock_guard<std::mutex> lock(g_file_mu);
                if (g_log_file_fd >= 0) close(g_log_file_fd);
                g_log_file_fd = fd;
                g_log_file_bytes = static_cast<uint64_t>(lseek(fd, 0, SEEK_END)); // Kotlin wrote a header already
            }
        }
    }
    try {
        std::thread(log_reader, fds[0]).detach();
    } catch (...) {
        {
            std::lock_guard<std::mutex> lock(g_file_mu);
            if (g_log_file_fd >= 0) { close(g_log_file_fd); g_log_file_fd = -1; }
        }
        // No reader: put stderr back at once, or the first full pipe would have nobody to empty it.
        dup2(saved, STDERR_FILENO);
        close(saved);
        close(fds[0]);
        return;
    }
    g_saved_stderr = saved;
    g_capturing = true;
}

// A line from the app (free-RAM samples): straight into the ring and the file, not through the stderr pipe.
extern "C" JNIEXPORT void JNICALL
Java_com_bigmoe_onedge_core_EngineNativeBridge_nativeLogNote(JNIEnv * env, jobject, jstring line) {
    if (line == nullptr) return;
    const char * chars = env->GetStringUTFChars(line, nullptr);
    if (chars == nullptr) return;
    log_push_line(std::string(chars));
    env->ReleaseStringUTFChars(line, chars);
}

// Puts the original stderr back; the reader finishes the rest of the pipe and ends. The kept lines stay.
extern "C" JNIEXPORT void JNICALL
Java_com_bigmoe_onedge_core_EngineNativeBridge_nativeLogStop(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> cap(g_cap_mu);
    if (!g_capturing) return;
    std::fflush(stderr);
    dup2(g_saved_stderr, STDERR_FILENO);
    close(g_saved_stderr);
    g_saved_stderr = -1;
    g_capturing = false;
    std::lock_guard<std::mutex> lock(g_file_mu);
    if (g_log_file_fd >= 0) {
        close(g_log_file_fd);
        g_log_file_fd = -1;
    }
}

// UTF-8 text: the first line is the sequence number of the newest line, then every line after `after`, one per line.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_bigmoe_onedge_core_EngineNativeBridge_nativeLogRead(JNIEnv * env, jobject, jlong after) {
    std::string out;
    {
        std::lock_guard<std::mutex> lock(g_log_mu);
        const uint64_t newest = g_log_next_seq - 1;
        out = std::to_string(newest);
        out.push_back('\n');
        const uint64_t first = g_log_next_seq - g_log_lines.size(); // sequence number of g_log_lines.front()
        const uint64_t want = after < 0 ? 1 : static_cast<uint64_t>(after) + 1;
        const uint64_t from = std::max(want, first);
        for (uint64_t seq = from; seq <= newest; seq++) {
            out += g_log_lines[static_cast<size_t>(seq - first)];
            out.push_back('\n');
        }
    }
    return to_bytes(env, out);
}

extern "C" JNIEXPORT void JNICALL
Java_com_bigmoe_onedge_core_EngineNativeBridge_nativeLogClear(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_log_mu);
    g_log_lines.clear(); // the sequence keeps counting, so a page that polls is not confused
}
