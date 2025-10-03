package net.activitywatch.android.watcher

import android.content.Context
import android.os.AsyncTask
import android.util.Log
import net.activitywatch.android.RustInterface
import net.activitywatch.android.data.AppSession
import net.activitywatch.android.parser.SessionParser
import net.activitywatch.android.utils.SessionUtils
import org.json.JSONObject
import org.threeten.bp.DateTimeUtils
import org.threeten.bp.Instant
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

const val SESSION_BUCKET_ID = "aw-watcher-android-test"
const val UNLOCK_BUCKET_ID = "aw-watcher-android-unlock"

class SessionEventWatcher(val context: Context) {
    private val ri = RustInterface(context)
    private val sessionParser = SessionParser(context)
    private val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)

    var lastUpdated: Instant? = null

    companion object {
        const val TAG = "SessionEventWatcher"
    }

    /**
     * Send individual events based on parsed sessions instead of heartbeats
     */
    fun sendSessionEvents() {
        Log.w(TAG, "Starting SendSessionEventTask")
        SendSessionEventTask().execute()
    }

    private fun getLastEventTime(): Instant? {
        val events = ri.getEventsJSON(SESSION_BUCKET_ID, limit = 1)
        return if (events.length() == 1) {
            val lastEvent = events[0] as JSONObject
            val timestampString = lastEvent.getString("timestamp")
            try {
                val timeCreatedDate = isoFormatter.parse(timestampString)
                DateTimeUtils.toInstant(timeCreatedDate)
            } catch (e: ParseException) {
                Log.e(TAG, "Unable to parse timestamp: $timestampString")
                null
            }
        } else {
            Log.w(TAG, "More or less than one event was retrieved when trying to get last event")
            null
        }
    }

    private inner class SendSessionEventTask : AsyncTask<Void, AppSession, Int>() {
        override fun doInBackground(vararg params: Void?): Int {
            Log.i(TAG, "Sending session events...")

            // Create bucket for session events
            ri.createBucketHelper(SESSION_BUCKET_ID, "currentwindow")
            ri.createBucketHelper(UNLOCK_BUCKET_ID, "os.lockscreen.unlocks")

            lastUpdated = getLastEventTime()
            Log.w(TAG, "lastUpdated: ${lastUpdated?.toString() ?: "never"}")

            val startTimestamp = lastUpdated?.toEpochMilli() ?: 0L
            val sessions = sessionParser.parseUsageEventsSince(startTimestamp)

            var eventsSent = 0

            for (session in sessions) {
                // Insert session as individual event
                insertSessionAsEvent(session)

                if (eventsSent % 10 == 0) {
                    publishProgress(session)
                }
                eventsSent++
            }

            return eventsSent
        }

        override fun onProgressUpdate(vararg progress: AppSession) {
            val session = progress[0]
            val timestamp = DateTimeUtils.toInstant(java.util.Date(session.endTime))
            lastUpdated = timestamp
            Log.i(TAG, "Progress: ${session.appName} - ${lastUpdated.toString()}")
        }

        override fun onPostExecute(result: Int?) {
            Log.w(TAG, "Finished SendSessionEventTask, sent $result session events")
        }
    }

    /**
     * Insert a single session as an individual event (not a heartbeat)
     */
    private fun insertSessionAsEvent(session: AppSession) {
        val startInstant = DateTimeUtils.toInstant(java.util.Date(session.startTime))
        val duration = session.durationSeconds
        val data = session.toEventData()

        // Use insertEvent method to insert as discrete event
        // This prevents merging behavior and treats each session as a separate event
        ri.insertEvent(SESSION_BUCKET_ID, startInstant, duration, data)

        Log.d(TAG, "Inserted session event for ${session.appName}: ${SessionUtils.formatDuration(session.durationMs)} (${session.startTime} - ${session.endTime})")
    }

    /**
     * Send session events for a specific day
     */
    fun sendSessionEventsForDay(dayStartMs: Long) {
        Log.i(TAG, "Sending session events for specific day: ${SessionUtils.formatDate(dayStartMs)}")

        val timeline = sessionParser.parseUsageEventsForDay(dayStartMs)

        ri.createBucketHelper(SESSION_BUCKET_ID, "currentwindow")

        var eventsSent = 0
        for (session in timeline.sessions) {
            insertSessionAsEvent(session)
            eventsSent++
        }

        Log.i(TAG, "Sent $eventsSent session events for day")
    }

    /**
     * Send session events for a date range
     */
    fun sendSessionEventsForPeriod(startTimestamp: Long, endTimestamp: Long) {
        Log.i(TAG, "Sending session events for period: ${SessionUtils.formatDateTime(startTimestamp)} to ${SessionUtils.formatDateTime(endTimestamp)}")

        val sessions = sessionParser.parseUsageEventsForPeriod(startTimestamp, endTimestamp)

        ri.createBucketHelper(SESSION_BUCKET_ID, "currentwindow")

        var eventsSent = 0
        for (session in sessions) {
            insertSessionAsEvent(session)
            eventsSent++
        }

        Log.i(TAG, "Sent $eventsSent session events for period")
    }

    /**
     * Insert multiple sessions as individual events
     */
    fun insertSessionsAsEvents(sessions: List<AppSession>) {
        Log.i(TAG, "Inserting ${sessions.size} sessions as individual events")

        ri.createBucketHelper(SESSION_BUCKET_ID, "currentwindow")

        var eventsSent = 0
        for (session in sessions) {
            insertSessionAsEvent(session)
            eventsSent++
        }

        Log.i(TAG, "Inserted $eventsSent session events")
    }

    /**
     * Get timeline for analysis without sending events
     */
    fun getTimelineForDay(dayStartMs: Long) = sessionParser.parseUsageEventsForDay(dayStartMs)

    /**
     * Get sessions since last update for analysis
     */
    fun getSessionsSinceLastUpdate(): List<AppSession> {
        val startTimestamp = lastUpdated?.toEpochMilli() ?: 0L
        return sessionParser.parseUsageEventsSince(startTimestamp)
    }

    /**
     * Force refresh - resend all sessions for today as individual events
     */
    fun forceRefreshToday() {
        val today = SessionUtils.getStartOfDay()
        sendSessionEventsForDay(today)
    }

    /**
     * Send session events for the last N days
     */
    fun sendSessionEventsForLastDays(numberOfDays: Int) {
        Log.i(TAG, "Sending session events for last $numberOfDays days")

        ri.createBucketHelper(SESSION_BUCKET_ID, "currentwindow")

        var totalEventsSent = 0

        for (i in 0 until numberOfDays) {
            val dayStart = SessionUtils.getStartOfDayDaysAgo(i)
            val timeline = sessionParser.parseUsageEventsForDay(dayStart)

            for (session in timeline.sessions) {
                insertSessionAsEvent(session)
                totalEventsSent++
            }

            Log.d(TAG, "Sent ${timeline.sessions.size} session events for day ${SessionUtils.formatDate(dayStart)}")
        }

        Log.i(TAG, "Sent total of $totalEventsSent session events for last $numberOfDays days")
    }

    /**
     * Insert session as discrete event with specific timestamp and duration
     */
    fun insertSessionEvent(
        packageName: String,
        appName: String,
        className: String = "",
        startTime: Long,
        durationMs: Long
    ) {
        val session = AppSession(
            packageName = packageName,
            appName = appName,
            className = className,
            startTime = startTime,
            endTime = startTime + durationMs
        )

        ri.createBucketHelper(SESSION_BUCKET_ID, "currentwindow")
        insertSessionAsEvent(session)

        Log.d(TAG, "Inserted individual session event for $appName: ${SessionUtils.formatDuration(durationMs)}")
    }

    /**
     * Clear all session events from the bucket (useful for testing)
     */
    fun clearSessionEvents() {
        // Note: There's no direct clear method in RustInterface
        // This is a placeholder for potential future implementation
        Log.w(TAG, "Clear session events not implemented - would require new RustInterface method")
    }

    /**
     * Get count of events in session bucket
     */
    fun getSessionEventCount(): Int {
        val events = ri.getEventsJSON(SESSION_BUCKET_ID)
        return events.length()
    }
}
