#!/bin/bash

# Signs an APK with the Android debug keystore.
#
# scripts/sign_apk.sh covers release signing (android.jks + CI secrets). This is
# its no-secrets counterpart, used by `make build-apk-per-abi` for debug builds:
# Gradle already signed the debug APK with the debug keystore, and dropping the
# other ABIs' lib/ entries rewrites the archive, which voids that signature. An
# APK whose signature no longer verifies cannot be installed, so it has to be
# re-signed with the same debug key (aw-android#308 review).
#
# Keystore: $DEBUG_KEYSTORE, else ~/.android/debug.keystore — AGP's default, so
# a preceding `./gradlew assembleStandardDebug` has already created it with the
# androiddebugkey/android credentials used below.

set -e

input=$1
output=$2
echo 'Signing (debug):'
echo "$input" '->' "$output"

if [ -z "$ANDROID_HOME" ]; then
    echo "\$ANDROID_HOME needs to be set" >&2
    exit 1
fi

keystore=${DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}
if [ ! -f "$keystore" ]; then
    echo "No debug keystore at $keystore." >&2
    echo "Build the debug APK first (Gradle creates it), or set \$DEBUG_KEYSTORE." >&2
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

rm -f "$input.idsig"

# Move to output destination
mv "$input" "$output"
