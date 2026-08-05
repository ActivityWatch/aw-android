#!/bin/bash

# Signs APKs or AABs using the android.jks keystore
#
# This signing strategy (zipalign, then apksigner for APKs and jarsigner for
# AABs, passwords via env vars) is intentionally shared with gptme's Android
# release signing (gptme/gptme .github/workflows/tauri.yml release-android job,
# documented in docs/contributing.rst "Android release signing" there).
# Keep the two implementations consistent when changing either.
#
# Optional: set ANDROID_CERT_SHA256 to the expected signer cert SHA-256 digest
# (from `apksigner verify --print-certs`). When set, the script verifies the
# APK's actual signer cert matches — fails loudly on mismatch.

set -e

input=$1
output=$2
echo 'Signing:'
echo $input '->' $output

if [ -z $ANDROID_HOME ]; then
    echo '$ANDROID_HOME needs to be set'
    exit 1
fi

if [ -z $JKS_STOREPASS ]; then
    echo '$JKS_STOREPASS needs to be set'
    exit 1
fi
if [ -z $JKS_KEYPASS ]; then
    echo '$JKS_KEYPASS needs to be set'
    exit 1
fi

# Zipalign
# Not needed for AABs
if [[ $input == *.apk ]]; then
    zipalign=$(find $ANDROID_HOME/build-tools -name "zipalign" -print | head -n 1)
    $zipalign -v -p 4 $input $input.new
    mv $input.new $input
fi

# Sign
# Using apksigner for APKs instead of jarsigner since API 30+: https://stackoverflow.com/a/69473649
# Using jarsigner for AABs since apksigner doesn't support them
if [[ $input == *.apk ]]; then
    apksigner=$(find $ANDROID_HOME/build-tools -name "apksigner" -print | sort -V | tail -n 1)
    $apksigner sign --ks android.jks --ks-key-alias activitywatch \
        --ks-pass env:JKS_STOREPASS --key-pass env:JKS_KEYPASS \
        $input

    # Verify signature integrity
    $apksigner verify $input

    # Verify signer cert SHA-256 if pinned (set ANDROID_CERT_SHA256 to pin)
    if [ -n "${ANDROID_CERT_SHA256:-}" ]; then
        actual=$($apksigner verify --print-certs "$input" \
            | grep "Signer #1 certificate SHA-256 digest:" \
            | awk '{print $NF}')
        if [ -z "$actual" ]; then
            echo "ERROR: Could not extract signer certificate SHA-256 from $input"
            exit 1
        fi
        if [ "$actual" != "$ANDROID_CERT_SHA256" ]; then
            echo "ERROR: Signer certificate SHA-256 mismatch — possible key rotation or wrong keystore."
            echo "  expected: $ANDROID_CERT_SHA256"
            echo "  actual:   $actual"
            exit 1
        fi
        echo "Signer certificate verified: $actual"
    fi
fi
if [[ $input == *.aab ]]; then
    jarsigner -verbose \
        -keystore android.jks \
        -storepass $JKS_STOREPASS -keypass $JKS_KEYPASS \
        $input activitywatch
fi

# Move to output destination
mv $input $output
