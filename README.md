# aw-android (Remote Fork)

A fork of [ActivityWatch/aw-android](https://github.com/ActivityWatch/aw-android) with **remote ActivityWatch server forwarding** support.

---

## Features

This fork changes the data collection from "local storage" to "remote HTTP forwarding". Captured app usage, browser visits, screen-unlock events, etc. are sent directly to a user-configured remote ActivityWatch server via HTTP API.

### Key Features

- **Pure Remote Forwarding** — Data is sent directly to the remote server via HTTP, no local storage
- **Configurable Remote Address** — In-app UI to set the remote server address
- **Dynamic WebUI** — The embedded WebUI automatically shows the remote dashboard after configuration
- **Native Menu** — Toolbar restored; tap the top ☰ button to open the navigation drawer
- **Cleartext HTTP Support** — Allows sending data to HTTP servers (for LAN/internal deployments)

---

## Modified Files

| File | Changes |
|------|---------|
| `RustInterface.kt` | Changed from JNI client to HTTP client; removed local aw-server calls; all operations access remote server via HTTP API |
| `UsageStatsWatcher.kt` | Bucket names changed to `aw-watcher-android-test2` and `aw-watcher-android-unlock2` |
| `MainActivity.kt` | Removed `ri.startServerTask()` (no local server); `baseURL` reads remote address from config; added `showRemoteServerDialog()`; enabled Toolbar + ActionBarDrawerToggle |
| `AWPreferences.kt` | Added `getRemoteServerUrl()` / `setRemoteServerUrl()` for persisting remote address |
| `activity_main_drawer.xml` | Added `nav_remote_server` menu item |
| `app_bar_main.xml` | Enabled Toolbar |
| `activity_main.xml` | Added `android:id` to `app_bar_main` include |
| `network_security_config.xml` | Added `<base-config cleartextTrafficPermitted="true"/>` to allow plaintext HTTP |

---

## Configuration Guide

### 1. Prepare Remote Server

You need a running ActivityWatch server, such as:
- `aw-server-rust` (recommended)
- `aw-server` (Python version)

The server must expose the HTTP API, default port is `5600`.

### 2. Install APK

```bash
./gradlew assembleDebug
adb install -r mobile/build/outputs/apk/debug/mobile-debug.apk
```

### 3. Configure Remote Address

1. Open the app and grant **Usage Access** permission
2. Tap the top **☰** button to open the navigation drawer (top-left of the white Toolbar)
3. Tap **Remote Server**
4. Enter the remote server address, e.g. `http://192.168.1.100:5600`
5. Tap **Save**

> **Note**: Leaving it blank falls back to the local server `http://127.0.0.1:5600`

### 4. Verify Data

After using your phone normally for 1-2 hours, visit in a browser:
```
http://your-server-ip:5600/#/timeline
```

You should see the phone's collected data.

---

## Debugging

Check remote forwarding logs:
```bash
adb logcat -s RustInterface:D
```

You should see:
```
HTTP POST OK: /api/0/buckets/aw-watcher-android-test2/heartbeat?pulsetime=1.0
```

---

## Build Environment

This project was set up from scratch on Windows 11 without Android Studio.

| Component | Path |
|-----------|------|
| JDK 17 | `./jdk-17.0.18+8` |
| Android SDK | `./android-sdk` |
| Output APK | `mobile/build/outputs/apk/debug/mobile-debug.apk` |

Build commands:
```bash
export JAVA_HOME="$(pwd)/jdk-17.0.18+8"
export ANDROID_HOME="$(pwd)/android-sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

./gradlew assembleDebug
```

> **Note**: This project depends on pre-built Rust JNI libraries (`libaw_server.so`), which are included in `jniLibs/`. You do not need to compile Rust/NDK yourself.

---

## Known Limitations

- App usage data from UsageStats is batched hourly (AlarmManager), not real-time
- Chrome browser data is captured in real-time via AccessibilityService; forwarding delay is ~hundreds of milliseconds
- The remote server must expose the standard ActivityWatch API (`aw-server-rust` or `aw-server`)
- Data will be lost when network is disconnected (fire-and-forget, no local cache)

---

## Original Project

https://github.com/ActivityWatch/aw-android
