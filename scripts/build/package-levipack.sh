#!/usr/bin/env bash
# Package the M1 .levipack: xykell/ = manifest.json + libxykell.so (+ config/ later).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BUILD="$ROOT/build/native"
DIST="$ROOT/dist"
STAGE="$DIST/xykell"
test -f "$BUILD/libxykell.so" || { echo "missing $BUILD/libxykell.so (run build-native.sh)"; exit 1; }
rm -rf "$STAGE" "$DIST/xykell.levipack"
mkdir -p "$STAGE"
cp "$ROOT/native/manifest.json" "$STAGE/manifest.json"
cp "$BUILD/libxykell.so" "$STAGE/libxykell.so"
(cd "$DIST" && zip -qr xykell.levipack xykell)
ls -la "$DIST/xykell.levipack"
unzip -l "$DIST/xykell.levipack"
