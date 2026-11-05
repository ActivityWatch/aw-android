package net.activitywatch.android.watcher

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Append-only log of private wall-clock intervals (#385).
 *
 * This is the authoritative history and current-state gate after migration. The legacy
 * `AWPreferences.LIVE_COLLECTION_PAUSED` flag supplies the migration fallback and collector
 * notifications, not a second durable state: a kill between the file and preference writes
 * must not leave live collection disagreeing with history import. [loadOrMigrate] creates
 * an open interval if a pause was recorded while only that flag existed.
 *
 * A boolean is not enough: both import paths resume from the last stored event, so simply not
 * importing while paused backfills the private window on resume. The window has to be
 * persisted and subtracted from every import range.
 */
internal class PauseIntervalStore(
    context: Context,
    private val wallClockMs: () -> Long = { System.currentTimeMillis() },
) {

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

    /** Once migrated, the interval file is the durable collection-state authority too. */
    fun isPaused(fallback: Boolean): Boolean = synchronized(lock) {
        if (file.exists()) read()?.any { it.endMs == null } ?: true else fallback
    }

    /**
     * Record a pause. Idempotent: an already-open interval is kept as-is.
     *
     * Returns false when the interval could not be persisted; the caller must not flip the
     * live flag in that case, because a flag without an interval would let the next import
     * backfill the pause from a later start.
     */
    fun recordPause(atMs: Long): Boolean = synchronized(lock) {
        val current = read() ?: return@synchronized false
        if (current.any { it.endMs == null }) return@synchronized true
        write(current + PauseInterval(startMs = atMs, endMs = null))
    }

    /** Close the open interval at [atMs]. No-op (true) when nothing is open. */
    fun recordResume(atMs: Long): Boolean = synchronized(lock) {
        val current = read() ?: return@synchronized false
        val openIndex = current.indexOfFirst { it.endMs == null }
        if (openIndex < 0) return@synchronized true
        val open = current[openIndex]
        val resumeFloor = maxOf(open.startMs, rollbackResumeFloor())
        val end = if (atMs > resumeFloor) atMs else null
        if (end == null) {
            // A backwards clock jump cannot close this wall-clock interval safely.
            // Preserve the durable boundary and report failure instead of silently
            // deleting private history. The caller keeps collectors paused.
            false
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
        val current = read() ?: return@synchronized listOf(PauseInterval(0L))
        // nowMs is a query cutoff sampled before acquiring this lock. A concurrent UI
        // pause can legitimately start after that cutoff: only a fresh clock sample can
        // provide evidence of rollback. Endpoint samples cannot detect a rollback that
        // catches up entirely between observations; this is not arbitrary clock recovery.
        val observedNow = wallClockMs()
        val open = current.firstOrNull { it.endMs == null }
        if (open != null && observedNow < open.startMs) {
            // Wall-clock timestamps cannot distinguish public from rolled-back private
            // events. Preserve closed evidence too: after resume an older closed interval
            // can extend beyond the newly closed epoch-based exclusion.
            val expanded = normalizeForStorage(current.filter { it.endMs != null } + PauseInterval(0L))
            write(expanded, maxOf(open.startMs, rollbackResumeFloor()))
            return@synchronized expanded
        }
        if (!flagPaused || file.exists()) return@synchronized current
        val migrated = current + PauseInterval(startMs = nowMs, endMs = null)
        Log.i(TAG, "Migrated an open pause interval from the live-collection flag at $nowMs")
        if (!write(migrated)) {
            // The interval exists only in memory: the file still has no boundary, so a
            // later import would see an empty history and backfill the private window.
            // Fail closed exactly as an unreadable file does. The migration is retried on
            // the next call once the obstruction is gone, so public history is not lost.
            return@synchronized listOf(PauseInterval(0L))
        }
        PauseIntervals.normalize(migrated)
    }

    /**
     * Drop closed intervals that end before [keepFromMs].
     *
     * [keepFromMs] must be safe for EVERY consumer, including delayed browser writes, not
     * just session/unlock replay. No production caller currently proves that floor, so
     * automatic pruning is disabled. Never prune an open interval.
     */
    fun prune(keepFromMs: Long) = synchronized(lock) {
        val current = read() ?: return@synchronized
        val kept = current.filter { it.endMs == null || it.endMs >= keepFromMs }
        // No count-based cap: every remaining interval still overlaps a range a later import
        // can query, so dropping one to bound the file would store private history. The
        // age-based filter is safe only with the all-consumer floor described above.
        if (kept != current) write(kept)
    }

    /** Null is unreadable privacy evidence, never permission to collect or overwrite. */
    private fun read(): List<PauseInterval>? {
        if (!file.exists()) return emptyList()
        return try {
            val root = JSONObject(file.readText())
            require(root.getInt("version") == VERSION) { "Unsupported pause history version" }
            val array = root.getJSONArray("intervals")
            normalizeForStorage((0 until array.length()).map { index ->
                val entry = array.getJSONObject(index)
                val start = entry.getLong("startMs")
                require(start >= 0L) { "Invalid pause start" }
                require(entry.has("endMs")) { "Missing pause end" }
                val end = if (entry.isNull("endMs")) null else entry.getLong("endMs")
                require(end == null || end > start) { "Invalid pause end" }
                PauseInterval(startMs = start, endMs = end)
            })
        } catch (e: Exception) {
            Log.w(TAG, "Unable to read pause intervals; collection remains blocked: ${e.message}")
            null
        }
    }

    /**
     * Atomic write: `.tmp` + rename, so a kill mid-write cannot truncate the log.
     * Returns false when nothing could be persisted.
     */
    private fun rollbackResumeFloor(): Long = if (file.exists()) {
        try {
            JSONObject(file.readText()).optLong("resumeAfterMs", 0L)
        } catch (e: Exception) {
            Long.MAX_VALUE
        }
    } else 0L

    /**
     * Unlike the query union, durable evidence must retain closed ranges hidden beneath
     * an open range. Closing an earlier pause after a clock correction must not erase a
     * later closed exclusion. Query callers may still union this redundant representation.
     */
    private fun normalizeForStorage(intervals: List<PauseInterval>): List<PauseInterval> {
        val closed = PauseIntervals.normalize(intervals.filter { it.endMs != null })
        val open = intervals.filter { it.endMs == null }.minByOrNull { it.startMs }
        return (closed + listOfNotNull(open)).sortedBy { it.startMs }
    }

    private fun write(intervals: List<PauseInterval>, resumeAfterMs: Long = rollbackResumeFloor()): Boolean {
        val normalized = normalizeForStorage(intervals)
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
            put("resumeAfterMs", resumeAfterMs)
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
