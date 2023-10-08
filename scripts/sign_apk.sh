#!/bin/bash

# Signs APKs or AABs using the android.jks keystore

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
    apksigner=$(find $ANDROID_HOME/build-tools -name "apksigner" -print | head -n 1)
    $apksigner sign --ks android.jks --ks-key-alias activitywatch \
        --ks-pass env:JKS_STOREPASS --key-pass env:JKS_KEYPASS \
        $input

    # Verify
    $apksigner verify $input
fi
if [[ $input == *.aab ]]; then
    jarsigner -verbose \
        -keystore android.jks activitywatch \
        -storepass $JKS_STOREPASS -keypass $JKS_KEYPASS \
        $input activitywatch
fi

# Move to output destination
mv $input $output
