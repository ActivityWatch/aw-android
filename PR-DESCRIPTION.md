## Add configurable network access setting

I needed to access the ActivityWatch API from other devices on my network (via Tailscale) to sync data to a central server, so I figured I'd contribute a proper implementation rather than just hardcoding `0.0.0.0`.

### What this does

Adds a "Allow network access" toggle in a new native Settings screen, accessible from the action bar settings button (which previously showed a "not yet implemented" snackbar).

- **Off by default** — server binds to `127.0.0.1` (unchanged behavior)
- **When enabled** — server binds to `0.0.0.0`, allowing connections from other devices on the local network
- Enabling shows a **security warning dialog** explaining that the API has no authentication and activity data will be exposed to the network
- Displays a toast noting the change takes effect on app restart

### Changes

**aw-android:**
- `AWPreferences.kt` — new `isNetworkAccessEnabled` / `setNetworkAccessEnabled` preference
- `RustInterface.kt` — `startServer()` and `startServerTask()` now accept a `host` parameter
- `MainActivity.kt` — reads the preference and passes the host to the server; wires the Settings action bar button to the new `SettingsActivity`
- `SettingsActivity.kt` (new) — simple settings screen with the network access toggle + confirmation dialog
- `activity_settings.xml` (new) — layout for the settings screen
- `strings.xml` — added setting labels and warning text
- `AndroidManifest.xml` — registered `SettingsActivity`

**aw-server-rust** (submodule, [see commit](https://github.com/lucletoffe/aw-server-rust/commit/7a5c14841ee7b33b29576d4b2cc2003cacd52ac2)):
- `android/mod.rs` — `startServer` JNI function now accepts a `host` string parameter and passes it to `AWConfig` instead of relying on the default

### Security considerations

The toggle is opt-in with a clear warning. The dialog text mentions:
- No authentication on the API
- Anyone on the same network can read/modify data
- Recommendation to only enable on trusted networks (e.g. VPN)

### Screenshots

_Not available yet — will add after building the APK._

Closes #121, closes #107
