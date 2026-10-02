#!/usr/bin/env bash
# Host unit tests: pure-C++ core compiled with Termux clang++ (host target).
# No Android toolchain, no device, no downloads. CWD is forced to repo root
# because test_gui_model reads registry/features.json relatively.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
WORK=/data/data/com.termux/files/usr/tmp/opencode/xykell-unit
rm -rf "$WORK" && mkdir -p "$WORK"
# Test file-state dirs must start clean: earlier aborted runs may leave
# corrupt fixtures behind that would fail the "missing file" assertions.
rm -rf /data/data/com.termux/files/usr/tmp/opencode/xcfg-1 \
       /data/data/com.termux/files/usr/tmp/opencode/xprof-1 \
       /data/data/com.termux/files/usr/tmp/opencode/xcrash-1
pass=0
run_case() { # name, sources...
    local name="$1"; shift
    clang++ -std=c++20 -Wall -Wextra -Werror -I "$ROOT/native/include" "$@" \
        -o "$WORK/$name"
    "$WORK/$name"
    pass=$((pass + 1))
}
SRC="$ROOT/native/src"
run_case test_core "$ROOT/tests/unit/test_core.cpp" "$SRC/xykell_core.cpp"
run_case test_adapter "$ROOT/tests/unit/test_adapter.cpp" "$SRC/xykell_version_adapter.cpp"
run_case test_bus "$ROOT/tests/unit/test_bus.cpp" "$SRC/xykell_event_bus.cpp"
run_case test_manager "$ROOT/tests/unit/test_manager.cpp" "$SRC/xykell_module_manager.cpp"
run_case test_json "$ROOT/tests/unit/test_json.cpp" "$SRC/xykell_json_min.cpp"
run_case test_config "$ROOT/tests/unit/test_config.cpp" "$SRC/xykell_json_min.cpp" \
    "$SRC/xykell_file_util.cpp" "$SRC/xykell_config_store.cpp"
run_case test_profile "$ROOT/tests/unit/test_profile.cpp" "$SRC/xykell_json_min.cpp" \
    "$SRC/xykell_file_util.cpp" "$SRC/xykell_profile_manager.cpp"
run_case test_crash "$ROOT/tests/unit/test_crash.cpp" "$SRC/xykell_json_min.cpp" \
    "$SRC/xykell_file_util.cpp" "$SRC/xykell_crash_guard.cpp"
run_case test_gui_model "$ROOT/tests/unit/test_gui_model.cpp" "$SRC/xykell_json_min.cpp" \
    "$SRC/xykell_file_util.cpp" "$SRC/xykell_clickgui_model.cpp"
run_case test_hud_theme "$ROOT/tests/unit/test_hud_theme.cpp" "$SRC/xykell_json_min.cpp" \
    "$SRC/xykell_hud_model.cpp" "$SRC/xykell_theme.cpp"
echo "UNIT: $pass/10 suites PASS"
rm -rf "$WORK"
