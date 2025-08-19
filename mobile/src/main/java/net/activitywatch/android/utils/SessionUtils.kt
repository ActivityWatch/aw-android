package net.activitywatch.android.utils

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

object SessionUtils {

    /**
     * Check if the app has usage stats permission
     */
    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOpsManager.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Open usage access settings
     */
    fun openUsageAccessSettings(context: Context) {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        context.startActivity(intent)
    }

    /**
     * Format duration in milliseconds to human readable string
     */
    fun formatDuration(durationMs: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(durationMs)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMs) % 60

        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    /**
     * Format duration for short display (e.g., in charts)
     */
    fun formatDurationShort(durationMs: Long): String {
        val hours = durationMs / 3600000.0
        val minutes = durationMs / 60000.0

        return when {
            hours >= 1 -> "${String.format("%.1f", hours)}h"
            minutes >= 1 -> "${minutes.roundToInt()}m"
            else -> "${(durationMs / 1000.0).roundToInt()}s"
        }
    }

    /**
     * Format timestamp to readable date
     */
    fun formatDate(timestamp: Long): String {
        val format = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
        return format.format(Date(timestamp))
    }

    /**
     * Format timestamp to time
     */
    fun formatTime(timestamp: Long): String {
        val format = SimpleDateFormat("HH:mm", Locale.getDefault())
        return format.format(Date(timestamp))
    }

    /**
     * Format timestamp to date and time
     */
    fun formatDateTime(timestamp: Long): String {
        val format = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
        return format.format(Date(timestamp))
    }

    /**
     * Get start of day timestamp
     */
    fun getStartOfDay(timestamp: Long = System.currentTimeMillis()): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestamp
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    /**
     * Get end of day timestamp
     */
    fun getEndOfDay(timestamp: Long = System.currentTimeMillis()): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestamp
        calendar.set(Calendar.HOUR_OF_DAY, 23)
        calendar.set(Calendar.MINUTE, 59)
        calendar.set(Calendar.SECOND, 59)
        calendar.set(Calendar.MILLISECOND, 999)
        return calendar.timeInMillis
    }

    /**
     * Get start of day timestamp for X days ago
     */
    fun getStartOfDayDaysAgo(daysAgo: Int): Long {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, -daysAgo)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    /**
     * Convert hours to a readable percentage of day
     */
    fun formatScreenTimePercentage(hours: Double): String {
        val percentage = (hours / 24.0 * 100).roundToInt()
        return "$percentage%"
    }

    /**
     * Get human-readable app name from package name
     */
    fun getAppName(context: Context, packageName: String): String {
        return try {
            val packageManager = context.packageManager
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            // Fallback to package name if app is not found (uninstalled apps)
            packageName.split(".").lastOrNull() ?: packageName
        }
    }

    /**
     * Check if a duration is reasonable for a session
     */
    fun isReasonableDuration(durationMs: Long): Boolean {
        val minDuration = 1000L // 1 second
        val maxDuration = 4 * 60 * 60 * 1000L // 4 hours
        return durationMs >= minDuration && durationMs <= maxDuration
    }

    /**
     * Check if a timestamp is reasonable (not too far in the future)
     */
    fun isReasonableTimestamp(timestamp: Long): Boolean {
        val currentTime = System.currentTimeMillis()
        val maxFutureTime = currentTime + 60000 // 1 minute in the future
        return timestamp <= maxFutureTime
    }

    /**
     * Calculate session gap between two sessions in milliseconds
     */
    fun calculateSessionGap(session1EndTime: Long, session2StartTime: Long): Long {
        return maxOf(0L, session2StartTime - session1EndTime)
    }

    /**
     * Check if two sessions are from the same app
     */
    fun isSameApp(packageName1: String, packageName2: String): Boolean {
        return packageName1 == packageName2
    }

    /**
     * Get day of week string
     */
    fun getDayOfWeek(timestamp: Long): String {
        val format = SimpleDateFormat("EEEE", Locale.getDefault())
        return format.format(Date(timestamp))
    }

    /**
     * Convert milliseconds to seconds with decimal precision
     */
    fun msToSeconds(ms: Long): Double {
        return ms / 1000.0
    }

    /**
     * Convert milliseconds to minutes with decimal precision
     */
    fun msToMinutes(ms: Long): Double {
        return ms / 60000.0
    }

    /**
     * Convert milliseconds to hours with decimal precision
     */
    fun msToHours(ms: Long): Double {
        return ms / 3600000.0
    }
}
