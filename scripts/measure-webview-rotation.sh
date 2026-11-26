#!/usr/bin/env bash
# Diagnostic only. Reuse the already installed API 29 test APK for each round.
set -euo pipefail

log_dir=mobile/build/webview-rotation
test_class=net.activitywatch.android.NativeWindowInsetsTest
test_method=rotatingMainActivityKeepsTheSameWebView
runner=net.activitywatch.android.debug.test/androidx.test.runner.AndroidJUnitRunner
mkdir -p "$log_dir"

printf 'attempt\tresult\tseconds\n' | tee "$log_dir/results.tsv"
failures=0
for attempt in {1..20}; do
    adb logcat -c
    start=$SECONDS
    instrumentation_status=0
    timeout 45 adb shell am instrument -w -e class "$test_class#$test_method" "$runner" \
        >"$log_dir/$attempt.instrumentation.txt" 2>&1 || instrumentation_status=$?
    adb logcat -d >"$log_dir/$attempt.logcat.txt"

    if grep -Eq 'Fatal signal 6 \(SIGABRT\).*Chrome_InProcGp' "$log_dir/$attempt.logcat.txt"; then
        result=gpu_abort
    elif [[ $instrumentation_status -eq 0 ]] &&
        grep -Fq 'OK (1 test)' "$log_dir/$attempt.instrumentation.txt"; then
        result=pass
    else
        result=instrumentation_failure
    fi
    if [[ $result != pass ]]; then
        failures=$((failures + 1))
    fi
    printf '%d\t%s\t%d\n' "$attempt" "$result" "$((SECONDS - start))" | tee -a "$log_dir/results.tsv"
done
if ((failures > 0)); then
    exit 1
fi
