#!/usr/bin/env bash
# Builds FeatherTV without Gradle: aapt2 -> javac -> R8 -> recompression -> zipalign -> apksigner.
# Usage: ./build.sh             (produces build/feathertv.apk)
#        ./build.sh --install   (also installs it with adb on $FTV_DEVICE)
#        FTV_DEBUG=1 ./build.sh (without R8: unshrunk dex, readable names in stack traces)
set -euo pipefail
cd "$(dirname "$0")"

VERSION_CODE=2
VERSION_NAME=1.0.0
MIN_SDK=22
TARGET_SDK=28
BUILD_TOOLS=29.0.2
PLATFORM=android-28

to_unix() { if command -v cygpath >/dev/null 2>&1; then cygpath -u "$1"; else printf '%s' "$1"; fi; }
to_native() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }

SDK="$(to_unix "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/sdk}}")"
BT="$SDK/build-tools/$BUILD_TOOLS"
ANDROID_JAR="$SDK/platforms/$PLATFORM/android.jar"
LAMBDA_STUBS="$BT/core-lambda-stubs.jar"
if [ -n "${JAVA_HOME:-}" ]; then JBIN="$(to_unix "$JAVA_HOME")/bin/"; else JBIN=""; fi
tool() { if [ -e "$BT/$1.exe" ]; then printf '%s' "$BT/$1.exe"; else printf '%s' "$BT/$1"; fi; }
AAPT2="$(tool aapt2)"; AAPT="$(tool aapt)"; ZIPALIGN="$(tool zipalign)"
SEP=':'; command -v cygpath >/dev/null 2>&1 && SEP=';'

for f in "$ANDROID_JAR" "$AAPT2" "$BT/lib/d8.jar" "$BT/lib/apksigner.jar" "$LAMBDA_STUBS"; do
  [ -e "$f" ] || { echo "Missing: $f (check ANDROID_HOME and build-tools $BUILD_TOOLS)" >&2; exit 1; }
done
[ -f keys/feathertv.jks ] && [ -f keys/keystore.pass ] || { echo "Signing key missing in keys/ (see README)" >&2; exit 1; }

# Only the temporary folder is cleaned: build/ also keeps measurements and the R8 map.
OUT=build
TMP="$OUT/tmp"
rm -rf "$TMP"
mkdir -p "$TMP/gen" "$TMP/classes" "$TMP/dex"

echo "> resources"
"$AAPT2" compile --dir res -o "$TMP/res.zip"
"$AAPT2" link -I "$ANDROID_JAR" --manifest AndroidManifest.xml \
  --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --proguard "$TMP/aapt-rules.txt" \
  --java "$TMP/gen" -o "$TMP/linked.apk" "$TMP/res.zip"

echo "> java"
# Lambdas need core-lambda-stubs.jar to compile; R8/D8 then desugar them for Android.
BOOTCP="$(to_native "$ANDROID_JAR")$SEP$(to_native "$LAMBDA_STUBS")"
find src "$TMP/gen" -name '*.java' > "$TMP/sources.txt"
"${JBIN}javac" -source 1.8 -target 1.8 -encoding UTF-8 -Xlint:-options -g:none \
  -bootclasspath "$BOOTCP" -d "$TMP/classes" @"$TMP/sources.txt"

echo "> dex"
CLASSES=$(find "$TMP/classes" -name '*.class')
if [ -n "${FTV_DEBUG:-}" ]; then
  # shellcheck disable=SC2086
  "${JBIN}java" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --release \
    --min-api "$MIN_SDK" --lib "$ANDROID_JAR" --output "$TMP/dex" $CLASSES
else
  # shellcheck disable=SC2086
  "${JBIN}java" -cp "$BT/lib/d8.jar" com.android.tools.r8.R8 --release \
    --min-api "$MIN_SDK" --lib "$ANDROID_JAR" --lib "$LAMBDA_STUBS" \
    --pg-conf "$TMP/aapt-rules.txt" --pg-conf proguard-rules.pro \
    --output "$TMP/dex" $CLASSES
fi
(cd "$TMP/dex" && "$AAPT" add ../linked.apk classes.dex >/dev/null)

echo "> compression"
mkdir -p "$TMP/tools"
"${JBIN}javac" -nowarn -d "$TMP/tools" tools/Repack.java
"${JBIN}java" -cp "$(to_native "$TMP/tools")" Repack "$TMP/linked.apk" "$TMP/packed.apk"

echo "> signing"
"$ZIPALIGN" -f -p 4 "$TMP/packed.apk" "$TMP/aligned.apk"
"${JBIN}java" -jar "$BT/lib/apksigner.jar" sign \
  --ks keys/feathertv.jks --ks-key-alias feathertv \
  --ks-pass file:keys/keystore.pass \
  --out "$OUT/feathertv.apk" "$TMP/aligned.apk"
rm -f "$OUT"/*.idsig

SIZE=$(wc -c < "$OUT/feathertv.apk" | tr -d ' ')
echo "OK  $OUT/feathertv.apk  ($SIZE bytes, v$VERSION_NAME)"

if [ "${1:-}" = "--install" ]; then
  ADB="${ADB:-adb}"
  "$ADB" ${FTV_DEVICE:+-s "$FTV_DEVICE"} install -r "$OUT/feathertv.apk"
fi
