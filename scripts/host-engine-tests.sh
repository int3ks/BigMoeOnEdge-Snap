#!/usr/bin/env bash
# Build and run the engine core's host tests (app/src/main/cpp/core/tests) on Linux or macOS.
# Needs cmake, ninja, a C++17 compiler and python3 with the `gguf` and `numpy` packages
# (pip install gguf numpy). The session test runs on tiny random Jamba and llama models this script makes.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TESTS="$ROOT/app/src/main/cpp/core/tests"
BUILD="${BUILD_DIR:-$ROOT/build/host-engine-tests}"
mkdir -p "$BUILD"
cmake -S "$TESTS" -B "$BUILD" -G Ninja -DCMAKE_BUILD_TYPE=Release > "$BUILD/cmake.log"
ninja -C "$BUILD" file_reader_test expert_layout_test cache_brake_test session_ckpt_test
python3 -I "$TESTS/make_jamba.py" "$BUILD/tiny-jamba.gguf" > /dev/null
python3 -I "$TESTS/make_jamba.py" "$BUILD/tiny-llama.gguf" --llama > /dev/null
cd "$BUILD"
fail=0
for t in file_reader_test expert_layout_test cache_brake_test; do
    if ./$t > $t.log 2>&1; then echo "ok   $t"; else echo "FAIL $t (see $BUILD/$t.log)"; fail=1; fi
done
if ./session_ckpt_test tiny-jamba.gguf tiny-llama.gguf > session_ckpt_test.log 2>&1; then echo "ok   session_ckpt_test"; else
    echo "FAIL session_ckpt_test (see $BUILD/session_ckpt_test.log)"; fail=1; fi
exit $fail
