cmake_minimum_required(VERSION 3.22)

project(xykellcore)

# Shared implementation with the game-side native module (same files, one
# codebase). Only the platform-free subset is compiled here: no preloader,
# no game, no fmt/log beyond android/log (unused here).
set(XYKELL_NATIVE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../native)

add_library(
    xykellcore
    SHARED
    bridge.cpp
    ${XYKELL_NATIVE_DIR}/src/xykell_json_min.cpp
    ${XYKELL_NATIVE_DIR}/src/xykell_file_util.cpp
    ${XYKELL_NATIVE_DIR}/src/xykell_profile_manager.cpp
    ${XYKELL_NATIVE_DIR}/src/xykell_config_store.cpp
    ${XYKELL_NATIVE_DIR}/src/xykell_hud_model.cpp
    # The HUD renderer is platform-free (hud_renderer.h pulls only model,
    # module_manager, notifications, profile_manager, theme) so the app can
    # render the same tested lines the game-side overlay would. xykell_hud.cpp
    # is deliberately NOT linked here: that one talks to the portal backend.
    ${XYKELL_NATIVE_DIR}/src/xykell_hud_renderer.cpp
    ${XYKELL_NATIVE_DIR}/src/xykell_module_manager.cpp
    ${XYKELL_NATIVE_DIR}/src/xykell_notifications.cpp
    ${XYKELL_NATIVE_DIR}/src/xykell_theme.cpp
    ${XYKELL_NATIVE_DIR}/src/xykell_keybinds.cpp
    ${XYKELL_NATIVE_DIR}/src/xykell_keybind_store.cpp
    ${XYKELL_NATIVE_DIR}/src/xykell_version_adapter.cpp
    ${XYKELL_NATIVE_DIR}/src/xykell_detection.cpp
    ${XYKELL_NATIVE_DIR}/src/xykell_runtime_provider.cpp
    ${XYKELL_NATIVE_DIR}/src/xykell_runtime_session.cpp
    ${XYKELL_NATIVE_DIR}/src/xykell_lan_discovery.cpp
)

target_include_directories(
    xykellcore
    PRIVATE
    ${XYKELL_NATIVE_DIR}/include
)

target_compile_features(xykellcore PRIVATE cxx_std_17)
