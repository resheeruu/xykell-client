#!/usr/bin/env bash
# On-device debug APK assembly — no Gradle (phone is ARM64; CI remains the
# authority via gradle :app:assembleDebug, see .github/workflows/).
# Toolchain (all ARM-native, all installed on the phone):
#   termux aapt2/d8/zipalign, JDK 17 (javac/keytool), kotlinc (typecheck
#   toolchain), termux clang cross-compile against the NDK *sysroot* (NDK
#   prebuilt binaries are x86-64 and cannot execute here).
# Output: app/build/outputs/apk/debug/app-debug.apk (same path CI uses).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

WORK="${XYKELL_APK_WORK:-/data/data/com.termux/files/usr/tmp/opencode/xykell-apk}"
NDK="${XYKELL_NDK:-$HOME/android-sdk/ndk/26.1.10909125}"
ANDROID_JAR="${XYKELL_ANDROID_JAR:-$HOME/android-sdk/platforms/android-35/android.jar}"
BT="${XYKELL_BUILD_TOOLS:-$HOME/android-sdk/build-tools/35.0.0}"
AX="$HOME/local/opt/androidx-jars"
JVM="$HOME/local/opt/jvm-jars"
KOTLINC="${KOTLINC:-$HOME/local/opt/kotlinc/bin/kotlinc}"
KOTLINC_LIB="$(dirname "$(dirname "$KOTLINC")")/lib"
OUT="app/build/outputs/apk/debug/app-debug.apk"

PKG="dev.xykell.client"
VCODE=1
VNAME="0.1.0-m1.5"

fail() { echo "BUILD-APK: FAIL — $*" >&2; exit 1; }
need() { command -v "$1" >/dev/null || fail "missing command: $1"; }
need aapt2; need d8; need zipalign; need javac; need keytool; need zip; need clang++
[ -x "$KOTLINC" ] || fail "kotlinc not at $KOTLINC"
[ -f "$ANDROID_JAR" ] || fail "android.jar not at $ANDROID_JAR"
SYS="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
CLUNW="$NDK/toolchains/llvm/prebuilt/linux-x86_64/lib/clang/17/lib/linux/aarch64"
[ -d "$SYS" ] || fail "NDK sysroot not at $SYS"
[ -f "$CLUNW/libunwind.a" ] || fail "NDK libunwind.a not at $CLUNW"

rm -rf "$WORK"
mkdir -p "$WORK/gen" "$WORK/rclasses" "$WORK/classes" "$WORK/lib/arm64-v8a" \
         "$WORK/assets" "$WORK/aars"

step() { echo "BUILD-APK: $* ..."; }

# --- 1. native: libxykellcore.so (cross-compile against NDK sysroot) -------
step "native libxykellcore.so"
# The HUD renderer is platform-free (no portal backend), so it links here as
# well as in the game module: HudOverlayService draws the same lines.
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
    -o "$WORK/lib/arm64-v8a/libxykellcore.so" 2> "$WORK/native.log" \
    || { tail -5 "$WORK/native.log"; fail "native link (see $WORK/native.log)"; }
JNI_EXPORTS=$(nm -D --defined-only "$WORK/lib/arm64-v8a/libxykellcore.so" | grep -c "Java_dev" || true)
# 43: NativeHud.renderHudLines, which renders the HUD through the same tested
# C++ renderer the game-side overlay uses and hands the lines to the Android
# overlay window (HudOverlayService).
# 42 before it: nativeOfferVitals (observed SetHealth 0x2A / SetTime 0x0A) and
# nativeOfferPopulation (relay entity counts), both added with the observation
# path that made hud.health / low_health / entity_counter / tps deliverable.
[ "$JNI_EXPORTS" -eq 44 ] || fail "expected 44 Java_dev* exports, got $JNI_EXPORTS"

# --- 2. assets: registry catalog (mirrors gradle copyRegistry) -------------
step "assets registry"
cp registry/features.json "$WORK/assets/features.json"

# --- 3. resources: aapt2 compile app res + dependency AAR res --------------
step "aapt2 compile (app res)"
aapt2 compile --dir app/src/main/res -o "$WORK/app-res.zip"
step "aapt2 compile (dependency res)"
# Extract res/ from every AAR we hold (libraries the app links against).
i=0
AAR_RES_ZIPS=()
for aar in "$AX"/*.aar; do
    [ -f "$aar" ] || continue
    name=$(basename "$aar" .aar)
    mkdir -p "$WORK/aars/$name"
    unzip -qo "$aar" "res/*" -d "$WORK/aars/$name" 2>/dev/null || continue
    [ -d "$WORK/aars/$name/res" ] || continue
    aapt2 compile --dir "$WORK/aars/$name/res" -o "$WORK/aar-$i.zip"
    AAR_RES_ZIPS+=("$WORK/aar-$i.zip")
    i=$((i + 1))
done
echo "BUILD-APK: compiled $i dependency res sets"

# --- 4. manifest with package injected (namespace, AGP would do this) ------
step "aapt2 link"
python3 - "$ROOT/app/src/main/AndroidManifest.xml" "$WORK/AndroidManifest.xml" "$PKG" <<'PYEOF'
import sys
src, dst, pkg = sys.argv[1:4]
text = open(src, encoding="utf-8").read()
assert 'package=' not in text, "manifest already has package"
needle = "<manifest "
assert needle in text
text = text.replace(needle, f'<manifest package="{pkg}" ', 1)
open(dst, "w", encoding="utf-8").write(text)
PYEOF

link_args=(
    -o "$WORK/base.apk"
    -I "$ANDROID_JAR"
    --manifest "$WORK/AndroidManifest.xml"
    --java "$WORK/gen"
    --min-sdk-version 28 --target-sdk-version 35
    --version-code "$VCODE" --version-name "$VNAME"
    --auto-add-overlay
)
# Dependency res first, app res last so the app wins under --auto-add-overlay.
for z in ${AAR_RES_ZIPS[@]+"${AAR_RES_ZIPS[@]}"}; do link_args+=("$z"); done
link_args+=("$WORK/app-res.zip")
aapt2 link "${link_args[@]}" -A "$WORK/assets" || fail "aapt2 link"

# --- 5. R classes (real R from aapt2, not the typecheck stub) --------------
step "javac R"
find "$WORK/gen" -name 'R.java' -print0 | xargs -0 javac -nowarn -d "$WORK/rclasses" \
    || fail "javac R"

# --- 6. kotlinc: all main sources against real R + android.jar + deps ------
step "kotlinc main sources"
# Same classpath strategy as run-kotlin-typecheck.sh: every jar we hold.
# A curated subset already missed androidx.viewpager — glob is both simpler
# and what the green typecheck gate proved. kotlinc tolerates version dups;
# only d8 needs a curated (duplicate-free) list below.
CLASSPATH="$WORK/rclasses:$ANDROID_JAR"
for j in "$AX"/*.jar "$JVM"/*.jar; do
    [ -f "$j" ] && CLASSPATH="$CLASSPATH:$j"
done
find app/src/main/java -name '*.kt' > "$WORK/sources.list"
"$KOTLINC" -J-Xmx1400m -nowarn -jvm-target 17 -cp "$CLASSPATH" \
    -d "$WORK/classes" @"$WORK/sources.list" || fail "kotlinc"

# --- 7. dex: app classes + R + runtime deps -------------------------------
step "d8 dex"
DEX_JARS=(
    "$AX/appcompat-1.7.0.classes.jar"
    "$AX/activity-1.9.2.classes.jar"
    "$AX/fragment-1.8.2.classes.jar"
    "$AX/core-1.13.1.classes.jar"
    "$AX/savedstate-1.2.1.classes.jar"
    "$AX/lifecycle-viewmodel-savedstate-2.8.4.classes.jar"
    "$AX/annotation-1.8.2.jar"
    "$AX/annotation-jvm-1.8.2.jar"
    "$AX/annotations-13.0.jar"
    "$AX/annotation-experimental-1.0.0.jar"
    "$AX/core-common-2.2.0.jar"
    "$AX/core-runtime-2.2.0.jar"
    "$AX/core-ktx-1.13.0.jar"
    "$AX/collection-1.0.0.jar"
    "$AX/concurrent-futures-1.0.0.jar"
    "$AX/listenablefuture-1.0.jar"
    "$AX/cursoradapter-1.0.0.jar"
    "$AX/customview-1.0.0.jar"
    "$AX/drawerlayout-1.0.0.jar"
    "$AX/emoji2-1.3.0.jar"
    "$AX/emoji2-views-helper-1.2.0.jar"
    "$AX/interpolator-1.0.0.jar"
    "$AX/lifecycle-common-2.8.4.jar"
    "$AX/lifecycle-livedata-2.0.0.jar"
    "$AX/lifecycle-livedata-core-2.5.1.jar"
    "$AX/lifecycle-process-2.4.1.jar"
    "$AX/lifecycle-runtime-2.6.1.jar"
    "$AX/lifecycle-viewmodel-2.6.1.jar"
    "$AX/loader-1.0.0.jar"
    "$AX/profileinstaller-1.3.1.jar"
    "$AX/resourceinspection-annotation-1.0.1.jar"
    "$AX/startup-runtime-1.0.0.jar"
    "$AX/tracing-1.0.0.jar"
    "$AX/vectordrawable-1.1.0.jar"
    "$AX/vectordrawable-animated-1.1.0.jar"
    "$AX/versionedparcelable-1.1.1.jar"
    "$AX/viewpager-1.0.0.jar"
    # One stdlib only: the kotlinc 1.9.24 stdlib the sources compiled against
    # (1.8.22 + jdk7/8 duplicates its merged classes — d8 rejects dups).
    "$KOTLINC_LIB/kotlin-stdlib.jar"
    "$AX/kotlinx-coroutines-android-1.6.4.jar"
    "$AX/kotlinx-coroutines-core-jvm-1.6.4.jar"
    "$JVM/okhttp-4.12.0.jar"
    "$JVM/okio-jvm-3.6.0.jar"
)
# Directories would feed d8 META-INF/*.kotlin_module → "Unsupported source
# file type". Jar them; d8 reads only .class entries from jar inputs.
(cd "$WORK/classes" && jar cf "$WORK/app-classes.jar" .) || fail "jar classes"
(cd "$WORK/rclasses" && jar cf "$WORK/r-classes.jar" .) || fail "jar rclasses"
DEX_INPUTS=("$WORK/app-classes.jar" "$WORK/r-classes.jar")
for j in "${DEX_JARS[@]}"; do
    [ -f "$j" ] || fail "missing dex jar: $j"
    DEX_INPUTS+=("$j")
done
mkdir -p "$WORK/dex"
d8 --release --min-api 28 --lib "$ANDROID_JAR" --output "$WORK/dex" \
    "${DEX_INPUTS[@]}" || fail "d8"

# --- 8. package: base.apk + classes.dex + lib + assets ---------------------
step "package"
cp "$WORK/base.apk" "$WORK/unsigned.apk"
(cd "$WORK" && zip -q -j unsigned.apk dex/classes.dex) || fail "zip dex"
(cd "$WORK" && zip -qr unsigned.apk lib assets) || fail "zip lib/assets"

# --- 9. align + sign (debug key, mirrors AGP debug signing) ---------------
step "zipalign + sign"
zipalign -f -p 4 "$WORK/unsigned.apk" "$WORK/aligned.apk" || fail "zipalign"
KS="$WORK/debug.keystore"
if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US" || fail "keytool"
fi
mkdir -p "$(dirname "$OUT")"
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out "$OUT" "$WORK/aligned.apk" || fail "apksigner sign"
"$BT/apksigner" verify --verbose "$OUT" > "$WORK/verify.txt" || fail "apksigner verify"

step "done"
echo "BUILD-APK: PASS"
echo "  apk: $OUT ($(stat -c%s "$OUT") bytes)"
grep -E "Verified using|number of signatures" "$WORK/verify.txt" || true
