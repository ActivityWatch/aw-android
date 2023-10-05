aw-android
==========

[![GitHub Actions badge](https://github.com/ActivityWatch/aw-android/workflows/Build/badge.svg)](https://github.com/ActivityWatch/aw-android/actions)

A very work-in-progress ActivityWatch app for Android.

Available on Google Play:

<a title="Get it on Google Play" href="https://play.google.com/store/apps/details?id=net.activitywatch.android">
    <img src="https://play.google.com/intl/en_us/badges/images/generic/en_badge_web_generic.png" width="240px"/>
</a>


## Usage

Install the APK from the Play Store or from the [GitHub releases](https://github.com/ActivityWatch/aw-android/releases).

### For Oculus Quest

> **Note** 
> At some point a Quest system upgrade broke the ability to allow ActivityWatch access to usage stats. This can be fixed by manually assigning the needed permission using adb: `adb shell appops set net.activitywatch.android android:get_usage_stats allow`

It's available [on SideQuest](https://sidequestvr.com/#/app/201). 


## Building

To build this app you first need to build aw-server-rust (`./aw-server-rust`) and aw-webui (`./aw-server-rust/aw-webui`).

If you haven't already, initialize the submodules with: `git submodule update --init --recursive`

### Building aw-server-rust

> **Note**
> If you don't want to go through the hassle of getting Rust up and running, you can download the jniLibs from [aw-server-rust CI artifacts](https://github.com/ActivityWatch/aw-server-rust/actions/workflows/build.yml) and place them in `mobile/src/main/jniLibs` manually instead of following this section.

To build aw-server-rust you need to have Rust nightly installed (with rustup). Then you can build it with:

```
export ANDROID_NDK_HOME=`pwd`/aw-server-rust/NDK  # The path to your NDK
pushd aw-server-rust && ./install-ndk.sh; popd    # This configures the NDK for use with Rust, and installs the NDK if missing
env RELEASE=false make aw-server-rust             # Set RELEASE=true to build in release mode (slower build, harder to debug)
```

> **Note**
> The Android NDK will be downloaded by `install-ndk.sh` to `aw-server-rust/NDK` if `ANDROID_NDK_HOME` not set. You can create a symlink pointing to the real location if you already have it elsewhere (such as /opt/android-ndk/ on Arch Linux).

### Building aw-webui

To build aw-webui you need a recent version of node/npm installed. You can then build it with `make aw-webui`.

### Putting it all together

Once both aw-server-rust and aw-webui is built, you can build the Android app as any other Android app using Android Studio.

## More info

For more info, check out the main [ActivityWatch repo](https://github.com/ActivityWatch/activitywatch).
