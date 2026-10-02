#!/usr/bin/env bash
# M1 native build: CMake (Unix Makefiles) + Termux clang, ARM64. No downloads.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BUILD="$ROOT/build/native"
export CXX="${CXX:-clang++}"
cmake -S "$ROOT/native" -B "$BUILD" -G "Unix Makefiles" -DCMAKE_BUILD_TYPE=MinSizeRel
cmake --build "$BUILD" -- -j"$(nproc)"
ls -la "$BUILD/libxykell.so"
file "$BUILD/libxykell.so"
nm -D "$BUILD/libxykell.so" | grep -E "PLGetModRegistration" && echo "ENTRY-OK"
