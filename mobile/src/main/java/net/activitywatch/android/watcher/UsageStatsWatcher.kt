package net.activitywatch.android.watcher

import android.Manifest
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.AppOpsManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
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
import net.activitywatch.android.RustInterface
import net.activitywatch.android.models.Event
import org.json.JSONObject
import org.threeten.bp.DateTimeUtils
import org.threeten.bp.Instant
import java.net.URL
import java.text.ParseException
import java.text.SimpleDateFormat

const val bucket_id = "aw-watcher-android-test"
const val unlock_bucket_id = "aw-watcher-android-unlock"

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

    private var alarmMgr: AlarmManager? = null
    private lateinit var alarmIntent: PendingIntent

    fun setupAlarm() {
        alarmMgr = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmIntent = Intent(context, AlarmReceiver::class.java).let { intent ->
            intent.action = "net.activitywatch.android.watcher.LOG_DATA"
            PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        }

        val interval = AlarmManager.INTERVAL_HOUR   // Or if testing: AlarmManager.INTERVAL_HOUR / 60
        alarmMgr?.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + interval,
            interval,
            alarmIntent
        )
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

    private inner class SendHeartbeatsTask : AsyncTask<URL, Instant, Int>() {
        override fun doInBackground(vararg urls: URL): Int? {
            Log.i(TAG, "Sending heartbeats...")

            // TODO: Use other bucket type when support for such a type has been implemented in aw-webui
            ri.createBucketHelper(bucket_id, "currentwindow")
            ri.createBucketHelper(unlock_bucket_id, "os.lockscreen.unlocks")
            lastUpdated = getLastEventTime()
            Log.w(TAG, "lastUpdated: ${lastUpdated?.toString() ?: "never"}")

            val usm = getUSM() ?: return 0

            // Store activities here that have had a RESUMED but not a PAUSED event.
            // (to handle out-of-order events)
            //val activeActivities = [];

            // TODO: Fix issues that occur when usage stats events are out of order (RESUME before PAUSED)
            var heartbeatsSent = 0
            val usageEvents = usm.queryEvents(lastUpdated?.toEpochMilli() ?: 0L, Long.MAX_VALUE)
            nextEvent@ while(usageEvents.hasNextEvent()) {
                val event = UsageEvents.Event()
                usageEvents.getNextEvent(event)

                // Log screen unlock
                if(event.eventType !in arrayListOf(UsageEvents.Event.ACTIVITY_RESUMED, UsageEvents.Event.ACTIVITY_PAUSED)) {
                    if(event.eventType == UsageEvents.Event.KEYGUARD_HIDDEN){
                        val timestamp = DateTimeUtils.toInstant(java.util.Date(event.timeStamp))
                        // NOTE: getLastEventTime() returns the last time of an event from  the activity bucket(bucket_id)
                        // Therefore, if an unlock happens after last event from main bucket, unlock event will get sent twice.
                        // Fortunately not an issue because identical events will get merged together (see heartbeats)
                        ri.heartbeatHelper(unlock_bucket_id, timestamp, 0.0, JSONObject(), 0.0)
                    }
                    // Not sure which events are triggered here, so we use a (probably safe) fallback
                    //Log.d(TAG, "Rare eventType: ${event.eventType}, skipping")
                    continue@nextEvent
                }

                // Log activity
                val awEvent = Event.fromUsageEvent(event, context, includeClassname = true)
                val pulsetime: Double
                when(event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        // ACTIVITY_RESUMED: Activity was opened/reopened
                        pulsetime = 1.0
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED -> {
                        // ACTIVITY_PAUSED: Activity was moved to background
                        pulsetime = 24 * 60 * 60.0   // 24h, we will assume events should never grow longer than that
                    }
                    else -> {
                        Log.w(TAG, "This should never happen!")
                        continue@nextEvent
                    }
                }

                ri.heartbeatHelper(bucket_id, awEvent.timestamp, awEvent.duration, awEvent.data, pulsetime)
                if(heartbeatsSent % 100 == 0) {
                    publishProgress(awEvent.timestamp)
                }
                heartbeatsSent++
            }
            return heartbeatsSent
        }

        override fun onProgressUpdate(vararg progress: Instant) {
            lastUpdated = progress[0]
            Log.i(TAG, "Progress: ${lastUpdated.toString()}")
            // The below is useful in testing, but otherwise just noisy.
            //Toast.makeText(context, "Logging data, progress: $lastUpdated", Toast.LENGTH_LONG).show()
        }

        override fun onPostExecute(result: Int?) {
            Log.w(TAG, "Finished SendHeartbeatTask, sent $result events")
            // The below is useful in testing, but otherwise just noisy.
            /*
            if(result != 0) {
                Toast.makeText(context, "Completed logging of data! Logged events: $result", Toast.LENGTH_LONG).show()
            }
            */
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
