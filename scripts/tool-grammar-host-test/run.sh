#!/usr/bin/env bash
# Host test for core/src/engine/tool_grammar.cpp against the vendored llama.cpp (no model, no device).
# Builds llama.cpp for the host CPU once (a few minutes), makes a tiny vocab-only GGUF and runs the cases.
# Needs: cmake, g++, python3 with `pip install gguf numpy`.
# sample.gbnf is what parser/ToolGrammar.kt builds for run_shell, read_file and create_file; regenerate it if the
# Kotlin builder changes.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
cpp="$here/../../app/src/main/cpp"
out="${1:-$here/build}"
mkdir -p "$out/llama"
if [ ! -f "$out/llama/src/libllama.a" ]; then
  cmake -S "$cpp/third_party/llama.cpp" -B "$out/llama" -DCMAKE_BUILD_TYPE=Release -DGGML_NATIVE=OFF \
    -DBUILD_SHARED_LIBS=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_TOOLS=OFF \
    -DLLAMA_BUILD_SERVER=OFF -DLLAMA_CURL=OFF -DLLAMA_OPENSSL=OFF -DGGML_OPENMP=OFF
  cmake --build "$out/llama" --target llama -j"$(nproc)"
fi
python3 "$here/make_vocab.py" "$out/vocab.gguf"
g++ -std=c++17 -O2 -I"$cpp/third_party/llama.cpp/include" -I"$cpp/third_party/llama.cpp/ggml/include" \
  -I"$cpp/core/src/engine" "$here/test_tool_grammar.cpp" "$cpp/core/src/engine/tool_grammar.cpp" \
  "$out/llama/src/libllama.a" "$out/llama/ggml/src/libggml.a" "$out/llama/ggml/src/libggml-cpu.a" \
  "$out/llama/ggml/src/libggml-base.a" -lpthread -ldl -o "$out/test_tool_grammar"
"$out/test_tool_grammar" "$out/vocab.gguf" "$here/sample.gbnf" 2>&1 | grep -E "FAIL|PASSED|long prose"
