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
import java.net.URL
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

    private inner class SendHeartbeatsTask : AsyncTask<URL, Pair<Int, Instant>, Int>() {
        override fun doInBackground(vararg urls: URL): Int? {
            Log.i(TAG, "Starting to send heartbeats...")
            // Ensure bucket exists
            // TODO: Use other bucket type when support for such a type has been implemented in aw-webui
            ri.createBucketHelper(bucket_id, "currentwindow")

            var eventsSent = 0
            for(e in getNewEvents()) {
                val awEvent = Event.fromUsageEvent(e, context)
                Log.w(TAG, awEvent.toString())
                Log.w(TAG, "Event type: ${e.eventType}")

                // TODO: Set pulsetime correctly for the different event types
                val pulsetime: Double = if (e.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                    10e6
                } else {
                    60.0
                }

                ri.heartbeatHelper(bucket_id, awEvent.timestamp, awEvent.duration, awEvent.data, pulsetime)
                publishProgress(Pair(eventsSent, awEvent.timestamp))
                if(eventsSent >= 1000) {
                    break
                }
                eventsSent++

                // FIXME: Not including this sometimes (often) causes crashes from locked database not correctly handled in aw-server-rust
                Thread.sleep(100)
            }
            return eventsSent
        }

        override fun onProgressUpdate(vararg progress: Pair<Int, Instant>) {
            val eventCount = progress[0].first
            val timestamp = progress[0].second
            Log.i(TAG, "Progress: ($eventCount/1000) $timestamp")
            //Snackbar.make(context.findViewById(R.id.coordinator_layout), "Successfully saved $eventsSent new events to the database!${if (eventsSent >= 100) " (max 100 events saved at a time, spamming the button is not recommended)" else ""}", Snackbar.LENGTH_LONG)
            //    .setAction("Action", null).show()
        }

        override fun onPostExecute(result: Int?) {
            //showDialog("Downloaded $result bytes")
        }
    }

    /***
     * Returns the number of events sent
     */
    fun sendHeartbeats() {
        SendHeartbeatsTask().execute()
    }

}