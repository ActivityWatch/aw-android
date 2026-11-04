package net.activitywatch.android.watcher

import net.activitywatch.android.data.AppSession
import net.activitywatch.android.parser.MAX_REASONABLE_SESSION_DURATION

/**
 * One private wall-clock window `[startMs, endMs)`.
 *
 * `endMs == null` means the pause is still in effect. At most one interval may be open;
 * [PauseIntervals.normalize] enforces that when a corrupt or partially-written file is read.
 *
 * Wall clock on purpose: `UsageEvents` timestamps are wall clock, so the exclusion stays on
 * the same timeline as the data it filters.
 */
internal data class PauseInterval(val startMs: Long, val endMs: Long? = null)

/** Half-open importable window: start inclusive, end exclusive. */
internal data class AllowedWindow(val startMs: Long, val endMs: Long)

/**
 * Pure interval arithmetic for the usage-history pause exclusion (#385).
 *
 * Nothing here touches Android or the datastore, so the rules that decide what may be
 * imported can be tested directly. The datastore side is [PauseIntervalStore].
 */
internal object PauseIntervals {

    /**
     * Sort, drop unusable intervals, clip overlaps and keep at most one open interval.
     *
     * - A closed interval with `endMs <= startMs` is dropped: a backwards clock jump must not
     *   become a negative window. The pause itself still holds because the live flag stays on.
     * - Overlapping closed intervals are unioned by clipping the later start.
     * - The first open interval wins; any interval after it is inside it and is dropped.
     */
    fun normalize(raw: List<PauseInterval>): List<PauseInterval> {
        val sorted = raw.sortedBy { it.startMs }
        val result = ArrayList<PauseInterval>(sorted.size)
        var coveredUntil: Long? = null

        for (interval in sorted) {
            val end = interval.endMs
            if (end != null && end <= interval.startMs) continue

            // An interval starting at or before the covered end is inside (or contiguous
            // with) the previous one: extend it rather than logging two adjacent windows.
            if (coveredUntil != null && interval.startMs <= coveredUntil) {
                if (end == null) {
                    result[result.lastIndex] = PauseInterval(result.last().startMs, null)
                    break
                }
                if (end > coveredUntil) {
                    result[result.lastIndex] = PauseInterval(result.last().startMs, end)
                    coveredUntil = end
                }
                continue
            }

            result.add(PauseInterval(interval.startMs, end))
            if (end == null) break else coveredUntil = end
        }
        return result
    }

    /**
     * `[fromMs, toMs)` minus the union of [pauses] clipped to that range.
     *
     * The returned windows are what the importer queries and parses, one sub-window at a
     * time. Importing per window is what makes the clip explicit: a session cannot straddle
     * a boundary because each window's parse ends at its own boundary.
     */
    fun allowedWindows(pauses: List<PauseInterval>, fromMs: Long, toMs: Long): List<AllowedWindow> {
        if (toMs <= fromMs) return emptyList()
        val windows = ArrayList<AllowedWindow>()
        var cursor = fromMs

        for (pause in normalize(pauses)) {
            if (pause.startMs >= toMs) break
            val pauseEnd = pause.endMs ?: Long.MAX_VALUE
            if (pauseEnd <= cursor) continue

            if (pause.startMs > cursor) {
                windows.add(AllowedWindow(cursor, minOf(pause.startMs, toMs)))
            }
            cursor = maxOf(cursor, minOf(pauseEnd, toMs))
            if (cursor >= toMs) break
        }

        if (cursor < toMs) windows.add(AllowedWindow(cursor, toMs))
        return windows
    }

    /**
     * A stored session beginning at a pause's end may have had its RESUMED inside (or
     * before) that pause. Its clipped timestamp is an output cursor, not a foreground
     * replay cursor. Recover state from the persisted boundary on every run, including
     * after process death. The parser cannot emit a new session >= its maximum duration,
     * so that much lookback covers every session it can import without an unbounded query.
     *
     * Callers MUST clip every parsed session to [window]: the lookback is state recovery,
     * not permission to reinsert earlier events or any private part of a session.
     */
    fun sessionReplayStart(pauses: List<PauseInterval>, storedStart: Long?, window: AllowedWindow): Long {
        val cursor = minOf(storedStart ?: window.startMs, window.startMs)
        return if (normalize(pauses).any { it.endMs == window.startMs }) {
            minOf(cursor, (window.startMs - MAX_REASONABLE_SESSION_DURATION).coerceAtLeast(0L))
        } else cursor
    }

    /**
     * Clip a session to [window], returning null when it falls entirely outside.
     *
     * Needed when a window is parsed from further back than its own start: the parser needs
     * the events that establish which app is in the foreground, but only the part of that
     * session inside the window may be stored.
     */
    fun clipToWindow(session: AppSession, window: AllowedWindow): AppSession? {
        val start = maxOf(session.startTime, window.startMs)
        val end = minOf(session.endTime, window.endMs)
        if (end <= start) return null
        return session.copy(startTime = start, endTime = end, durationMs = end - start)
    }

    /**
     * Split a session that straddles a pause into its importable parts.
     *
     * The per-window parse already produces split sessions, so this is the second line of
     * defence for a session that was assembled elsewhere (a replayed stored session, or a
     * future caller that queries one wide range). Zero- and negative-duration segments are
     * dropped; the same app on both sides of a pause is two sessions, never one.
     */
    fun splitSession(session: AppSession, pauses: List<PauseInterval>): List<AppSession> =
        allowedWindows(pauses, session.startTime, session.endTime).map { window ->
            AppSession(
                packageName = session.packageName,
                appName = session.appName,
                className = session.className,
                startTime = window.startMs,
                endTime = window.endMs,
            )
        }

    /**
     * True when a closed pause lies strictly between two consecutive inserts, so a heartbeat
     * merge from the first to the second would bridge the private window.
     */
    fun crossesPause(pauses: List<PauseInterval>, fromMs: Long, toMs: Long): Boolean =
        normalize(pauses).any { pause ->
            val end = pause.endMs
            end != null && pause.startMs >= fromMs && end <= toMs
        }

    /** True when [timestampMs] falls inside a pause. Unlock points inside one are dropped. */
    fun isExcluded(pauses: List<PauseInterval>, timestampMs: Long): Boolean =
        normalize(pauses).any { pause ->
            timestampMs >= pause.startMs && (pause.endMs == null || timestampMs < pause.endMs)
        }
}
