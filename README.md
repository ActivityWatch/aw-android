aw-android
==========

A very work-in-progress ActivityWatch app for Android.

Available on Google Play:

<a title="Get it on Google Play" href="https://play.google.com/store/apps/details?id=net.activitywatch.android">
    <img src="https://play.google.com/intl/en_us/badges/images/generic/en_badge_web_generic.png" width="240px"/>
</a>


## Usage

Due to the massive disclaimer we put up to prevent people from getting disappointed when things break or just don't work great, you first need to navigate to the Web UI in the side menu. We hope to change this soon, when things don't suck as much.


### For Oculus Quest

It's available [on SideQuest](https://sidequestvr.com/#/app/201). 

**Note:** you might need to install AppStarter to find it the app menus. If this is the case, open an issue and include the steps for what you had to do.


## Building

To build this app you first need to build aw-server-rust and aw-webui (which is placed in `aw-server-rust/aw-webui`).

If you haven't already, initialize the submodules with: `git submodule update --init --recursive`

### Building aw-server-rust

To build aw-server-rust you need to have Rust nightly installed (with rustup). Then you could try to build it with:

```
export ANDROID_NDK_HOME=`pwd`/aw-server-rust/NDK
pushd aw-server-rust && ./install-ndk.sh; popd  # This configures the NDK for use with Rust, and installs the NDK if missing
env RELEASE=false make aw-server-rust  # Set RELEASE=true to build in release mode (slower build, harder to debug)
```

Note: The Android NDK will be downloaded by the `install-ndk.sh` script if missing. The location of the Android NDK *must* be `aw-server-rust/NDK`. You can create a symlink pointing to the real location if you already have it elsewhere (such as /opt/android-ndk/ on Arch Linux).

### Building aw-webui

To build aw-webui you need a recent version of node/npm installed. You can then build it with `make aw-webui`.

### Putting it all together

Once both aw-server-rust and aw-webui is built, you can build the Android app as any other Android app using Android Studio.

## More info

For more info, check out the main [ActivityWatch repo](https://github.com/ActivityWatch/activitywatch).
