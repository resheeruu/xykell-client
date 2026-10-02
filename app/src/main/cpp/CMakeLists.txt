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
    ${XYKELL_NATIVE_DIR}/src/xykell_version_adapter.cpp
    ${XYKELL_NATIVE_DIR}/src/xykell_detection.cpp
)

target_include_directories(
    xykellcore
    PRIVATE
    ${XYKELL_NATIVE_DIR}/include
)

target_compile_features(xykellcore PRIVATE cxx_std_17)
