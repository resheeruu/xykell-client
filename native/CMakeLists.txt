cmake_minimum_required(VERSION 3.22)

project(xykell VERSION 0.1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Phone-first: Termux clang targeting ARM64 Android (minSdk 28, Levi's floor).
add_compile_options(-target aarch64-linux-android28)
add_link_options(-target aarch64-linux-android28)

add_compile_options(-Wall -Wextra -Werror -fPIC)

add_library(
    xykell
    SHARED
    src/xykell.cpp
    src/xykell_core.cpp
    src/xykell_version_adapter.cpp
)

target_include_directories(
    xykell
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/preloader-android/include
)

# __android_log_print + fmt::format (both verified present on this device).
target_link_libraries(xykell PRIVATE log fmt)

set_target_properties(xykell PROPERTIES OUTPUT_NAME xykell)
