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
# (from `apksigner verify --print-certs`, lowercase hex without colons). When
# set, the script verifies the signed APK *and* AAB signer cert matches —
# fails loudly on mismatch. keytool fingerprints (colon-separated uppercase)
# are normalized to the same form.

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

# apksigner prints lowercase hex without colons; keytool prints uppercase
# colon-separated SHA256. Normalize both (and the pin) before comparing.
_normalize_sha256() {
    printf '%s' "$1" | tr -d ' :\n' | tr 'A-F' 'a-f'
}

# Fail closed when ANDROID_CERT_SHA256 is set and the signed artifact's
# signer cert does not match. APKs use apksigner; AABs use keytool because
# apksigner does not support app bundles.
_verify_pinned_cert() {
    local file=$1
    local actual expected
    if [ -z "${ANDROID_CERT_SHA256:-}" ]; then
        return 0
    fi
    if [[ $file == *.apk ]]; then
        actual=$($apksigner verify --print-certs "$file" \
            | grep "Signer #1 certificate SHA-256 digest:" \
            | awk '{print $NF}')
    else
        actual=$(keytool -printcert -jarfile "$file" \
            | sed -n 's/^[[:space:]]*SHA256:[[:space:]]*//p' \
            | head -n 1)
    fi
    actual=$(_normalize_sha256 "$actual")
    expected=$(_normalize_sha256 "$ANDROID_CERT_SHA256")
    if [ -z "$actual" ]; then
        echo "ERROR: Could not extract signer certificate SHA-256 from $file"
        exit 1
    fi
    if [ "$actual" != "$expected" ]; then
        echo "ERROR: Signer certificate SHA-256 mismatch — possible key rotation or wrong keystore."
        echo "  expected: $expected"
        echo "  actual:   $actual"
        exit 1
    fi
    echo "Signer certificate verified: $actual"
}

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

    _verify_pinned_cert "$input"
fi
if [[ $input == *.aab ]]; then
    jarsigner -verbose \
        -keystore android.jks \
        -storepass $JKS_STOREPASS -keypass $JKS_KEYPASS \
        $input activitywatch

    # Verify the bundle before it can be uploaded. `-strict` turns signer and
    # certificate problems that jarsigner otherwise reports as warnings into a
    # non-zero exit status.
    jarsigner -verify -strict "$input"

    _verify_pinned_cert "$input"
fi

# Move to output destination
mv $input $output
