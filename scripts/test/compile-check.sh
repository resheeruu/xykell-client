#!/usr/bin/env bash
# Compile-check arbitrary pure-Kotlin sources against the android-free CP.
# Used by module workers between edits; run-kotlin-unit.sh remains the gate.
#   bash scripts/test/compile-check.sh <file.kt> [more.kt ...]
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

KOTLINC_BIN="${KOTLINC:-$HOME/local/opt/kotlinc/bin/kotlinc}"
JARS_DIR="${XYKELL_JVM_JARS:-$HOME/local/opt/jvm-jars}"
ANDROID_JAR="${XYKELL_ANDROID_JAR:-$HOME/android-sdk/platforms/android-35/android.jar}"
WORK="${XYKELL_KOTLIN_WORK:-/data/data/com.termux/files/usr/tmp/opencode/xykell-cc}"
# Small sources need little heap; parallel workers on a phone must not each
# grab the default 1.4 GB.
HEAP="${XYKELL_CC_HEAP:-900m}"

[ "$#" -ge 1 ] || { echo "usage: compile-check.sh <file.kt> ..." >&2; exit 2; }

CP="$JARS_DIR/junit-4.13.2.jar:$JARS_DIR/hamcrest-core-1.3.jar:$JARS_DIR/json-20240303.jar:$JARS_DIR/okhttp-4.12.0.jar:$JARS_DIR/okio-jvm-3.6.0.jar:$ANDROID_JAR"
KOTLINC_HOME="$(cd "$(dirname "$KOTLINC_BIN")/.." && pwd)"
for lib in kotlin-stdlib.jar kotlin-stdlib-jdk7.jar kotlin-stdlib-jdk8.jar; do
    CP="$CP:$KOTLINC_HOME/lib/$lib"
done

WORK="${WORK}.$$"
rm -rf "$WORK" && mkdir -p "$WORK/classes"
"$KOTLINC_BIN" -J-Xmx"$HEAP" -nowarn -cp "$CP" -d "$WORK/classes" "$@" \
    2> "$WORK/kotlinc.log" || {
        echo "COMPILE-CHECK: FAIL" >&2
        cat "$WORK/kotlinc.log" >&2
        exit 1
    }
echo "COMPILE-CHECK: PASS ($# files)"
rm -rf "$WORK"