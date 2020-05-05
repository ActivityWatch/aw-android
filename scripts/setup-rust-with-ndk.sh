#!/bin/bash

# Based on https://mozilla.github.io/firefox-browser-architecture/experiments/2017-09-21-rust-on-android.html
# TODO: Merge with aw-server-rust/install-ndk.sh

set -e

script_dir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
project_path="$(readlink -f "$script_dir/..")"

if [ -z "$ANDROID_NDK_HOME" ]; then
    echo "ANDROID_NDK_HOME not set, exiting."
    exit 1
fi

# Runs non-Android specific setup tasks
# - Creates some symlinks in the ANDROID_NDK_HOME toolchains to work around ring weirdness
# - Creates the cargo config
# - Retrieves target dependencies with rustup
$project_path/aw-server-rust/install-ndk.sh

# Create destination folders for built libraries
mkdir -p $project_path/mobile/src/main/jniLibs/x86
mkdir -p $project_path/mobile/src/main/jniLibs/x86_64
mkdir -p $project_path/mobile/src/main/jniLibs/arm64
mkdir -p $project_path/mobile/src/main/jniLibs/armeabi

# Some more steps after this is done:
#  - Build aw-server-rust using its compile-android.sh script
#  - Copy/link the built libraries into the mobile/src/main/jniLibs folders
#  - Build and test the app!
