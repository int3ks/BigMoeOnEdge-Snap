#include "bmoe/load_progress.h"

#include <atomic>

namespace bmoe {

namespace {
std::atomic<int> g_step{0};
std::atomic<float> g_frac{0.0f};
} // namespace

void load_progress_set(LoadStep step, float frac) {
    if (!(frac >= 0.0f)) frac = 0.0f; // NaN too
    if (frac > 1.0f) frac = 1.0f;
    g_frac.store(frac, std::memory_order_relaxed);
    g_step.store((int) step, std::memory_order_release);
}

void load_progress_clear() {
    load_progress_set(LoadStep::None, 0.0f);
}

LoadProgress load_progress_get() {
    LoadProgress p;
    p.step = (LoadStep) g_step.load(std::memory_order_acquire);
    p.frac = g_frac.load(std::memory_order_relaxed);
    return p;
}

} // namespace bmoe
