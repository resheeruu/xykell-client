cmake_minimum_required(VERSION 3.22)

project(xykell VERSION 0.1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Phone-first: Termux clang targeting ARM64 Android (minSdk 28, Levi's floor).
add_compile_options(-target aarch64-linux-android28)
add_link_options(-target aarch64-linux-android28)

# NOTE: strict warnings are target-scoped (below), never global: FetchContent
# dependencies must not inherit our -Werror.
add_compile_options(-fPIC)

add_library(
    xykell
    SHARED
    src/xykell.cpp
    src/xykell_clickgui_model.cpp
    src/xykell_config_store.cpp
    src/xykell_core.cpp
    src/xykell_crash_guard.cpp
    src/xykell_detection.cpp
    src/xykell_engines.cpp
    src/xykell_event_bus.cpp
    src/xykell_file_util.cpp
    src/xykell_friends.cpp
    src/xykell_gui_controller.cpp
    src/xykell_gui_host.cpp
    src/xykell_hud.cpp
    src/xykell_hud_model.cpp
    src/xykell_hud_renderer.cpp
    src/xykell_input_router.cpp
    src/xykell_json_min.cpp
    src/xykell_load_stages.cpp
    src/xykell_menu.cpp
    src/xykell_module_manager.cpp
    src/xykell_notifications.cpp
    src/xykell_planner.cpp
    src/xykell_portal.cpp
    src/xykell_portal_preloader.cpp
    src/xykell_profile_manager.cpp
    src/xykell_runtime_active.cpp
    src/xykell_runtime_probe.cpp
    src/xykell_runtime_provider.cpp
    src/xykell_lan_discovery.cpp
    src/xykell_server_profiles.cpp
    src/xykell_sigscan.cpp
    src/xykell_theme.cpp
    src/xykell_version_adapter.cpp
    src/xykell_waypoints.cpp
)

target_compile_options(xykell PRIVATE -Wall -Wextra -Werror)

target_include_directories(
    xykell
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/preloader-android/include
)

# fmt: preloader's Logger.hpp needs it. Prefer system package (phone already
# has it: zero downloads); fetch pinned source only when absent (CI runners).
find_package(fmt 11 QUIET)
if(NOT fmt_FOUND)
    include(FetchContent)
    FetchContent_Declare(
        fmt
        GIT_REPOSITORY https://github.com/fmtlib/fmt.git
        GIT_TAG 11.2.0
    )
    FetchContent_MakeAvailable(fmt)
endif()

# Preloader runtime lib: the NDK linker forbids undefined symbols, while the
# phone (Termux clang) permits them for runtime resolution. Default OFF keeps
# the proven phone path; CI passes -DXYKELL_LINK_PRELOADER=ON (documented Levi
# path: link the `preloader` target from pinned preloader-android 0.2.3).
option(XYKELL_LINK_PRELOADER "Link preloader runtime lib (NDK ld needs it)" OFF)
if(XYKELL_LINK_PRELOADER)
    FetchContent_Declare(
        preloader_android
        GIT_REPOSITORY https://github.com/LiteLDev/preloader-android.git
        GIT_TAG 0.2.3
    )
    FetchContent_MakeAvailable(preloader_android)
    target_link_libraries(xykell PRIVATE preloader)
endif()

# __android_log_print + fmt::format (system or fetched above).
target_link_libraries(xykell PRIVATE log fmt)

set_target_properties(xykell PROPERTIES OUTPUT_NAME xykell)
