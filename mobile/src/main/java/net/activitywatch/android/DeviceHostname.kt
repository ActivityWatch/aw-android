package net.activitywatch.android

import android.content.Context
import android.provider.Settings
import java.util.Locale

/**
 * Hostname used for Android buckets, sync, and the Activity webui route.
 *
 * Sentinel `"unknown"` is a valid stored hostname (stopwatch, unsynced rows) but a
 * terrible default: `/#/activity/unknown/` loads desktop visualizations for a host
 * that has no `aw-watcher-android_*` buckets.
 */
internal fun sanitizeDeviceHostname(raw: String?): String {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return "unknown"
    return value
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9_-]+"), "_")
        .trim('_')
        .ifEmpty { "unknown" }
}

internal fun rawDeviceName(context: Context): String? =
    Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

/**
 * Sanitized [deviceName], or the sanitized [buildDevice] when the name is missing or
 * has nothing that survives sanitization (`小米手机`, `Телефон`, `📱`). `"unknown"` only
 * when both are unusable (ActivityWatch/aw-android#402).
 *
 * Devices previously stored as `"unknown"` migrate through the existing one-shot
 * paths: `"unknown"` is always a [SanitizedHostnameMigration.legacyHostnames] candidate.
 */
internal fun resolveDeviceHostname(deviceName: String?, buildDevice: String?): String =
    sanitizeDeviceHostname(deviceName).takeIf { it != "unknown" }
        ?: sanitizeDeviceHostname(buildDevice)

internal fun deviceHostname(context: Context): String =
    resolveDeviceHostname(rawDeviceName(context), android.os.Build.DEVICE)

internal fun legacyDeviceHostnames(context: Context): List<String> =
    SanitizedHostnameMigration.legacyHostnames(
        current = deviceHostname(context),
        deviceName = rawDeviceName(context),
        model = android.os.Build.MODEL,
    )
