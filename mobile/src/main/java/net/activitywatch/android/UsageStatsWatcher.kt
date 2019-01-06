package net.activitywatch.android

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

class UsageStatsWatcher constructor(val context: Context) {
    val TAG = "UsageStatsWatcher"

    private fun isUsageAllowed(): Boolean {
        // https://stackoverflow.com/questions/27215013/check-if-my-application-has-usage-access-enabled
        val applicationInfo: ApplicationInfo = try {
            context.packageManager.getApplicationInfo(context.packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, e.toString())
            return false
        }

        val appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOpsManager.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            applicationInfo.uid,
            applicationInfo.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun queryUsage() {
        val usageIsAllowed = isUsageAllowed()

        if (usageIsAllowed) {
            // Get UsageStatsManager stuff
            val usm: UsageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

            // Print per application
            val usageStats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, 0, Long.MAX_VALUE)
            Log.i(TAG, "usageStats.size=${usageStats.size}")
            for(e in usageStats) {
                Log.i(TAG, "${e.packageName}: ${e.totalTimeInForeground/1000}")
            }

            // Print each event
            val usageEvents = usm.queryEvents(0, Long.MAX_VALUE)
            val eventOut = UsageEvents.Event()
            while(usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(eventOut)
                Log.i(TAG, "timestamp=${eventOut.timeStamp}, ${eventOut.eventType}, ${eventOut.className}")
            }
        } else {
            Log.w(TAG, "Was not allowed access to UsageStats, enable in settings.")
            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }

}