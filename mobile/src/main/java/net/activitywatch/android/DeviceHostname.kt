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

internal fun deviceHostname(context: Context): String =
    sanitizeDeviceHostname(rawDeviceName(context) ?: android.os.Build.DEVICE)

internal fun legacyDeviceHostnames(context: Context): List<String> =
    SanitizedHostnameMigration.legacyHostnames(
        current = deviceHostname(context),
        deviceName = rawDeviceName(context),
        model = android.os.Build.MODEL,
    )
