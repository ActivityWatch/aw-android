package net.activitywatch.android.watcher

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.AsyncTask
import android.provider.Settings
import android.util.Log
import net.activitywatch.android.RustInterface
import net.activitywatch.android.models.Event
import org.json.JSONObject
import java.text.SimpleDateFormat
import org.threeten.bp.DateTimeUtils
import org.threeten.bp.Instant
import java.lang.Thread.sleep
import java.net.URL
import java.text.ParseException




class UsageStatsWatcher constructor(val context: Context) {
    private val TAG = "UsageStatsWatcher"
    private val bucket_id = "aw-watcher-android-test"

    private val ri = RustInterface(context)
    private val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")

    var lastUpdated: Instant? = null

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
        }
    }

    private fun getLastEvent(): JSONObject? {
        val events = ri.getEventsJSON(bucket_id, limit=1)
        return if (events.length() > 0) {
            events[0] as JSONObject
        } else {
            null
        }
    }

    private fun getNewEvents(limit: Int = 100): List<UsageEvents.Event> {
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

        Log.d(TAG, "Since: $since")

        val newUsageEvents = mutableListOf<UsageEvents.Event>()
        if (usm != null) {
            val usageEvents = usm.queryEvents(since, Long.MAX_VALUE)
            while(usageEvents.hasNextEvent() && newUsageEvents.size < limit) {
                val eventOut = UsageEvents.Event()
                usageEvents.getNextEvent(eventOut)
                newUsageEvents.add(eventOut)
            }
        }
        return newUsageEvents
    }

    private inner class SendHeartbeatsTask : AsyncTask<URL, Instant, Int>() {
        override fun doInBackground(vararg urls: URL): Int? {
            Log.i(TAG, "Starting to send heartbeats...")
            // Ensure bucket exists
            // TODO: Use other bucket type when support for such a type has been implemented in aw-webui
            ri.createBucketHelper(bucket_id, "currentwindow")

            for(e in getNewEvents(limit=1000)) {
                val awEvent = Event.fromUsageEvent(e, context)
                //Log.d(TAG, awEvent.toString())
                //Log.w(TAG, "Event type: ${e.eventType}")

                // TODO: Set pulsetime correctly for the different event types
                val pulsetime: Double = if (e.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND || e.eventType == UsageEvents.Event.SCREEN_NON_INTERACTIVE) {
                    // MOVE_TO_BACKGROUND: Activity was moved to background
                    // SCREEN_NOT_INTERACTIVE: Screen locked/turned off, user is therefore now AFK, and this is the last event
                    24 * 60 * 60.0   // 24h, we will assume events should never grow longer than that
                } else if (e.eventType == UsageEvents.Event.SCREEN_INTERACTIVE || e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    // SCREEN_INTERACTIVE: Screen just became interactive, user was previously therefore not active on the device
                    // MOVE_TO_FOREGROUND: New Activity was opened
                    0.0
                } else {
                    // Not sure which events are triggered here, so we use a (probably safe) fallback
                    Log.w(TAG, "Rare eventType: ${e.eventType}, defaulting to pulsetime of 1h")
                    60 * 60.0
                }
                sleep(1)  // might fix crashes on some phones, idk, suspecting a race condition but no proper testing done

                ri.heartbeatHelper(bucket_id, awEvent.timestamp, awEvent.duration, awEvent.data, pulsetime)
                publishProgress(awEvent.timestamp)
            }
            return null
        }

        override fun onProgressUpdate(vararg progress: Instant) {
            lastUpdated = progress[0]
            Log.i(TAG, "Progress: ${lastUpdated.toString()}")
        }

        override fun onPostExecute(result: Int?) {
            Log.w(TAG, "Finished SendHeartbeatTask")
        }
    }

    /***
     * Returns the number of events sent
     */
    fun sendHeartbeats() {
        Log.w(TAG, "Starting SendHeartbeatTask")
        SendHeartbeatsTask().execute()
    }

}