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
import org.json.JSONObject
import java.text.SimpleDateFormat
import org.threeten.bp.DateTimeUtils
import org.threeten.bp.Instant
import java.sql.Timestamp
import java.text.ParseException


class UsageStatsWatcher constructor(val context: Context) {
    private val TAG = "UsageStatsWatcher"
    private val bucket_id = "aw-watcher-android-test"

    private val ri = RustInterface(context)
    private val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")

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

    private fun getUSM(): UsageStatsManager? {
        val usageIsAllowed = isUsageAllowed()

        return if (usageIsAllowed) {
            // Get UsageStatsManager stuff
            val usm: UsageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            usm
        } else {
            Log.w(TAG, "Was not allowed access to UsageStats, enable in settings.")
            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            null
        }
    }

    fun queryUsage() {
        val usm = getUSM()

        if(usm != null) {
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
        }
    }

    // TODO: Move to seperate file in ./models/
    data class Event(val timestamp: Instant, val duration: Double = 0.0, val data: JSONObject) {
        override fun toString(): String {
            return """{"timestamp": "$timestamp", "duration": $duration, "data": $data}"""
        }
    }

    private fun createEventFromUsageEvent(uevent: UsageEvents.Event): Event {
        val timestamp = DateTimeUtils.toInstant(java.util.Date(uevent.timeStamp))
        val pm = context.packageManager
        val appName = try {
            pm.getApplicationLabel(pm.getApplicationInfo(uevent.packageName, PackageManager.GET_META_DATA))
        } catch(e: PackageManager.NameNotFoundException) {
            "Unknown"
        }
        return Event(timestamp = timestamp, duration = 0.0, data = JSONObject("""{"app": "$appName", "package": "${uevent.packageName}", "classname": "${uevent.className}"}"""))
    }

    private fun getLastEvent(): JSONObject? {
        val events = ri.getEventsJSON(bucket_id, limit=1)
        return if (events.length() > 0) {
            events[0] as JSONObject
        } else {
            null
        }
    }

    private fun getNewEvents(): List<UsageEvents.Event> {
        val usm = getUSM()

        // TODO: Get end time of last heartbeat
        val lastEvent = getLastEvent()
        Log.w(TAG, "Last event: $lastEvent")

        val since: Long = if(lastEvent != null) {
            val timestampString = lastEvent.getString("timestamp")
            // Instant.parse("2014-10-23T00:35:14.800Z").toEpochMilli()
            try {
                val timeCreatedDate = isoFormatter.parse(timestampString)
                DateTimeUtils.toInstant(timeCreatedDate).toEpochMilli()
            } catch (e: ParseException) {
                Log.e(TAG, "Unable to parse timestamp")
                0L
            }
        } else {
            0L
        }

        if(since == 0L) {
            Log.e(TAG, "Since was 0, this should only happen on a fresh install")
        }

        Log.w(TAG, "Since: $since")

        val newUsageEvents = mutableListOf<UsageEvents.Event>()
        if (usm != null) {
            val usageEvents = usm.queryEvents(since, Long.MAX_VALUE)
            while(usageEvents.hasNextEvent()) {
                val eventOut = UsageEvents.Event()
                usageEvents.getNextEvent(eventOut)
                newUsageEvents.add(eventOut)
            }
        }
        return newUsageEvents
    }

    fun sendHeartbeats() {
        // Ensure bucket exists
        ri.createBucketHelper(bucket_id, "test")

        for(e in getNewEvents()) {
            val awEvent = createEventFromUsageEvent(e)
            Log.i(TAG, awEvent.toString())
            // TODO: Use correct pulsetime, with long pulsetime if event was of some types (such as application close)
            ri.heartbeatHelper(bucket_id, awEvent.timestamp, awEvent.duration, awEvent.data)
        }
    }

}