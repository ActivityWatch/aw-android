package net.activitywatch.android.watcher

import android.Manifest
import android.app.AlertDialog
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.*
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.work.*
import net.activitywatch.android.RustInterface
import org.json.JSONObject
import org.threeten.bp.DateTimeUtils
import org.threeten.bp.Duration
import org.threeten.bp.Instant
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.concurrent.TimeUnit

const val bucket_id = "aw-watcher-android-plus"
const val unlock_bucket_id = "aw-watcher-android-plus-unlock"

class UsageStatsWatcher constructor(val context: Context) {
    private val ri = RustInterface(context)
    private val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")

    var lastUpdated: Instant? = null


    enum class PermissionStatus {
        GRANTED, DENIED, CANNOT_BE_GRANTED
    }

    companion object {
        const val TAG = "UsageStatsWatcher"

        fun isUsageAllowed(context: Context): Boolean {
            // https://stackoverflow.com/questions/27215013/check-if-my-application-has-usage-access-enabled
            val applicationInfo: ApplicationInfo = try {
                context.packageManager.getApplicationInfo(context.packageName, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                Log.e(TAG, e.toString())
                return false
            }

            return getUsageStatsPermissionsStatus(context)
        }

        fun isAccessibilityAllowed(context: Context): Boolean {
            return getAccessibilityPermissionStatus(context)
        }

        private fun getUsageStatsPermissionsStatus(context: Context): Boolean {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
           return if (mode == AppOpsManager.MODE_DEFAULT) context.checkCallingOrSelfPermission(
                    Manifest.permission.PACKAGE_USAGE_STATS
                ) == PackageManager.PERMISSION_GRANTED else mode == AppOpsManager.MODE_ALLOWED
        }

        private fun getAccessibilityPermissionStatus(context: Context): Boolean {
            // https://stackoverflow.com/a/54839499/4957939
            val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val accessibilityServices = accessibilityManager.getEnabledAccessibilityServiceList(
                AccessibilityEvent.TYPES_ALL_MASK)
            return accessibilityServices.any { it.id.contains(context.packageName) }
        }
    }

    private fun getUSM(): UsageStatsManager? {
        val usageIsAllowed = isUsageAllowed(context)

        return if (usageIsAllowed) {
            // Get UsageStatsManager stuff
            val usm: UsageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            usm
        } else {
            Log.w(TAG, "Was not allowed access to UsageStats, enable in settings.")

            // Unused, deprecated in favor of OnboardingActivity
            /*
            Handler(Looper.getMainLooper()).post {
                // Create an alert dialog to inform the user
                AlertDialog.Builder(context)
                    .setTitle("ActivityWatch needs Usage Access")
                    .setMessage("This gives ActivityWatch access to your device use data, which is required for the basic functions of the application.\n\nWe respect your privacy, no data leaves your device.")
                    .setPositiveButton("Continue") { _, _ ->
                        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    }
                    .setNegativeButton("Cancel") { dialog, _ ->
                        dialog.cancel()
                        System.exit(0)
                    }
                    .show()
            }
             */
            null
        }
    }

    fun setupAlarm() {
        val workRequest = PeriodicWorkRequestBuilder<HeartbeatWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "heartbeat_periodic",
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
        Log.i(TAG, "Periodic heartbeat work scheduled every 15 minutes")
    }


    fun queryUsage() {
        val usm = getUSM() ?: return

        // Print per application
        val usageStats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, 0, Long.MAX_VALUE)
        Log.i(TAG, "usageStats.size=${usageStats.size}")
        for(e in usageStats) {
            Log.i(TAG, "${e.packageName}: ${e.totalTimeInForeground/1000}")
        }
    }

    private fun getLastEvent(): JSONObject? {
        val events = ri.getEventsJSON(bucket_id, limit=1)
        return if (events.length() == 1) {
            //Log.d(TAG, "Last event: ${events[0]}")
            events[0] as JSONObject
        } else {
            Log.w(TAG, "More or less than one event was retrieved when trying to get last event, actual length: ${events.length()}")
            null
        }
    }

    // TODO: Maybe return end of event instead of start?
    private fun getLastEventTime(): Instant? {
        val lastEvent = getLastEvent()
        Log.w(TAG, "Last event: $lastEvent")

        return if(lastEvent != null) {
            val timestampString = lastEvent.getString("timestamp")
            // Instant.parse("2014-10-23T00:35:14.800Z").toEpochMilli()
            try {
                val timeCreatedDate = isoFormatter.parse(timestampString)
                DateTimeUtils.toInstant(timeCreatedDate)
            } catch (e: ParseException) {
                Log.e(TAG, "Unable to parse timestamp: $timestampString")
                null
            }
        } else {
            null
        }
    }

    fun sendHeartbeats() {
        Log.w(TAG, "Enqueueing one-time heartbeat work")
        val workRequest = OneTimeWorkRequestBuilder<HeartbeatWorker>()
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            HeartbeatWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

}
