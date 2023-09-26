#!/bin/bash

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
zipalign=$(find $ANDROID_HOME/build-tools -name "zipalign" -print | head -n 1)
$zipalign -v -p 4 $input $input.new
mv $input.new $input

# Sign
# Using apksigner instead of jarsigner since API 30+: https://stackoverflow.com/a/69473649
apksigner=$(find $ANDROID_HOME/build-tools -name "apksigner" -print | head -n 1)
$apksigner sign --ks android.jks --ks-key-alias activitywatch \
    --ks-pass env:JKS_STOREPASS --key-pass env:JKS_KEYPASS \
    $input

# Verify
$apksigner verify $input

# Move to output destination
mv $input $output
