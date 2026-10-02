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
# on the connected device/emulator, completes first-run onboarding so the app
# actually starts the server + WebView, and fails if logcat shows a
# class/method/JNI resolution error from the app process.
#
# Usage: scripts/smoke-release-variant.sh [path/to/release.apk]
# Env:   ANDROID_HOME (required), SMOKE_SECONDS (default 20), LOGCAT_OUT
#        (default mobile/build/release-smoke-logcat.log)
#        MAPPING (default: R8 mapping.txt under the APK's Gradle build root;
#        must be set explicitly for a prebuilt APK living outside one)

set -eu

PKG=net.activitywatch.android
APK=${1:-}
SMOKE_SECONDS=${SMOKE_SECONDS:-20}
LOGCAT_OUT=${LOGCAT_OUT:-mobile/build/release-smoke-logcat.log}

if [ -z "${ANDROID_HOME:-}" ]; then
    echo "\$ANDROID_HOME needs to be set" >&2
    exit 1
fi

# The release variant must really be minified, otherwise this proves nothing.
# Tie the mapping to the APK under test: derive it from the APK's `build/` root
# (the default Gradle outputs tree), or accept it explicitly via MAPPING.
if [ -z "$APK" ]; then
    ./gradlew :mobile:assembleStandardRelease -x lintVitalStandardRelease
    APK=mobile/build/outputs/apk/standard/release/mobile-standard-release-unsigned.apk
    MAPPING=${MAPPING:-mobile/build/outputs/mapping/standardRelease/mapping.txt}
else
    MAPPING=${MAPPING:-}
    case "$APK" in
        */build/outputs/apk/*)
            MAPPING=${MAPPING:-${APK%%/outputs/apk/*}/outputs/mapping/standardRelease/mapping.txt}
            ;;
    esac
fi
[ -f "$APK" ] || { echo "No APK at $APK" >&2; exit 1; }
if [ -z "$MAPPING" ] || [ ! -s "$MAPPING" ]; then
    echo "No R8 mapping.txt (looked at '${MAPPING:-<unset>}'): the APK under test was not minified, or pass MAPPING=/path/to/mapping.txt" >&2
    exit 1
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
keytool -genkeypair -keystore "$tmp/smoke.jks" -alias smoke -keyalg RSA -keysize 2048 \
    -validity 2 -storepass android -keypass android -dname "CN=aw-android release smoke" >/dev/null

zipalign=$(find "$ANDROID_HOME/build-tools" -name zipalign -print | sort -V | tail -n 1)
apksigner=$(find "$ANDROID_HOME/build-tools" -name apksigner -print | sort -V | tail -n 1)
[ -n "$zipalign" ] || { echo "zipalign not found under $ANDROID_HOME/build-tools" >&2; exit 1; }
[ -n "$apksigner" ] || { echo "apksigner not found under $ANDROID_HOME/build-tools" >&2; exit 1; }
"$zipalign" -p 4 "$APK" "$tmp/aligned.apk"
"$apksigner" sign --ks "$tmp/smoke.jks" --ks-key-alias smoke \
    --ks-pass pass:android --key-pass pass:android "$tmp/aligned.apk"

adb uninstall "$PKG" >/dev/null 2>&1 || true
adb logcat -c
adb install -r "$tmp/aligned.apk"
# Usage access must be granted before launch: without it onboarding's Finish
# button refuses and the app never leaves the first-run screen.
adb shell appops set "$PKG" android:get_usage_stats allow >/dev/null 2>&1 || true
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1
sleep 5

# A fresh install always opens OnboardingActivity, which keeps the process alive
# without starting the server or WebView — so a stripped JNI symbol or
# @JavascriptInterface method would pass unnoticed. Tap Continue/Finish on the
# live UI dump until the first-run screen is gone.
complete_onboarding() {
    local bounds x1 y1 x2 y2
    adb shell uiautomator dump /sdcard/smoke-ui.xml >/dev/null 2>&1 || true
    bounds=$(adb shell cat /sdcard/smoke-ui.xml 2>/dev/null | tr '>' '\n' \
        | grep 'resource-id="[^"]*nextButton"' \
        | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -n 1)
    [ -n "$bounds" ] || return 1
    # Bounds are [x1,y1][x2,y2]; tap the centre of the next/finish button.
    read -r x1 y1 x2 y2 < <(printf '%s' "$bounds" | grep -o '[0-9][0-9]*' | tr '\n' ' ')
    adb shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))
}
for _ in 1 2 3 4 5; do
    complete_onboarding || true
    sleep 1
    adb shell dumpsys activity activities 2>/dev/null | grep "ResumedActivity" | grep -q "OnboardingActivity" || break
done

status=0

# Must have left the first-run screen, or this test exercised nothing.
if adb shell dumpsys activity activities 2>/dev/null | grep "ResumedActivity" | grep -q "OnboardingActivity"; then
    echo "FAIL: still on OnboardingActivity after tapping through; runtime path was never exercised" >&2
    status=1
fi

# Capture the PIDs before the window too: if the app crashes it is reaped (or
# replaced) by the time we read it after, and the error lines carry the old PIDs.
# Keep *all* pids (pidof returns one per process) in case a helper process is
# added later; today the Rust server shares the main process.
pids_before=$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r')

sleep "$SMOKE_SECONDS"

mkdir -p "$(dirname "$LOGCAT_OUT")"
adb logcat -d -v threadtime > "$LOGCAT_OUT"

# Process must still be alive (a startup crash kills it).
pids_after=$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r')
if [ -z "$pids_after" ]; then
    echo "FAIL: $PKG is not running after ${SMOKE_SECONDS}s" >&2
    status=1
fi

# Lines any app process emitted (before/after PIDs), or lines naming the package.
# The PID scope catches caught errors logged under app-specific tags (e.g. a
# watcher logging a native-init failure) that a tag-name match would drop.
app_log=$(awk -v before="${pids_before:-}" -v after="${pids_after:-}" -v pkg="$PKG" '
    BEGIN { n = split(before " " after, a, " "); for (i = 1; i <= n; i++) if (a[i] != "") p[a[i]] = 1 }
    ($3 in p) || index($0, pkg) { print }' "$LOGCAT_OUT")

# Resolution errors R8 can introduce, anywhere in the app process's output.
if printf '%s\n' "$app_log" \
    | grep -E "ClassNotFoundException|NoClassDefFoundError|NoSuchMethodError|NoSuchFieldError|UnsatisfiedLinkError|AbstractMethodError|ExceptionInInitializerError"; then
    echo "FAIL: R8-style resolution errors in logcat (see $LOGCAT_OUT)" >&2
    status=1
fi
if printf '%s\n' "$app_log" | grep -q "FATAL EXCEPTION"; then
    echo "FAIL: fatal exception in $PKG" >&2
    status=1
fi

[ "$status" -eq 0 ] && echo "OK: minified release variant launched cleanly"
exit "$status"
