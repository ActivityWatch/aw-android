package net.activitywatch.android.watcher

import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.AsyncTask
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
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

    fun isUsageAllowed(): Boolean {
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

            // Needed since Toasts can only be created in the UI thread, and getUSM isn't always called in the UI thread
            Handler(Looper.getMainLooper()).post {
                run {
                    Toast.makeText(context, "Please grant ActivityWatch usage access", Toast.LENGTH_LONG).show()
                }
            }
            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            null
        }
    }

    private var alarmMgr: AlarmManager? = null
    private lateinit var alarmIntent: PendingIntent

    fun setupAlarm() {
        alarmMgr = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmIntent = Intent(context, AlarmReceiver::class.java).let { intent ->
            intent.action = "net.activitywatch.android.watcher.LOG_DATA"
            PendingIntent.getBroadcast(context, 0, intent, 0)
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
        private var heartbeatsSent = 0;
        override fun doInBackground(vararg urls: URL): Int? {
            Log.i(TAG, "Sending heartbeats...")

            // TODO: Use other bucket type when support for such a type has been implemented in aw-webui
            ri.createBucketHelper(bucket_id, "currentwindow")
            lastUpdated = getLastEventTime()
            Log.w(TAG, "lastUpdated: ${lastUpdated?.toString() ?: "never"}")

            val usm = getUSM() ?: return 0
            val usageEvents = usm.queryEvents(lastUpdated?.toEpochMilli() ?: 0L, Long.MAX_VALUE)
            // App in last loop iteration if it triggered a SCREEN_NON_INTERACTIVE(i.e AFK), otherwise NULL
            var queuedAfkEvent : Event? = null
            // App name in last loop iteration if it triggered a MOVE_TO_FOREGROUND/BACKGROUND, otherwise NULL
            var prevEventAppName: String? = null
            val lastEvent = getLastEvent()
            if(lastEvent != null) {
                var prevEventData = JSONObject(lastEvent.getString("data"))
                prevEventAppName = prevEventData.getString("app")
                Log.w(TAG, "lastAppName: ${prevEventAppName}")
            }
            nextEvent@ while(usageEvents.hasNextEvent()) {
                val event = UsageEvents.Event()
                usageEvents.getNextEvent(event)
                if(event.eventType !in arrayListOf(UsageEvents.Event.ACTIVITY_RESUMED, UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.SCREEN_INTERACTIVE, UsageEvents.Event.SCREEN_NON_INTERACTIVE)) {
                    // Not sure which events are triggered here, so we use a (probably safe) fallback
                    //Log.d(TAG, "Rare eventType: ${event.eventType}, skipping")
                    // send the previous afk event if there is no event after this
                    if(!usageEvents.hasNextEvent() && queuedAfkEvent != null){
                        sendHeartbeatHelper(queuedAfkEvent,24 * 60 * 60.0)
                        queuedAfkEvent = null
                    }
                    continue@nextEvent
                }

                val currawEvent = Event.fromUsageEvent(event, context, includeClassname = true)
                val pulsetime: Double
                when(event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED, UsageEvents.Event.SCREEN_INTERACTIVE -> {
                        // MOVE_TO_FOREGROUND: New Activity was opened
                        // SCREEN_INTERACTIVE: Screen just became interactive, user was previously therefore not active on the device
                        pulsetime = 1.0
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                        // MOVE_TO_BACKGROUND: Activity was moved to background
                        // SCREEN_NOT_INTERACTIVE: Screen locked/turned off, user is therefore now AFK, and this is the last event
                        pulsetime = 24 * 60 * 60.0   // 24h, we will assume events should never grow longer than that
                    }
                    else -> {
                        Log.w(TAG, "This should never happen!")
                        // send the previous afk event if there is no event after this
                        if(!usageEvents.hasNextEvent() && queuedAfkEvent != null){
                            sendHeartbeatHelper(queuedAfkEvent,24 * 60 * 60.0)
                            queuedAfkEvent = null
                        }
                        continue@nextEvent
                    }
                }
                if(usageEvents.hasNextEvent()){
                    if(queuedAfkEvent != null){
                        // there is an event in the queue, so it must be sent in this iteration.
                        // if prev app name matches the current event name, then send current event first to merge the heartbeats
                        // Note: Only do this if event is not SCREEN_INTERACTIVE type, SCREEN_INTERACTIVE type needs
                        // to be in the right order with respect to an AFK event(SCREEN_NON_INTERACTIVE)
                        if(currawEvent.data.getString(("app")) == prevEventAppName && event.eventType != UsageEvents.Event.SCREEN_INTERACTIVE){
                            sendHeartbeatHelper(currawEvent, pulsetime)
                            sendHeartbeatHelper(queuedAfkEvent, pulsetime)
                            queuedAfkEvent = null
                            // no need to update prevEventAppName since they're equal
                        }
                        // otherwise send the queued event first
                        else {
                            sendHeartbeatHelper(queuedAfkEvent, pulsetime)
                            queuedAfkEvent = null
                            // if curr event is AFK, queue it instead
                            if(event.eventType == UsageEvents.Event.SCREEN_NON_INTERACTIVE){
                                queuedAfkEvent = currawEvent
                            }
                            else{ //otherwise send it
                                sendHeartbeatHelper(currawEvent, pulsetime)
                                prevEventAppName = currawEvent.data.getString("app")
                            }
                        }
                    }
                    else{
                        // there is no AfkEvent queued, therefore we can just look at the current event

                        // if current event is noninteractive(i.e AFK), don't send it, queue it instead
                        if(event.eventType == UsageEvents.Event.SCREEN_NON_INTERACTIVE){
                            queuedAfkEvent = currawEvent
                        }
                        else{ // current event is active, so send it
                            sendHeartbeatHelper(currawEvent, pulsetime)
                            prevEventAppName = currawEvent.data.getString("app")
                        }
                    }
                }
                else{
                    if(queuedAfkEvent != null){
                        // there is a queued event, so need to send it in this iteration

                        // if currEvent matches last event, send curr first then the queuedAfkevent
                        if(currawEvent.data.getString(("app")) == prevEventAppName && event.eventType != UsageEvents.Event.SCREEN_INTERACTIVE){
                            sendHeartbeatHelper(currawEvent, pulsetime)
                            sendHeartbeatHelper(queuedAfkEvent, pulsetime)
                        }
                        else{ // otherwise send events in normal order
                            sendHeartbeatHelper(queuedAfkEvent, pulsetime)
                            sendHeartbeatHelper(currawEvent, pulsetime)
                        }
                    }
                    else{ // no queued event, so send current one
                        sendHeartbeatHelper(currawEvent, pulsetime)
                    }
                }
            }
            return heartbeatsSent
        }
        private fun sendHeartbeatHelper(awEvent: Event, pulsetime: Double){
            Log.w(TAG,awEvent.toString())
            ri.heartbeatHelper(bucket_id, awEvent.timestamp, awEvent.duration, awEvent.data, pulsetime)
            if(heartbeatsSent % 100 == 0) {
                publishProgress(awEvent.timestamp)
            }
            heartbeatsSent++
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