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
import java.util.Date
import java.util.Locale

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

    // TODO: AsyncTask is deprecated, replace with kotlin concurrency or java.util.concurrent
    private inner class SendHeartbeatsTask : AsyncTask<URL, Instant, Int>() {

        private val logEventFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        private var heartBeatsSent = 0

        fun logEventDetails(event: UsageEvents.Event) {
            val formattedTime = logEventFormatter.format(Date(event.timeStamp))

            // Additional logging based on the type of event
            val eventType = when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> "ACTIVITY_RESUMED"
                UsageEvents.Event.ACTIVITY_PAUSED -> "ACTIVITY_PAUSED"
                UsageEvents.Event.ACTIVITY_STOPPED -> "ACTIVITY_STOPPED"
                UsageEvents.Event.CONFIGURATION_CHANGE -> "CONFIGURATION_CHANGE"
                UsageEvents.Event.DEVICE_SHUTDOWN -> "DEVICE_SHUTDOWN"
                UsageEvents.Event.DEVICE_STARTUP -> "DEVICE_STARTUP"
                UsageEvents.Event.USER_INTERACTION -> "USER_INTERACTION"
                UsageEvents.Event.SHORTCUT_INVOCATION -> "SHORTCUT_INVOCATION"
                UsageEvents.Event.STANDBY_BUCKET_CHANGED -> "STANDBY_BUCKET_CHANGED"
                UsageEvents.Event.SCREEN_INTERACTIVE -> "SCREEN_INTERACTIVE"
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> "SCREEN_NON_INTERACTIVE"
                UsageEvents.Event.KEYGUARD_SHOWN -> "KEYGUARD_SHOWN"
                UsageEvents.Event.KEYGUARD_HIDDEN -> "KEYGUARD_HIDDEN"
                else -> "Unknown Event Type ${event.eventType}"
            }

            // Log the event details
            Log.d(TAG, "processing Event: ${formattedTime} ${eventType}  ${event.packageName}  ")
        }

        /**
         * Sends a heartbeat to aw-server, based on the Android UsageEvent for a specific app. Note that we do not store if the app
         * was 'opened' or 'closed', so we have to work around that.
         *
         * @longPulse can be set to TRUE if the app in the event is currently still open or just closed, so we want to merge the previous and last events
         * for this app to determine the duration. Set it to false if the app just (re)opened, so we don't count the duration between e.g.
         * a phone lock and unlock.
         *
         * @timestamp: normally the timestamp of the event will be used, but an alternative timestamp can be provided
         */
        private fun sendHeartbeat( event : UsageEvents.Event, longPulse : Boolean, customTimestamp : Long? = null ) {
            val awEvent = Event.fromUsageEvent(usageEvent = event, context, includeClassname = true, customTimestamp)
            val pulseTime = if( longPulse ) 60*60*24.0 else 3.0
            ri.heartbeatHelper(bucket_id, awEvent.timestamp, awEvent.duration, awEvent.data,  pulseTime )
            Log.d(TAG,"Heartbeat sent: ${awEvent.timestamp} longPulse: $longPulse ${awEvent.data.get("package")} ${awEvent.data.get("app")}")

            heartBeatsSent++;

            if(heartBeatsSent % 100 == 0) {
                publishProgress(awEvent.timestamp)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun doInBackground(vararg urls: URL): Int {
            Log.i(TAG, "Sending heartbeats...")

            // TODO: Use other bucket type when support for such a type has been implemented in aw-webui
            ri.createBucketHelper(bucket_id, "currentwindow")
            ri.createBucketHelper(unlock_bucket_id, "os.lockscreen.unlocks")
            lastUpdated = getLastEventTime()
            Log.w(TAG, "lastUpdated: ${lastUpdated?.toString() ?: "never"}")

            val usm = getUSM() ?: return 0

            // TODO: Fix issues that occur when usage stats events are out of order (RESUME before PAUSED)
            var heartbeatsSent = 0
            val usageEvents = usm.queryEvents(lastUpdated?.toEpochMilli() ?: 0L, Long.MAX_VALUE)
            var activeAppEvent : UsageEvents.Event? = null

            nextEvent@ while(usageEvents.hasNextEvent()) {
                val event = UsageEvents.Event()
                usageEvents.getNextEvent(event)

                if(event.eventType !in arrayListOf(UsageEvents.Event.ACTIVITY_RESUMED, UsageEvents.Event.ACTIVITY_PAUSED)) {
                    continue@nextEvent
                }

                // do not include launchers, since they are used all the time to switch between apps. It distorts the timeline while
                // it is more part of the OS than an app which we want to monitor
                if( event.packageName.contains("launcher", false) ) {
                    Log.d(TAG,"Skipping launcher event for package " + event.packageName)
                    continue@nextEvent
                }

                if( Log.isLoggable(TAG, Log.DEBUG)) {
                    logEventDetails(event)
                }

                when(event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {

                        if( activeAppEvent != null ) {
                            if( activeAppEvent.packageName.equals(event.packageName) ) {
                                continue@nextEvent
                            }

                            // another app is resumed than the current app. Close the current app by sending a heartbeat with a long pulsetime
                            // immediately followed up by the heartbeat for the new app that is currently open
                            sendHeartbeat(activeAppEvent, longPulse = true, customTimestamp = event.timeStamp)
                        }

                        // send a heartbeat for the newly opened app
                        // it could be that the app was closed before (e.g. screen was turned off) and now reopened, so these events will be
                        // consecutive. We do not want the time in between these events to count as duration, so send a heartbeat with short pulsetime.
                        sendHeartbeat(event, longPulse = false )

                        activeAppEvent = event;
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED -> {
                        // ACTIVITY_PAUSED: Activity was moved to background
                        if( activeAppEvent == null ) {
                            continue@nextEvent
                        }

                        if( !activeAppEvent.packageName.equals(event.packageName) ) {
                            // if the active app does not match this ACTIVITY_PAUSED event,
                            // we can safely ignore this event. Android has many apps (especially sytem related apps such as launchers)
                            // closing all the time, also in random order between the actual user app and the system app.
                            continue@nextEvent
                        }

                        sendHeartbeat(event, longPulse = true)

                        activeAppEvent = null
                    }
                    else -> {
                        Log.w(TAG, "This should never happen!")
                        continue@nextEvent
                    }
                }


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

            if(result != 0 && Log.isLoggable(TAG, Log.DEBUG)) {
                Toast.makeText(context, "Completed logging of data! Logged events: $result", Toast.LENGTH_LONG).show()
            }

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
