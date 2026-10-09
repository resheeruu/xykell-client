#!/usr/bin/env bash
# Build the APK with AGP (the only supported packaging path since 0.2.6).
#
# The only host-specific wrinkle is aapt2: AGP downloads an x86-64 binary that
# cannot execute on aarch64, so on Termux we point it at the native aapt2.
# Everywhere else the default is correct, so the override is only passed when
# a native aapt2 actually exists.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TASK="${1:-assembleRelease}"

GRADLE="${XYKELL_GRADLE:-}"
if [ -z "$GRADLE" ]; then
    if [ -x "$ROOT/gradlew" ]; then
        GRADLE="$ROOT/gradlew"
    elif command -v gradle >/dev/null 2>&1; then
        GRADLE="gradle"
    else
        echo "build-gradle: no gradle found." >&2
        echo "  Install one, or set XYKELL_GRADLE=/path/to/gradle." >&2
        exit 1
    fi
fi

# Native libs are build outputs and are gitignored. A fresh clone has to build
# them before AGP can package them.
if [ ! -f "$ROOT/app/src/main/jniLibs/arm64-v8a/libxykellcore.so" ]; then
    echo "build-gradle: native libs missing; building them first"
    "$ROOT/scripts/build-apk.sh" || {
        echo "build-gradle: native build failed" >&2
        exit 1
    }
fi

ARGS=()
if command -v aapt2 >/dev/null 2>&1; then
    native_aapt2="$(command -v aapt2)"
    if "$native_aapt2" version >/dev/null 2>&1; then
        ARGS+=("-Pandroid.aapt2FromMavenOverride=$native_aapt2")
    fi
fi

exec "$GRADLE" "$TASK" --no-daemon --console=plain "${ARGS[@]}"
