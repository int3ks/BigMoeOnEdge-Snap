// How far Session::open() has got, for a UI to draw a real progress bar while a model loads.
//
// A load is a few steps of very different length; each reports its own fraction where the work is
// measurable (llama.cpp's tensor loading, the bytes of the dense weights read into RAM) and 0 or 1
// where it is a single call. The UI shows the step and its fraction instead of inventing weights for
// steps whose length depends on the model, the storage and the device.
//
// Process-wide and lock-free: the loading thread writes, any thread reads. One load at a time is
// what the app does; a second concurrent load would only mix the two readings, never break anything.
#pragma once

namespace bmoe {

enum class LoadStep : int {
    None = 0,
    Model = 1,   // llama.cpp reads the GGUF's tensor table and maps the weights (fraction from llama.cpp)
    Draft = 2,   // a separate MTP draft GGUF (fraction from llama.cpp)
    Experts = 3, // one warm-up decode that finds the expert tensors and their routing
    Weights = 4, // the dense (non-expert) weights read into RAM and the expert cache set up (fraction by bytes)
    Npu = 5,     // the NPU (Hexagon) prefill device takes its weights
    Finish = 6,  // last checks; the session is about to be ready
};

struct LoadProgress {
    LoadStep step = LoadStep::None;
    float frac = 0.0f; // 0..1 within the step
};

// Engine side. frac is clamped to [0, 1].
void load_progress_set(LoadStep step, float frac);
void load_progress_clear();
// Reader side, any thread.
LoadProgress load_progress_get();

} // namespace bmoe
