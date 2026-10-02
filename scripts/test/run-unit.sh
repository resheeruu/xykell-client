#!/usr/bin/env bash
# Host unit tests: pure-C++ core compiled with Termux clang++ (host target).
# No Android toolchain, no device, no downloads.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK=/data/data/com.termux/files/usr/tmp/opencode/xykell-unit
rm -rf "$WORK" && mkdir -p "$WORK"
pass=0
run_case() { # name, sources...
    local name="$1"; shift
    clang++ -std=c++20 -Wall -Wextra -Werror -I "$ROOT/native/include" "$@" \
        -o "$WORK/$name"
    "$WORK/$name"
    pass=$((pass + 1))
}
run_case test_core "$ROOT/tests/unit/test_core.cpp" "$ROOT/native/src/xykell_core.cpp"
run_case test_adapter "$ROOT/tests/unit/test_adapter.cpp" "$ROOT/native/src/xykell_version_adapter.cpp"
run_case test_bus "$ROOT/tests/unit/test_bus.cpp" "$ROOT/native/src/xykell_event_bus.cpp"
run_case test_manager "$ROOT/tests/unit/test_manager.cpp" "$ROOT/native/src/xykell_module_manager.cpp"
echo "UNIT: $pass/4 suites PASS"
rm -rf "$WORK"
