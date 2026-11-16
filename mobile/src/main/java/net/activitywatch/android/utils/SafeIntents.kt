package net.activitywatch.android.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes

private const val TAG = "SafeIntents"

/**
 * Starts [intent], trying each of [fallbacks] in order when no installed app
 * handles it. Returns false instead of throwing [ActivityNotFoundException]
 * when nothing does: devices without a browser (de-Googled ROMs, kiosk and
 * work profiles) and OEM/TV/Go builds that lack some Settings screens
 * (ActivityWatch/activitywatch#1180).
 */
fun Context.startActivitySafely(intent: Intent, vararg fallbacks: Intent): Boolean {
    for (candidate in listOf(intent, *fallbacks)) {
        try {
            startActivity(candidate)
            return true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No activity handles ${candidate.action} ${candidate.data}", e)
        }
    }
    return false
}

/** [startActivitySafely], showing [failureMessage] when no candidate could be opened. */
fun Context.startActivityOrToast(
    intent: Intent,
    @StringRes failureMessage: Int,
    vararg fallbacks: Intent,
): Boolean {
    val started = startActivitySafely(intent, *fallbacks)
    if (!started) {
        Toast.makeText(this, failureMessage, Toast.LENGTH_LONG).show()
    }
    return started
}

/** The system Settings home: a usable last resort when a specific screen is missing. */
fun settingsHomeIntent(): Intent = Intent(Settings.ACTION_SETTINGS)

/** This app's details page in system Settings. */
fun Context.appDetailsSettingsIntent(): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
