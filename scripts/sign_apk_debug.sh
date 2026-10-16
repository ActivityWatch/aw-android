#!/bin/bash

# Signs an APK with the Android debug keystore.
#
# scripts/sign_apk.sh covers release signing (android.jks + CI secrets). This is
# its no-secrets counterpart, used by `make build-apk-per-abi` for debug builds:
# Gradle already signed the debug APK with the debug keystore, and dropping the
# other ABIs' lib/ entries rewrites the archive, which voids that signature. An
# APK whose signature no longer verifies cannot be installed, so it has to be
# re-signed (aw-android#308 review).
#
# Usage: sign_apk_debug.sh <input.apk> <output.apk> [reference.apk]
#
# The optional reference is the unmodified APK that <input.apk> was derived from.
# When it is given, the re-signed APK must carry the same signer certificate, so
# a keystore Gradle did not sign with fails the build instead of producing a
# per-ABI APK that cannot update-install over the debug build it mirrors.
#
# Keystore resolution mirrors AGP: $DEBUG_KEYSTORE, else
# $ANDROID_USER_HOME/debug.keystore when that variable is set, else
# $HOME/.android/debug.keystore. The file must exist — AGP creates it with the
# androiddebugkey/android credentials used below on the first debug build.

set -e

input=$1
output=$2
reference=${3:-}

echo 'Signing (debug):'
echo "$input" '->' "$output"

if [ -z "$ANDROID_HOME" ]; then
    echo "\$ANDROID_HOME needs to be set" >&2
    exit 1
fi

if [ -n "$DEBUG_KEYSTORE" ]; then
    keystore=$DEBUG_KEYSTORE
elif [ -n "$ANDROID_USER_HOME" ]; then
    keystore=$ANDROID_USER_HOME/debug.keystore
else
    keystore=$HOME/.android/debug.keystore
fi
if [ ! -f "$keystore" ]; then
    echo "No debug keystore at $keystore." >&2
    echo "Build the debug APK first, or point \$DEBUG_KEYSTORE at the keystore Gradle used." >&2
    exit 1
fi

# Zipalign, then sign (same order and flags as scripts/sign_apk.sh).
zipalign=$(find "$ANDROID_HOME/build-tools" -name "zipalign" -print | head -n 1)
apksigner=$(find "$ANDROID_HOME/build-tools" -name "apksigner" -print | head -n 1)

"$zipalign" -v -p 4 "$input" "$input.new"
mv "$input.new" "$input"

"$apksigner" sign --ks "$keystore" --ks-key-alias androiddebugkey \
    --ks-pass pass:android --key-pass pass:android \
    "$input"

# Verify
"$apksigner" verify "$input"

# Identity check: a per-ABI split is only installable alongside the debug build
# it was derived from if it is signed by the same key.
if [ -n "$reference" ]; then
    cert_digest() {
        "$apksigner" verify --print-certs "$1" |
            sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -n 1
    }
    ref_cert=$(cert_digest "$reference")
    out_cert=$(cert_digest "$input")
    if [ -z "$ref_cert" ] || [ "$ref_cert" != "$out_cert" ]; then
        echo "Signer mismatch: '$reference' is signed by '${ref_cert:-?}', the output by '${out_cert:-?}'." >&2
        echo "Set \$DEBUG_KEYSTORE to the keystore Gradle signed the input with." >&2
        exit 1
    fi
fi

rm -f "$input.idsig"

# Move to output destination
mv "$input" "$output"
