package net.activitywatch.android.watcher

import android.content.Context
import android.util.Log
import net.activitywatch.android.OffThreadInit
import net.activitywatch.android.RustInterface
import net.activitywatch.android.data.AppSession
import net.activitywatch.android.parser.SessionParser
import net.activitywatch.android.utils.SessionUtils
import org.json.JSONException
import org.json.JSONObject
import org.threeten.bp.DateTimeUtils
import org.threeten.bp.Instant
import kotlinx.coroutines.*
import kotlin.math.roundToLong

const val SESSION_BUCKET_ID = "aw-watcher-android"
const val UNLOCK_BUCKET_ID = "aw-watcher-android-unlock"

class SessionEventWatcher(val context: Context) {
    // Lazy so constructing SessionEventWatcher on the main thread (e.g. from the
    // lifecycleScope.launch body in MainActivity.onResume, which runs inline via
    // Dispatchers.Main.immediate) does not call Thread.start() on the UI thread.
    // On a heavily-loaded fresh boot Thread.start() can block long enough to ANR.
    // RustInterface itself uses loadLibrary + JNI init; those run on the worker
    // thread inside OffThreadInit, same pattern as WebWatcher/MediaWatcher (#262).
    private val rust by lazy {
        OffThreadInit(
            threadName = "SessionEventWatcher-init",
            logTag = TAG,
        ) { RustInterface(context.applicationContext) }
    }
    private val sessionParser = SessionParser(context)

    var lastUpdated: Instant? = null

    // The time a run reads up to; tests replace it to run at chosen times.
    internal var clock: () -> Long = System::currentTimeMillis

    companion object {
        const val TAG = "SessionEventWatcher"
        private val lock = java.util.concurrent.locks.ReentrantLock()
    }

    suspend fun sendSessionEventsSuspend() {
        Log.w(TAG, "Starting SendSessionEventTask (awaitable)")
        withContext(Dispatchers.IO) {
            val result = processEventsSinceLastUpdate()
            Log.w(TAG, "Finished SendSessionEventTask, sent $result session events")
        }
    }

    private fun rustInterface(): RustInterface? = rust.await()

    /** The bucket's newest events, newest first; empty on the first run. */
    private fun getNewestEvents(bucketId: String): List<JSONObject> {
        val ri = rustInterface() ?: return emptyList()
        val events = try {
            ri.getEventsJSON(bucketId, limit = STORED_EVENTS_TO_MATCH)
        } catch (e: JSONException) {
            // Distinct from an empty bucket: the read failed, so the caller must not treat
            // this as "no session stored yet" and re-derive one.
            Log.e(TAG, "Could not read the newest events from '$bucketId': ${e.message}")
            throw e
        }
        return (0 until events.length()).map { events.getJSONObject(it) }
    }

    private fun storedSession(event: JSONObject): StoredSession? {
        val start = eventStart(event)?.toEpochMilli() ?: return null
        val end = start + (event.optDouble("duration", 0.0) * 1000).roundToLong()
        return StoredSession(start, end, event.optJSONObject("data"))
    }

    // A move of Android's history is otherwise invisible after the fact: the events sent show
    // only where it left them.
    private fun logShift(what: String, shift: Long?) {
        when (shift) {
            0L -> Unit
            null -> Log.w(TAG, "The newest stored $what is not where it was stored and no move " +
                "of the history confirms where it went; resuming from its stored time")
            else -> Log.i(TAG, "Android moved its history by $shift ms since the newest stored " +
                "$what was stored; resuming from where it now is")
        }
    }

    private fun eventStart(event: JSONObject): Instant? {
        // A missing timestamp is as unreadable as a malformed one, not a failed read.
        val timestampString = event.optString("timestamp")
        return parseAwTimestamp(timestampString)
            ?: run {
                Log.e(TAG, "Unable to parse timestamp: $timestampString")
                null
            }
    }

    /**
     * Synchronously process events since last update.
     * Returns the number of events sent.
     */
    fun processEventsSinceLastUpdate(): Int {
        if (!lock.tryLock()) {
            Log.i(TAG, "processEventsSinceLastUpdate already running, skipping concurrent call")
            return 0
        }
        try {
            return processEventsSinceLastUpdateLocked()
        } finally {
            lock.unlock()
        }
    }

    private fun processEventsSinceLastUpdateLocked(): Int {
        val ri = rustInterface() ?: run {
            Log.w(TAG, "RustInterface not ready; skipping session event processing")
            return 0
        }
        Log.i(TAG, "Processing session events...")

        // Create bucket for session events
        ri.createBucketsHelper(
            mapOf(SESSION_BUCKET_ID to "currentwindow", UNLOCK_BUCKET_ID to "os.lockscreen.unlocks")
        )

        // Read both buckets' newest events before changing state or querying Android history.
        // A failed lookup is not an empty bucket: skip this batch rather than replay from zero.
        val (sessionEvents, unlockEvents) = try {
            getNewestEvents(SESSION_BUCKET_ID) to getNewestEvents(UNLOCK_BUCKET_ID)
        } catch (e: JSONException) {
            Log.w(TAG, "Skipping session batch after a failed cursor read", e)
            return 0
        }
        // An event whose time cannot be read is left out, and its bucket resumes from the newest
        // one that can. If that was the newest event, what is sent again from there is its own
        // session or unlock, with its time and data, which the datastore merges into it.
        // Skipping the run instead would skip every later one too: no newer event could be
        // stored to take its place.
        val storedSessions = sessionEvents.mapNotNull(::storedSession)
        // Unlocks resume from their own bucket: the server only merges a heartbeat into a
        // bucket's newest event, so resuming them from an older time would insert again every
        // unlock after it.
        val storedUnlocks = unlockEvents.mapNotNull { eventStart(it)?.toEpochMilli() }
        lastUpdated = storedSessions.firstOrNull()?.let { Instant.ofEpochMilli(it.start) }
        Log.w(TAG, "lastUpdated: ${lastUpdated?.toString() ?: "never"}")

        val now = clock()
        // One PackageManager binder call per app rather than per session.
        val appNames = HashMap<String, String>()
        val plan = readAndPlanIngest(
            storedSessions, storedUnlocks, now,
            read = { since -> sessionParser.readSessionEventsSince(since, now) },
        ) { pkg -> appNames.getOrPut(pkg) { SessionUtils.getAppName(context, pkg) } }
        if (storedSessions.isNotEmpty()) logShift("session", plan.sessionShift)
        if (storedUnlocks.isNotEmpty()) logShift("unlock", plan.unlockShift)
        val unlockTimestamps = plan.unlocks

        var eventsSent = 0

        for (toSend in plan.sessions) {
            insertSessionAsEvent(toSend.session, toSend.storedData)
            eventsSent++
        }
        
        for (timestamp in unlockTimestamps) {
            val instant = DateTimeUtils.toInstant(java.util.Date(timestamp))
            ri.heartbeatHelper(UNLOCK_BUCKET_ID, instant, 0.0, JSONObject(), 0.0)
        }
        
        Log.i(TAG, "Finished processing events, sent $eventsSent session events and ${unlockTimestamps.size} unlock events")
        return eventsSent
    }



    /**
     * Insert a single session as an individual event (not a heartbeat)
     */
    private fun insertSessionAsEvent(session: AppSession, storedData: JSONObject? = null) {
        val ri = rustInterface() ?: return
        val startInstant = DateTimeUtils.toInstant(java.util.Date(session.startTime))
        val duration = session.durationSeconds
        val data = storedData ?: session.toEventData()

        // Use insertEvent method to insert as discrete event
        // This prevents merging behavior and treats each session as a separate event
        ri.insertEvent(SESSION_BUCKET_ID, startInstant, duration, data)

        Log.d(TAG, "Inserted session event for ${session.appName}: ${SessionUtils.formatDuration(session.durationMs)} (${session.startTime} - ${session.endTime})")
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

        val ri = rustInterface() ?: return
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
        val ri = rustInterface() ?: return 0
        return try {
            ri.getEventsJSON(SESSION_BUCKET_ID).length()
        } catch (e: JSONException) {
            Log.e(TAG, "Could not read session events: ${e.message}")
            0
        }
    }
}
