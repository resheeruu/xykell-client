#!/usr/bin/env bash
# Host unit tests: pure-C++ core. Compiler override for CI (g++), temp dir
# override so the same suites run on any host. CWD forced to repo root.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
CXX_BIN="${CXX:-clang++}"
WORK="${XYKELL_WORK:-/data/data/com.termux/files/usr/tmp/opencode/xykell-unit}"
rm -rf "$WORK" && mkdir -p "$WORK"
export XYKELL_TEST_TMP="$WORK/files"
mkdir -p "$XYKELL_TEST_TMP"
# File-state fixtures start clean (aborted runs must not pollute assertions).
rm -rf "$XYKELL_TEST_TMP/xcfg-1" "$XYKELL_TEST_TMP/xprof-1" \
       "$XYKELL_TEST_TMP/xcrash-1" "$XYKELL_TEST_TMP/xactive-1"
pass=0
run_case() { # name, sources...
    local name="$1"; shift
    "$CXX_BIN" -std=c++20 -Wall -Wextra -Werror -I "$ROOT/native/include" "$@" \
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
run_case test_gui_controller "$ROOT/tests/unit/test_gui_controller.cpp" "$SRC/xykell_json_min.cpp" \
    "$SRC/xykell_file_util.cpp" "$SRC/xykell_clickgui_model.cpp" \
    "$SRC/xykell_module_manager.cpp" "$SRC/xykell_gui_controller.cpp" \
    "$SRC/xykell_runtime_probe.cpp"
run_case test_input_router "$ROOT/tests/unit/test_input_router.cpp" "$SRC/xykell_json_min.cpp" \
    "$SRC/xykell_file_util.cpp" "$SRC/xykell_clickgui_model.cpp" \
    "$SRC/xykell_module_manager.cpp" "$SRC/xykell_gui_controller.cpp" \
    "$SRC/xykell_input_router.cpp" "$SRC/xykell_runtime_probe.cpp"
run_case test_hud_render "$ROOT/tests/unit/test_hud_render.cpp" "$SRC/xykell_json_min.cpp" \
    "$SRC/xykell_file_util.cpp" "$SRC/xykell_profile_manager.cpp" \
    "$SRC/xykell_hud_model.cpp" "$SRC/xykell_theme.cpp" "$SRC/xykell_module_manager.cpp" \
    "$SRC/xykell_hud_renderer.cpp"
run_case test_probe "$ROOT/tests/unit/test_probe.cpp" "$SRC/xykell_json_min.cpp" \
    "$SRC/xykell_file_util.cpp" "$SRC/xykell_clickgui_model.cpp" \
    "$SRC/xykell_module_manager.cpp" "$SRC/xykell_gui_controller.cpp" \
    "$SRC/xykell_runtime_probe.cpp"
run_case test_engines "$ROOT/tests/unit/test_engines.cpp" \
    "$SRC/xykell_runtime_probe.cpp" "$SRC/xykell_engines.cpp"
run_case test_stages_sigscan "$ROOT/tests/unit/test_stages_sigscan.cpp" \
    "$SRC/xykell_load_stages.cpp" "$SRC/xykell_sigscan.cpp"
run_case test_runtime_proof "$ROOT/tests/unit/test_runtime_proof.cpp" \
    "$SRC/xykell_json_min.cpp" "$SRC/xykell_file_util.cpp" \
    "$SRC/xykell_runtime_active.cpp" "$SRC/xykell_hud_renderer.cpp" \
    "$SRC/xykell_hud_model.cpp" "$SRC/xykell_theme.cpp" \
    "$SRC/xykell_profile_manager.cpp" "$SRC/xykell_module_manager.cpp"
run_case test_local_systems "$ROOT/tests/unit/test_local_systems.cpp" \
    "$SRC/xykell_json_min.cpp" "$SRC/xykell_friends.cpp" \
    "$SRC/xykell_notifications.cpp" "$SRC/xykell_server_profiles.cpp" \
    "$SRC/xykell_waypoints.cpp" "$SRC/xykell_theme.cpp"
run_case test_portal "$ROOT/tests/unit/test_portal.cpp" \
    "$SRC/xykell_core.cpp" "$SRC/xykell_menu.cpp" "$SRC/xykell_hud.cpp" \
    "$SRC/xykell_json_min.cpp" "$SRC/xykell_hud_model.cpp" \
    "$SRC/xykell_hud_renderer.cpp" "$SRC/xykell_theme.cpp" \
    "$SRC/xykell_profile_manager.cpp" "$SRC/xykell_module_manager.cpp" \
    "$SRC/xykell_gui_controller.cpp" "$SRC/xykell_input_router.cpp" \
    "$SRC/xykell_clickgui_model.cpp" "$SRC/xykell_file_util.cpp" \
    "$SRC/xykell_runtime_active.cpp" "$SRC/xykell_portal.cpp" "$SRC/xykell_runtime_probe.cpp"
run_case test_detection "$ROOT/tests/unit/test_detection.cpp" \
    "$SRC/xykell_detection.cpp"
run_case test_planner "$ROOT/tests/unit/test_planner.cpp" \
    "$SRC/xykell_json_min.cpp" "$SRC/xykell_file_util.cpp" \
    "$SRC/xykell_clickgui_model.cpp" "$SRC/xykell_module_manager.cpp" \
    "$SRC/xykell_gui_controller.cpp" "$SRC/xykell_runtime_probe.cpp" \
    "$SRC/xykell_planner.cpp"
run_case test_runtime_provider "$ROOT/tests/unit/test_runtime_provider.cpp" \
    "$SRC/xykell_runtime_provider.cpp"
echo "UNIT: $pass/22 suites PASS"
rm -rf "$WORK"
