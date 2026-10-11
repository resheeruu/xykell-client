#!/usr/bin/env bash
# Build the JNI bridge (libxykellcore.so) into app/src/main/jniLibs so AGP can
# package it.
#
# WHY THIS EXISTS SEPARATELY FROM build-apk.sh
# scripts/build-apk.sh is the manual no-Gradle path: it assembles a complete APK
# into a temp directory. build-gradle.sh used to call it to "build the native
# libs first", but that script writes its libraries into its own temp work
# directory, never into app/src/main/jniLibs. Gradle packages only what is under
# jniLibs, so the bridge was silently absent from every Gradle-built APK and
# every System.loadLibrary("xykellcore") call failed at runtime. The app still
# launched, because each JNI facade catches UnsatisfiedLinkError -- which is
# exactly what made the omission invisible: every native-backed feature
# degraded, and nothing said why.
#
# This script does the one thing that step was supposed to do, and it fails
# loudly rather than leaving a half-populated jniLibs behind.
#
# ARM64 only, cross-compiled against the NDK sysroot (the NDK's own binaries are
# x86-64 and cannot execute on a phone). Output is a build artefact and stays
# gitignored.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

NDK="${XYKELL_NDK:-$HOME/android-sdk/ndk/26.1.10909125}"
SYS="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
CLUNW="$NDK/toolchains/llvm/prebuilt/linux-x86_64/lib/clang/17/lib/linux/aarch64"
OUT="app/src/main/jniLibs/arm64-v8a"

fail() { echo "BUILD-JNI: FAIL — $*" >&2; exit 1; }

command -v clang++ >/dev/null 2>&1 || fail "clang++ not found"
[ -d "$SYS" ] || fail "NDK sysroot missing at $SYS (set XYKELL_NDK)"
[ -f "$CLUNW/libunwind.a" ] || fail "NDK libunwind.a missing at $CLUNW"

mkdir -p "$OUT"
LOG="${XYKELL_JNI_LOG:-/data/data/com.termux/files/usr/tmp/opencode/xykell-jni.log}"
mkdir -p "$(dirname "$LOG")"

echo "BUILD-JNI: compiling libxykellcore.so -> $OUT"
clang++ -shared -fPIC -O2 -std=c++17 -Wl,-z,defs -L"$CLUNW" \
    --target=aarch64-linux-android28 --sysroot="$SYS" \
    -I native/include \
    app/src/main/cpp/bridge.cpp \
    native/src/xykell_json_min.cpp native/src/xykell_file_util.cpp \
    native/src/xykell_profile_manager.cpp native/src/xykell_config_store.cpp \
    native/src/xykell_hud_model.cpp native/src/xykell_theme.cpp \
    native/src/xykell_hud_renderer.cpp native/src/xykell_module_manager.cpp \
    native/src/xykell_notifications.cpp \
    native/src/xykell_keybinds.cpp native/src/xykell_keybind_store.cpp \
    native/src/xykell_version_adapter.cpp native/src/xykell_detection.cpp \
    native/src/xykell_runtime_provider.cpp native/src/xykell_runtime_session.cpp \
    native/src/xykell_lan_discovery.cpp \
    -o "$OUT/libxykellcore.so" 2> "$LOG" \
    || { tail -20 "$LOG" >&2; fail "native link (see $LOG)"; }

# libc++_shared.so is an NDK library Android does not expose to third-party
# apps, and it is a load-time dependency: without it in the APK,
# System.loadLibrary("xykellcore") fails with "libc++_shared.so not found" and
# no amount of Kotlin-side try/catch helps.
STDLIB="$SYS/usr/lib/aarch64-linux-android/libc++_shared.so"
[ -f "$STDLIB" ] || fail "C++ runtime missing at $STDLIB"

# Both must be the same ELF class, or the APK ships a 32-bit runtime beside a
# 64-bit library and the dlopen failure just changes shape.
cls() { readelf -h "$1" 2>/dev/null | sed -n 's/.*Class: *\([A-Z0-9]*\).*/\1/p'; }
[ "$(cls "$STDLIB")" = "$(cls "$OUT/libxykellcore.so")" ] \
    || fail "C++ runtime is $(cls "$STDLIB") but the bridge is $(cls "$OUT/libxykellcore.so")"
cp "$STDLIB" "$OUT/libc++_shared.so"

# Every NEEDED entry other than the platform libraries must be packaged next to
# the bridge. Unmet ones surface as dlopen failures at runtime.
for NEEDED in $(readelf -d "$OUT/libxykellcore.so" 2>/dev/null \
        | sed -n 's/.*Shared library: \[\(.*\)\]/\1/p'); do
    case "$NEEDED" in
        libc.so|libdl.so|libm.so|liblog.so) continue ;;  # Android provides these
    esac
    [ -f "$OUT/$NEEDED" ] || fail "bridge needs $NEEDED but it is not packaged"
done

EXPORTS=$(nm -D --defined-only "$OUT/libxykellcore.so" | grep -c "Java_dev" || true)
[ "$EXPORTS" -gt 0 ] || fail "bridge exports no JNI symbols; the Kotlin facades would all fail"

echo "BUILD-JNI: OK — $EXPORTS JNI exports, $(du -h "$OUT/libxykellcore.so" | cut -f1) bridge"
