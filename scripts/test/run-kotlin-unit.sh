#!/usr/bin/env bash
# Host JVM tests for app/ pure-Kotlin sources (no Android runtime, no Gradle).
# Phone-local mirror of CI's `gradle :app:testDebugUnitTest`: kotlinc compiles
# only the android-free sources + test sources, JUnitCore runs the suites.
# Toolchain (verified downloads, lives OUTSIDE the repo):
#   ~/local/opt/kotlinc            kotlinc 1.9.24 (official .sha256 checked)
#   ~/local/opt/jvm-jars/*.jar      junit 4.13.2, hamcrest 1.3, json 20240303,
#                                    okhttp 4.12.0, okio-jvm 3.6.0 (SHA-1 checked)
# Count is enforced: a silently dropped suite is a coverage regression.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

KOTLINC_BIN="${KOTLINC:-$HOME/local/opt/kotlinc/bin/kotlinc}"
JARS_DIR="${XYKELL_JVM_JARS:-$HOME/local/opt/jvm-jars}"
WORK="${XYKELL_KOTLIN_WORK:-/data/data/com.termux/files/usr/tmp/opencode/xykell-kotlin}"

if [ ! -x "$KOTLINC_BIN" ]; then
    echo "KOTLIN-UNIT: FAIL — kotlinc not found at $KOTLINC_BIN" >&2
    echo "  install: kotlin-compiler-1.9.24.zip (official .sha256 verified) + junit/hamcrest/json jars" >&2
    exit 1
fi
for jar in junit-4.13.2.jar hamcrest-core-1.3.jar json-20240303.jar \
           okhttp-4.12.0.jar okio-jvm-3.6.0.jar; do
    if [ ! -f "$JARS_DIR/$jar" ]; then
        echo "KOTLIN-UNIT: FAIL — missing $JARS_DIR/$jar" >&2
        exit 1
    fi
done

SRC="$ROOT/app/src/main/java"
TEST="$ROOT/app/src/test/java"
rm -rf "$WORK" && mkdir -p "$WORK/classes"

# android-free main sources only — anything importing android.*/androidx.* is
# compiled by CI's Gradle. Extend this list when a new pure source lands.
MAIN_SOURCES=(
    "$SRC/dev/xykell/client/ui/SettingRowMapper.kt"
    "$SRC/dev/xykell/client/ui/ModuleCatalog.kt"
    "$SRC/dev/xykell/client/ui/ThemeColors.kt"
    "$SRC/dev/xykell/client/ui/HudPreview.kt"
    "$SRC/dev/xykell/client/ui/KeyLabels.kt"
    "$SRC/dev/xykell/client/ui/HomeStatus.kt"
    "$SRC/dev/xykell/client/runtime/servers/ServerStore.kt"
    "$SRC/dev/xykell/client/runtime/worlds/NbtReader.kt"
    "$SRC/dev/xykell/client/runtime/worlds/WorldStore.kt"
    "$SRC/dev/xykell/client/runtime/packs/PackStore.kt"
    "$SRC/dev/xykell/client/runtime/observation/Observations.kt"
    "$SRC/dev/xykell/client/runtime/observation/ObservationTranslator.kt"
    "$SRC/dev/xykell/client/runtime/observation/ObservationStateMachine.kt"
    "$SRC/dev/xykell/client/runtime/observation/ObservationCrypto.kt"
    "$SRC/dev/xykell/client/runtime/observation/LiveProducer.kt"
    "$SRC/dev/xykell/client/runtime/observation/LoopbackWebSocket.kt"
)
SUITES=(
    dev.xykell.client.ui.SettingRowMapperTest
    dev.xykell.client.ui.HudPreviewTest
    dev.xykell.client.ui.ThemeColorsTest
    dev.xykell.client.ui.ModuleCatalogTest
    dev.xykell.client.ui.KeyLabelsTest
    dev.xykell.client.ui.HomeStatusTest
    dev.xykell.client.runtime.servers.ServerStoreTest
    dev.xykell.client.runtime.worlds.WorldStoreTest
    dev.xykell.client.runtime.packs.PackStoreTest
    dev.xykell.client.runtime.observation.ObservationPipelineTest
)
EXPECTED_SUITES=10

if [ "${#SUITES[@]}" -ne "$EXPECTED_SUITES" ]; then
    echo "KOTLIN-UNIT: FAIL — suite list has ${#SUITES[@]}, expected $EXPECTED_SUITES" >&2
    exit 1
fi
for f in "${MAIN_SOURCES[@]}"; do
    if [ ! -f "$f" ]; then
        echo "KOTLIN-UNIT: FAIL — missing source $f" >&2
        exit 1
    fi
done
for s in "${SUITES[@]}"; do
    path="$TEST/$(echo "$s" | tr . /).kt"
    if [ ! -f "$path" ]; then
        echo "KOTLIN-UNIT: FAIL — missing test source $path" >&2
        exit 1
    fi
done

CP="$JARS_DIR/junit-4.13.2.jar:$JARS_DIR/hamcrest-core-1.3.jar:$JARS_DIR/json-20240303.jar:$JARS_DIR/okhttp-4.12.0.jar:$JARS_DIR/okio-jvm-3.6.0.jar"
# kotlinc's own stdlib (compiled classes need it at runtime too).
KOTLINC_HOME="$(cd "$(dirname "$KOTLINC_BIN")/.." && pwd)"
for lib in kotlin-stdlib.jar kotlin-stdlib-jdk7.jar kotlin-stdlib-jdk8.jar; do
    if [ ! -f "$KOTLINC_HOME/lib/$lib" ]; then
        echo "KOTLIN-UNIT: FAIL — missing $KOTLINC_HOME/lib/$lib" >&2
        exit 1
    fi
    CP="$CP:$KOTLINC_HOME/lib/$lib"
done
"$KOTLINC_BIN" -nowarn -cp "$CP" -d "$WORK/classes" \
    "${MAIN_SOURCES[@]}" "$TEST"/dev/xykell/client/ui/*Test.kt \
    "$TEST"/dev/xykell/client/runtime/observation/ObservationPipelineTest.kt \
    "$TEST"/dev/xykell/client/runtime/servers/ServerStoreTest.kt \
    "$TEST"/dev/xykell/client/runtime/worlds/WorldStoreTest.kt \
    "$TEST"/dev/xykell/client/runtime/packs/PackStoreTest.kt \
    2> "$WORK/kotlinc.log" || {
        echo "KOTLIN-UNIT: FAIL — kotlinc compile error" >&2
        cat "$WORK/kotlinc.log" >&2
        exit 1
    }

pass=0
for s in "${SUITES[@]}"; do
    if out=$(java -cp "$WORK/classes:$CP" org.junit.runner.JUnitCore "$s" 2>&1); then
        echo "$out" | grep -E "^OK \(" | sed "s/^OK/$(basename "$s" .kt): PASS —/"
        pass=$((pass + 1))
    else
        echo "$(basename "$s" .kt): FAIL" >&2
        echo "$out" >&2
        echo "KOTLIN-UNIT: FAIL — $pass/$EXPECTED_SUITES suites passed" >&2
        exit 1
    fi
done

echo "KOTLIN-UNIT: $pass/$EXPECTED_SUITES suites PASS"
rm -rf "$WORK"
