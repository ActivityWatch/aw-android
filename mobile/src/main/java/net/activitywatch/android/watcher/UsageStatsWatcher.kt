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
        val usm = getUSM()!!

        // Print per application
        val usageStats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, 0, Long.MAX_VALUE)
        Log.i(TAG, "usageStats.size=${usageStats.size}")
        for(e in usageStats) {
            Log.i(TAG, "${e.packageName}: ${e.totalTimeInForeground/1000}")
        }
    }

    private fun getLastEvent(): JSONObject? {
        // FIXME: For some reason doesn't return last event, always 2h behind (so probably a timezone issue)
        val events = ri.getEventsJSON(bucket_id, limit=1)
        return if (events.length() > 0) {
            //Log.d(TAG, events[0].toString())
            events[0] as JSONObject
        } else {
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

    private inner class SendHeartbeatsTask : AsyncTask<URL, Instant, Int>() {
        override fun doInBackground(vararg urls: URL): Int? {
            Log.i(TAG, "Sending heartbeats...")

            // TODO: Use other bucket type when support for such a type has been implemented in aw-webui
            ri.createBucketHelper(bucket_id, "currentwindow")
            lastUpdated = getLastEventTime()
            Log.w(TAG, "lastUpdated: ${lastUpdated.toString()}")

            var heartbeatsSent = 0
            val usm = getUSM()!!
            val usageEvents = usm.queryEvents(lastUpdated?.toEpochMilli() ?: 0L, Long.MAX_VALUE)
            nextEvent@ while(usageEvents.hasNextEvent()) {
                val event = UsageEvents.Event()
                usageEvents.getNextEvent(event)

                val awEvent = Event.fromUsageEvent(event, context)
                val pulsetime: Double = when(event.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                        // MOVE_TO_FOREGROUND: New Activity was opened
                        0.0
                    }
                    UsageEvents.Event.SCREEN_INTERACTIVE -> {
                        // SCREEN_INTERACTIVE: Screen just became interactive, user was previously therefore not active on the device
                        0.0
                    }
                    UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                        // MOVE_TO_BACKGROUND: Activity was moved to background
                        24 * 60 * 60.0   // 24h, we will assume events should never grow longer than that
                    }
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                        // SCREEN_NOT_INTERACTIVE: Screen locked/turned off, user is therefore now AFK, and this is the last event
                        24 * 60 * 60.0   // 24h, we will assume events should never grow longer than that
                    }
                    else -> {
                        // Not sure which events are triggered here, so we use a (probably safe) fallback
                        Log.w(TAG, "Rare eventType: ${event.eventType}, skipping")
                        continue@nextEvent
                    }
                }

                sleep(1)  // might fix crashes on some phones, idk, suspecting a race condition but no proper testing done
                ri.heartbeatHelper(bucket_id, awEvent.timestamp, awEvent.duration, awEvent.data, pulsetime)
                publishProgress(awEvent.timestamp)
                heartbeatsSent++
            }
            return heartbeatsSent
        }

        override fun onProgressUpdate(vararg progress: Instant) {
            lastUpdated = progress[0]
            Log.i(TAG, "Progress: ${lastUpdated.toString()}")
        }

        override fun onPostExecute(result: Int?) {
            Log.w(TAG, "Finished SendHeartbeatTask, sent $result events")
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