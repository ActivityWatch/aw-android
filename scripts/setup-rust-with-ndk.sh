#!/bin/bash

# Based on https://mozilla.github.io/firefox-browser-architecture/experiments/2017-09-21-rust-on-android.html

set -e

script_dir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
project_path="$(readlink -f "$script_dir/..")"

if [ -z "$ANDROID_HOME" ]; then
    export ANDROID_HOME=/home/$USER/Android/Sdk
    export ANDROID_NDK_HOME=$ANDROID_HOME/ndk-bundle
fi

# curl https://sh.rustup.rs -sSf | sh

$ANDROID_NDK_HOME/build/tools/make_standalone_toolchain.py --api 28 --arch arm64 --install-dir $ANDROID_NDK_HOME/arm64
$ANDROID_NDK_HOME/build/tools/make_standalone_toolchain.py --api 28 --arch arm --install-dir $ANDROID_NDK_HOME/arm
$ANDROID_NDK_HOME/build/tools/make_standalone_toolchain.py --api 28 --arch x86 --install-dir $ANDROID_NDK_HOME/x86

echo "
[target.aarch64-linux-android]
ar = '$ANDROID_NDK_HOME/arm64/bin/aarch64-linux-android-ar'
linker = '$ANDROID_NDK_HOME/arm64/bin/aarch64-linux-android-clang'

[target.armv7-linux-androideabi]
ar = '$ANDROID_NDK_HOME/arm/bin/arm-linux-androideabi-ar'
linker = '$ANDROID_NDK_HOME/arm/bin/arm-linux-androideabi-clang'

[target.i686-linux-android]
ar = '$ANDROID_NDK_HOME/x86/bin/i686-linux-android-ar'
linker = '$ANDROID_NDK_HOME/x86/bin/i686-linux-android-clang'
" > aw-server-rust/.cargo/config

rustup target add aarch64-linux-android armv7-linux-androideabi i686-linux-android

mkdir -p $project_path/mobile/src/main/jniLibs/x86
mkdir -p $project_path/mobile/src/main/jniLibs/arm64
mkdir -p $project_path/mobile/src/main/jniLibs/armeabi

# Some more steps after this is done:
#  - Build aw-server-rust using its compile-android.sh script
#  - Copy/link the built libraries into the mobile/src/main/jniLibs folder
#  - Build and test the app!
