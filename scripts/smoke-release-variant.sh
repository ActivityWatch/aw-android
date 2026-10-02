#!/bin/bash

# Runtime smoke test for the R8-minified release variant (aw-android#268).
#
# CI builds `assembleStandardRelease`, but the E2E job only ever runs the debug
# APK. R8 failures are runtime-only: a JNI symbol looked up by name, an
# @JavascriptInterface method removed, a reflectively-instantiated worker
# stripped. The keep rules in mobile/proguard-rules.pro are the mitigation; this
# script is the verification.
#
# It builds the release variant (unless an APK is given), re-signs it with a
# throwaway keystore (release builds are unsigned without secrets), installs it
# on the connected device/emulator, launches it, and fails if logcat shows a
# class/method/JNI resolution error from the app process.
#
# Usage: scripts/smoke-release-variant.sh [path/to/release.apk]
# Env:   ANDROID_HOME (required), SMOKE_SECONDS (default 20), LOGCAT_OUT
#        (default mobile/build/release-smoke-logcat.log)

set -eu

PKG=net.activitywatch.android
APK=${1:-}
SMOKE_SECONDS=${SMOKE_SECONDS:-20}
LOGCAT_OUT=${LOGCAT_OUT:-mobile/build/release-smoke-logcat.log}

if [ -z "${ANDROID_HOME:-}" ]; then
    echo "\$ANDROID_HOME needs to be set" >&2
    exit 1
fi

if [ -z "$APK" ]; then
    ./gradlew :mobile:assembleStandardRelease -x lintVitalStandardRelease
    APK=mobile/build/outputs/apk/standard/release/mobile-standard-release-unsigned.apk
fi
[ -f "$APK" ] || { echo "No APK at $APK" >&2; exit 1; }

# The release variant must really be minified, otherwise this proves nothing.
if [ ! -s mobile/build/outputs/mapping/standardRelease/mapping.txt ]; then
    echo "No R8 mapping.txt: the APK under test was not minified" >&2
    exit 1
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
keytool -genkeypair -keystore "$tmp/smoke.jks" -alias smoke -keyalg RSA -keysize 2048 \
    -validity 2 -storepass android -keypass android -dname "CN=aw-android release smoke" >/dev/null

zipalign=$(find "$ANDROID_HOME/build-tools" -name zipalign -print | head -n 1)
apksigner=$(find "$ANDROID_HOME/build-tools" -name apksigner -print | head -n 1)
"$zipalign" -p 4 "$APK" "$tmp/aligned.apk"
"$apksigner" sign --ks "$tmp/smoke.jks" --ks-key-alias smoke \
    --ks-pass pass:android --key-pass pass:android "$tmp/aligned.apk"

adb uninstall "$PKG" >/dev/null 2>&1 || true
adb logcat -c
adb install -r "$tmp/aligned.apk"
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1
sleep "$SMOKE_SECONDS"

mkdir -p "$(dirname "$LOGCAT_OUT")"
adb logcat -d > "$LOGCAT_OUT"

status=0

# Process must still be alive (a startup crash kills it).
if ! adb shell pidof "$PKG" >/dev/null 2>&1; then
    echo "FAIL: $PKG is not running after ${SMOKE_SECONDS}s" >&2
    status=1
fi

# Resolution errors R8 can introduce, scoped to lines naming our package or a
# fatal exception so unrelated system noise does not trip the check.
if grep -E "ClassNotFoundException|NoSuchMethodError|NoSuchFieldError|UnsatisfiedLinkError|AbstractMethodError" "$LOGCAT_OUT" \
    | grep -E "net\.activitywatch|FATAL EXCEPTION|AndroidRuntime" ; then
    echo "FAIL: R8-style resolution errors in logcat (see $LOGCAT_OUT)" >&2
    status=1
fi
if grep -E "FATAL EXCEPTION" -A3 "$LOGCAT_OUT" | grep -q "$PKG"; then
    echo "FAIL: fatal exception in $PKG" >&2
    status=1
fi

[ "$status" -eq 0 ] && echo "OK: minified release variant launched cleanly"
exit "$status"
