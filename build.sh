#!/usr/bin/env bash
# Builds and signs the APK with the Android SDK's own tools: no Gradle, no network,
# no libraries beyond the platform (Rules 9, 14).
#
#   ORGANIZER_KEYSTORE=/path/key.jks ORGANIZER_KEYSTORE_PASS=... ./build.sh --out DIR
#
# Needs ANDROID_HOME with build-tools 36.0.0 and platform android-34, and a JDK.
set -euo pipefail
cd "$(dirname "$0")"

VERSION_CODE=1
VERSION_NAME=1.0.0
BUILD_TOOLS=36.0.0
PLATFORM=android-34
MIN_SDK=26
TARGET_SDK=34
KEY_ALIAS=organizer
PACKAGE=io.github.chronicle8.lnorganizer

OUT=""
while [ $# -gt 0 ]; do
  case "$1" in
    --out) OUT="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 64 ;;
  esac
done
[ -n "$OUT" ] || { echo "usage: build.sh --out DIR" >&2; exit 64; }
: "${ANDROID_HOME:?set ANDROID_HOME to the Android SDK}"
: "${ORGANIZER_KEYSTORE:?set ORGANIZER_KEYSTORE to the signing keystore}"
: "${ORGANIZER_KEYSTORE_PASS:?set ORGANIZER_KEYSTORE_PASS}"
BT="$ANDROID_HOME/build-tools/$BUILD_TOOLS"
JAR="$ANDROID_HOME/platforms/$PLATFORM/android.jar"
[ -x "$BT/aapt2" ] || { echo "missing build-tools $BUILD_TOOLS in $BT" >&2; exit 1; }
[ -f "$JAR" ] || { echo "missing platform $PLATFORM ($JAR)" >&2; exit 1; }
[ -f "$ORGANIZER_KEYSTORE" ] || { echo "keystore not found: $ORGANIZER_KEYSTORE" >&2; exit 1; }

APK="$OUT/LibraryOrganizer-$VERSION_NAME.apk"
T="$OUT/work"
rm -rf "$T"
mkdir -p "$T/gen" "$T/classes" "$T/dex"

echo "== resources"
"$BT/aapt2" compile --dir app/res -o "$T/res.zip"
"$BT/aapt2" link -I "$JAR" --manifest app/AndroidManifest.xml \
  --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --java "$T/gen" -o "$T/base.apk" "$T/res.zip"

echo "== java"
# Compiled against android.jar as the boot class path, so only Java APIs that
# Android has can be used (a JDK-only call would compile and then crash on the phone).
find app/src "$T/gen" -name '*.java' > "$T/sources.txt"
# core-lambda-stubs.jar supplies the lambda bootstrap that android.jar leaves out.
javac -source 8 -target 8 -bootclasspath "$JAR:$BT/core-lambda-stubs.jar" -Xlint:all,-serial,-options -Werror \
  -encoding UTF-8 -d "$T/classes" @"$T/sources.txt"

echo "== dex"
find "$T/classes" -name '*.class' > "$T/classes.txt"
"$BT/d8" --release --min-api "$MIN_SDK" --lib "$JAR" --output "$T/dex" @"$T/classes.txt"

echo "== package"
cp "$T/base.apk" "$T/unsigned.apk"
(cd "$T/dex" && zip -X -q "../unsigned.apk" classes.dex)
"$BT/zipalign" -p -f 4 "$T/unsigned.apk" "$T/aligned.apk"
mkdir -p "$OUT"
rm -f "$APK"
"$BT/apksigner" sign --ks "$ORGANIZER_KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
  --ks-pass env:ORGANIZER_KEYSTORE_PASS --key-pass env:ORGANIZER_KEYSTORE_PASS \
  --out "$APK" "$T/aligned.apk"

echo "== verify"
"$BT/apksigner" verify --verbose --print-certs "$APK" | grep -E '^(Verified using|Signer #1 certificate (DN|SHA-256))'
BADGING="$("$BT/aapt2" dump badging "$APK")"
echo "$BADGING" | head -1
echo "$BADGING" | grep -q "^package: name='$PACKAGE' versionCode='$VERSION_CODE' versionName='$VERSION_NAME'" \
  || { echo "badging does not show $PACKAGE $VERSION_CODE $VERSION_NAME" >&2; exit 1; }
if echo "$BADGING" | grep -q '^uses-permission'; then
  echo "the APK asks for permissions; it must not (SPEC §8):" >&2
  echo "$BADGING" | grep '^uses-permission' >&2
  exit 1
fi
unzip -l "$APK" | grep -q ' classes.dex$' || { echo "classes.dex missing from the APK" >&2; exit 1; }
rm -rf "$T"
sha256sum "$APK"
echo "built $APK"
