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
# Env:   ANDROID_HOME (required), SMOKE_SECONDS (default 20), ONBOARDING_SECONDS
#        (default 120), LOGCAT_OUT
#        (default mobile/build/release-smoke-logcat.log)
#        MAPPING (default: R8 mapping.txt under the APK's Gradle build root;
#        must be set explicitly for a prebuilt APK living outside one)

set -eu

ADB_BIN=$(command -v adb)

# Every adb call is bounded: a wedged emulator must fail the job fast with a
# visible step trace, not sit silent until the 30 min step timeout (#313).
adb() { timeout "${ADB_TIMEOUT:-90}" "$ADB_BIN" "$@"; }
step() { echo "[smoke $(date -u +%H:%M:%S)] $*"; }

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

step "install"; adb uninstall "$PKG" >/dev/null 2>&1 || true
adb logcat -c
ADB_TIMEOUT=300 adb install -r "$tmp/aligned.apk"
# Usage access must be granted before launch: without it onboarding's Finish
# button refuses and the app never leaves the first-run screen.
adb shell appops set "$PKG" android:get_usage_stats allow >/dev/null 2>&1 || true
step "launch"; adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1
sleep 5
# PIDs of the first launch: a process that crashes during onboarding is gone by
# the time we read the PIDs after the window, but its log lines carry these.
pids_launch=$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r')

# A fresh install always opens OnboardingActivity, which keeps the process alive
# without starting the server or WebView — so a stripped JNI symbol or
# @JavascriptInterface method would pass unnoticed. Tap Continue/Finish on the
# live UI dump until the first-run screen is gone.
UI_DUMP=${LOGCAT_OUT%.log}-ui.xml
mkdir -p "$(dirname "$UI_DUMP")"
complete_onboarding() {
    local bounds x1 y1 x2 y2
    # If the screen slept or the keyguard returned during a slow install, the
    # dump shows that instead of the app and no button is ever found. One #313
    # run did 17 dumps and 0 taps; the cause was invisible, hence the saved dump.
    adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
    adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
    adb shell uiautomator dump /sdcard/smoke-ui.xml >/dev/null 2>&1 || true
    # Keep the last dump next to the logcat so a failure shows what was on screen.
    adb shell cat /sdcard/smoke-ui.xml 2>/dev/null > "$UI_DUMP" || true
    bounds=$(tr '>' '\n' < "$UI_DUMP" \
        | grep 'resource-id="[^"]*nextButton"' \
        | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -n 1)
    [ -n "$bounds" ] || return 1
    # Bounds are [x1,y1][x2,y2]; tap the centre of the next/finish button.
    read -r x1 y1 x2 y2 < <(printf '%s' "$bounds" | grep -o '[0-9][0-9]*' | tr '\n' ' ')
    adb shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))
}
# CI emulators are slow (first launch plus dexopt can take well over a minute),
# so poll against a deadline rather than a fixed handful of attempts.
#
# Prints "onboarding" or "left" only when dumpsys actually reported a resumed
# activity; a failed/empty dumpsys (wedged adb, mid-transition) prints "unknown"
# so it can never be mistaken for having left onboarding.
onboarding_state() {
    local resumed
    resumed=$(adb shell dumpsys activity activities 2>/dev/null | grep "ResumedActivity") || true
    if [ -z "$resumed" ]; then
        echo unknown
    elif printf '%s\n' "$resumed" | grep -q "OnboardingActivity"; then
        echo onboarding
    else
        echo left
    fi
}

step "onboarding"
onboarding_deadline=$(( $(date +%s) + ${ONBOARDING_SECONDS:-120} ))
while [ "$(date +%s)" -lt "$onboarding_deadline" ]; do
    complete_onboarding || true
    sleep 2
    [ "$(onboarding_state)" = left ] && break
done

status=0

# Must have positively left the first-run screen, or this test exercised nothing.
final_state=$(onboarding_state)
if [ "$final_state" != left ]; then
    echo "FAIL: did not confirm leaving OnboardingActivity after tapping through (state: $final_state); runtime path was never exercised" >&2
    # Name the window the last UI dump saw, so the cause is in the job log.
    echo "  last UI dump ($UI_DUMP): packages=[$(grep -o 'package="[^"]*"' "$UI_DUMP" 2>/dev/null | sort -u | cut -d'"' -f2 | tr '\n' ' ')] nextButton=$(grep -c 'nextButton' "$UI_DUMP" 2>/dev/null || true)" >&2
    status=1
fi

# Capture the PIDs before the window too: if the app crashes it is reaped (or
# replaced) by the time we read it after, and the error lines carry the old PIDs.
# Keep *all* pids (pidof returns one per process) in case a helper process is
# added later; today the Rust server shares the main process.
pids_before=$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r')

step "observing for ${SMOKE_SECONDS}s"
sleep "$SMOKE_SECONDS"

mkdir -p "$(dirname "$LOGCAT_OUT")"
adb logcat -d -v threadtime > "$LOGCAT_OUT"

# Process must still be alive (a startup crash kills it).
pids_after=$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r')
if [ -z "$pids_after" ]; then
    echo "FAIL: $PKG is not running after ${SMOKE_SECONDS}s" >&2
    status=1
fi

# Lines emitted by the app's own PIDs. The PID scope catches caught errors logged
# under app-specific tags (e.g. a watcher logging a native-init failure) and
# excludes system_server/PackageManager noise that merely mentions the package.
app_log=$(awk -v pids="${pids_launch:-} ${pids_before:-} ${pids_after:-}" '
    BEGIN { n = split(pids, a, " "); for (i = 1; i <= n; i++) if (a[i] != "") p[a[i]] = 1 }
    $3 in p { print }' "$LOGCAT_OUT")

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
