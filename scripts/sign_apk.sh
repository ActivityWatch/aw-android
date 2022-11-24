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

# Sign
jarsigner -verbose -sigalg SHA1withRSA -digestalg SHA1 \
    -storepass $JKS_STOREPASS -keypass $JKS_KEYPASS \
    -keystore android.jks $input activitywatch
jarsigner -verify $input
mv $input $output

zipalign=$(find $ANDROID_HOME/build-tools -name "zipalign" -print | head -n 1)
$zipalign -v 4 $output $output.new
mv $output.new $output
