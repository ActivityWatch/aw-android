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

internal fun deviceHostname(context: Context): String {
    val named = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    return sanitizeDeviceHostname(named ?: android.os.Build.DEVICE)
}
