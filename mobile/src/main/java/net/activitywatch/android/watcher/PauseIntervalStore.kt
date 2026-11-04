package net.activitywatch.android.watcher

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Append-only log of private wall-clock intervals (#385).
 *
 * This is the authoritative *history* gate; `AWPreferences.LIVE_COLLECTION_PAUSED` stays the
 * authoritative *live collector* gate. Both are written from the same pause/resume action so
 * they cannot disagree about "now", and [loadOrMigrate] re-derives an open interval if a
 * pause was recorded while only the live flag existed (i.e. before this shipped).
 *
 * A boolean is not enough: both import paths resume from the last stored event, so simply not
 * importing while paused backfills the private window on resume. The window has to be
 * persisted and subtracted from every import range.
 */
internal class PauseIntervalStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)

    companion object {
        private const val TAG = "PauseIntervalStore"
        const val FILE_NAME = "collection-pause-intervals.json"
        private const val VERSION = 1

        /**
         * Serializes every read-modify-write across store instances. The UI records a pause
         * outside the import lock, so an unserialized prune could read the file before that
         * pause and write back without it — deleting a private interval that a later import
         * would then store.
         */
        private val lock = Any()
    }

    /**
     * Record a pause. Idempotent: an already-open interval is kept as-is.
     *
     * Returns false when the interval could not be persisted; the caller must not flip the
     * live flag in that case, because a flag without an interval would let the next import
     * backfill the pause from a later start.
     */
    fun recordPause(atMs: Long): Boolean = synchronized(lock) {
        val current = read()
        if (current.any { it.endMs == null }) return@synchronized true
        write(current + PauseInterval(startMs = atMs, endMs = null))
    }

    /** Close the open interval at [atMs]. No-op (true) when nothing is open. */
    fun recordResume(atMs: Long): Boolean = synchronized(lock) {
        val current = read()
        val openIndex = current.indexOfFirst { it.endMs == null }
        if (openIndex < 0) return@synchronized true
        val open = current[openIndex]
        val end = if (atMs > open.startMs) atMs else null
        if (end == null) {
            // Resume at or before the pause start: a backwards clock jump. Dropping the
            // interval keeps the log valid and stops it excluding history forever.
            write(current.filterIndexed { index, _ -> index != openIndex })
        } else {
            write(current.toMutableList().also { it[openIndex] = open.copy(endMs = end) })
        }
    }

    /**
     * Persisted intervals, reconciled with the live flag.
     *
     * Only a missing interval file may migrate `flagPaused` into an open interval. Once the
     * file exists it is authoritative: a caller may have sampled a stale true preference
     * just before recordResume closed the interval. Reopening it would suppress all later
     * history even after the UI cleared the flag.
     *
     * Migration appends `{startMs: now, endMs: null}`. Read the `now`
     * literally: history recorded before this upgrade was collected while only the live
     * collectors were paused, so it is not known-private and must import normally. Never
     * backdate to epoch or first launch — that would quietly delete real history instead of
     * honouring the user's pause.
     */
    fun loadOrMigrate(flagPaused: Boolean, nowMs: Long): List<PauseInterval> = synchronized(lock) {
        val current = PauseIntervals.normalize(read())
        if (!flagPaused || file.exists()) return@synchronized current
        val migrated = current + PauseInterval(startMs = nowMs, endMs = null)
        Log.i(TAG, "Migrated an open pause interval from the live-collection flag at $nowMs")
        write(migrated)
        PauseIntervals.normalize(migrated)
    }

    /**
     * Drop closed intervals that end before [keepFromMs].
     *
     * An import can never rewind before the last stored session start, so those intervals can
     * no longer affect any window. Never prune an open interval. Call only after a successful
     * import cycle.
     */
    fun prune(keepFromMs: Long) = synchronized(lock) {
        val current = PauseIntervals.normalize(read())
        val kept = current.filter { it.endMs == null || it.endMs >= keepFromMs }
        // No count-based cap: every remaining interval still overlaps a range a later import
        // can query, so dropping one to bound the file would store private history. The
        // age-based filter is the only safe bound.
        if (kept != current) write(kept)
    }

    private fun read(): List<PauseInterval> {
        if (!file.exists()) return emptyList()
        return try {
            val root = JSONObject(file.readText())
            if (root.optInt("version", 0) != VERSION) return emptyList()
            val array = root.optJSONArray("intervals") ?: JSONArray()
            (0 until array.length()).mapNotNull { index ->
                val entry = array.optJSONObject(index) ?: return@mapNotNull null
                val start = entry.optLong("startMs", -1L)
                if (start < 0L) return@mapNotNull null
                val end = if (entry.has("endMs") && !entry.isNull("endMs")) entry.optLong("endMs") else null
                PauseInterval(startMs = start, endMs = end)
            }
        } catch (e: Exception) {
            // A corrupt log must not stop collection; the live flag still gates live
            // collectors, and the next pause/resume rewrites the file.
            Log.w(TAG, "Unable to read pause intervals, treating as empty: ${e.message}")
            emptyList()
        }
    }

    /**
     * Atomic write: `.tmp` + rename, so a kill mid-write cannot truncate the log.
     * Returns false when nothing could be persisted.
     */
    private fun write(intervals: List<PauseInterval>): Boolean {
        val normalized = PauseIntervals.normalize(intervals)
        val array = JSONArray()
        for (interval in normalized) {
            array.put(
                JSONObject().apply {
                    put("startMs", interval.startMs)
                    if (interval.endMs == null) put("endMs", JSONObject.NULL) else put("endMs", interval.endMs)
                }
            )
        }
        val root = JSONObject().apply {
            put("version", VERSION)
            put("intervals", array)
        }
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        return try {
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(root.toString())
                tmp.delete()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Unable to persist pause intervals: ${e.message}")
            tmp.delete()
            false
        }
    }
}
