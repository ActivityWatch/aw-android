package net.activitywatch.android.watcher

import android.content.Context
import android.util.Log
import net.activitywatch.android.AWPreferences
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
    private val pauseStore = PauseIntervalStore(context.applicationContext)

    var lastUpdated: Instant? = null

    companion object {
        const val TAG = "SessionEventWatcher"
        private val lock = java.util.concurrent.locks.ReentrantLock()

        /** Keep a day of closed intervals past the last stored session start. */
        internal const val PAUSE_INTERVAL_RETENTION_MARGIN_MS = 24 * 60 * 60 * 1000L
    }

    // Resume from the last stored session's start, inclusive. The parser emits the
    // still-open foreground session ending "now", so that event must be read again on
    // the next run to extend it. Re-emitting it is safe: it has the same start and data
    // as the stored event, and insertEvent's heartbeat (pulsetime 0) merges it into that
    // event, keeping the later end, rather than inserting a duplicate.
    private fun nextQueryStartTimestamp(): Long = lastUpdated?.toEpochMilli() ?: 0L

    // Unlocks are zero-duration and never extended, so they resume strictly after the
    // last stored one: queryEvents' lower bound is inclusive, and replaying that
    // timestamp would duplicate the event on every run.
    private fun queryStartAfter(last: Instant?): Long = (last?.toEpochMilli()?.plus(1L)) ?: 0L

    suspend fun sendSessionEventsSuspend() {
        Log.w(TAG, "Starting SendSessionEventTask (awaitable)")
        withContext(Dispatchers.IO) {
            val result = processEventsSinceLastUpdate()
            Log.w(TAG, "Finished SendSessionEventTask, sent $result session events")
        }
    }

    private fun rustInterface(): RustInterface? = rust.await()

    // Data of the last stored session, reused when that session is emitted again so the
    // server merge (which needs identical data) extends it. Re-deriving it would read the
    // app's current label, which a language change or app update can alter, and the
    // replay would then be inserted as a second copy.
    private var lastStoredData: JSONObject? = null

    private fun getLastEvent(bucketId: String): JSONObject? {
        val ri = rustInterface() ?: return null
        val events = try {
            ri.getEventsJSON(bucketId, limit = 1)
        } catch (e: JSONException) {
            // Distinct from the empty-bucket case below: the read failed, so the caller
            // must not treat this as "no session stored yet" and re-derive one.
            Log.e(TAG, "Could not read the last event from '$bucketId': ${e.message}")
            throw e
        }
        return if (events.length() == 1) {
            events[0] as JSONObject
        } else if (events.length() == 0) {
            null  // normal on first run
        } else {
            Log.w(TAG, "Expected 1 event for last-event lookup in $bucketId, got ${events.length()}")
            null
        }
    }

    private fun eventStart(event: JSONObject): Instant? {
        val timestampString = event.getString("timestamp")
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

        // Read both cursors before changing state or querying Android history. A failed
        // lookup is not an empty bucket: skip this batch rather than replay from zero.
        val (lastSession, lastUnlockEvent) = try {
            getLastEvent(SESSION_BUCKET_ID) to getLastEvent(UNLOCK_BUCKET_ID)
        } catch (e: JSONException) {
            Log.w(TAG, "Skipping session batch after a failed cursor read", e)
            return 0
        }
        lastStoredData = lastSession?.optJSONObject("data")
        lastUpdated = lastSession?.let(::eventStart)
        Log.w(TAG, "lastUpdated: ${lastUpdated?.toString() ?: "never"}")

        val storedStart = lastUpdated?.toEpochMilli()
        val now = System.currentTimeMillis()

        // Import only the windows outside the user's private pause intervals (#385). Each
        // window is parsed with its own end, so a session is never assembled across a
        // paused gap and the clip happens before the end is ever persisted — a later
        // replay cannot shrink a stored event, because heartbeat merge keeps the longer end.
        val pauses = pauseIntervals()
        val windows = PauseIntervals.allowedWindows(pauses, nextQueryStartTimestamp(), now)

        // Unlocks need their own cursor. Resuming them from the last *session* start
        // re-sent every unlock since then on each run, and the server only merges a
        // heartbeat into the newest event, so all but the last were inserted again.
        val lastUnlock = lastUnlockEvent?.let(::eventStart)
        var unlockCursor = queryStartAfter(lastUnlock)

        var eventsSent = 0
        var unlocksSent = 0
        var previousWindowEnd: Long? = null

        for (window in windows) {
            // Unlocks inside a pause are suppressed and advance no stored event, so leave the
            // cursor alone until the window before the gap has actually been processed.
            // Floors it to the newest interval end instead would skip valid unlocks that
            // happened before the pause.
            previousWindowEnd?.let { unlockCursor = maxOf(unlockCursor, it) }

            // A clipped event starts at the pause's end, not at its original RESUMED.
            // Recover foreground state even for the FIRST window after restart; using
            // only storedStart would filter out RESUMED and permanently stop extension.
            val sessionsSince = PauseIntervals.sessionReplayStart(pauses, storedStart, window)

            val batch = sessionParser.parseSessionsAndUnlocksSince(
                sessionsSince,
                maxOf(unlockCursor, window.startMs),
                storedStart,
                until = window.endMs,
            )
            for (session in batch.sessions) {
                // Replay can include private/previously imported history. Never persist
                // that lookback, even in the first window of a subsequent import.
                val clipped = PauseIntervals.clipToWindow(session, window) ?: continue
                // Insert session as individual event
                val storedData = lastStoredData.takeIf { clipped.startTime == storedStart }
                insertSessionAsEvent(clipped, storedData)
                eventsSent++
            }
            for (timestamp in batch.unlockTimestamps) {
                val instant = DateTimeUtils.toInstant(java.util.Date(timestamp))
                ri.heartbeatHelper(UNLOCK_BUCKET_ID, instant, 0.0, JSONObject(), 0.0)
                unlocksSent++
            }
            previousWindowEnd = window.endMs
        }

        // Keep the boundary needed to recover clipped foreground state. Output windows
        // never rewind past storedStart; the retention margin also covers replay lookback.
        storedStart?.let { pauseStore.prune(it - PAUSE_INTERVAL_RETENTION_MARGIN_MS) }

        Log.i(TAG, "Finished processing events, sent $eventsSent session events and $unlocksSent unlock events in ${windows.size} window(s)")
        return eventsSent
    }

    private fun pauseIntervals(): List<PauseInterval> =
        pauseStore.loadOrMigrate(
            flagPaused = AWPreferences(context).isLiveCollectionPaused(),
            nowMs = System.currentTimeMillis(),
        )



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
