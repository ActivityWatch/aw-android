package net.activitywatch.android.watcher

import android.app.usage.UsageEvents
import net.activitywatch.android.data.AppSession
import net.activitywatch.android.data.UsageEvent
import net.activitywatch.android.parser.MAX_REASONABLE_SESSION_DURATION
import net.activitywatch.android.parser.MIN_SESSION_DURATION
import net.activitywatch.android.parser.parseForegroundSessions
import org.json.JSONObject
import kotlin.math.abs

/**
 * Largest move of Android's saved usage history, either way, that a resumed ingest looks for.
 *
 * When Android corrects the clock it moves every usage event it has saved by the correction, to
 * the millisecond. The corrections seen on a phone were of seconds, most right after a boot. A
 * minute leaves room for larger ones; a wider window would cover only clock changes far beyond
 * any seen, and every other event of the same kind within it is a chance to line up the wrong
 * one.
 */
internal const val MAX_HISTORY_SHIFT_MS = 60 * 1000L

/**
 * How many of a bucket's newest events are looked for again to measure that move. The events
 * that confirm a move for one bucket's newest event can be in the other bucket, stored by the
 * same run, and are pushed back by everything stored since: an unlock can be followed by an hour
 * of app switches before the next one.
 */
internal const val STORED_EVENTS_TO_MATCH = 50

/**
 * How long before a bucket's newest event its other newest events are read again. The events
 * that confirm a move are those stored before the same correction as the newest one, most
 * often by the same run, minutes before it; the bound keeps a bucket whose events are days
 * apart from making every run read days of history.
 */
internal const val STORED_EVENTS_SPAN_MS = 6 * 60 * 60 * 1000L

/**
 * A stored event to look for in Android's history: its stored start, which usage event started
 * it, and [kind], equal for marks that [startedBy] the same usage events.
 */
internal class StoredMark(val time: Long, val kind: Any, val startedBy: (UsageEvent) -> Boolean)

/**
 * How far Android has moved its history since [marks] were stored, newest mark first: the amount
 * to add to a stored time to find its usage event in [events].
 *
 * 0 when the newest mark's usage event is still at its stored time. Otherwise the move, up to
 * [MAX_HISTORY_SHIFT_MS], that puts the newest mark's usage event and the most other marks' ones
 * back at their stored times, to the millisecond: another event of the same kind near the newest
 * mark, such as the same activity opened again, lines up no other mark. Null when no move is
 * confirmed by another mark, so a lone match is never trusted. A mark of the newest one's kind
 * at its time, a second copy of it, confirms nothing; one of another kind at that time, such as
 * an app opened as the phone is unlocked, does.
 */
internal fun findHistoryShift(events: List<UsageEvent>, marks: List<StoredMark>): Long? {
    val newest = marks.firstOrNull() ?: return null
    val candidates = events.filter {
        newest.startedBy(it) && abs(it.timeStamp - newest.time) <= MAX_HISTORY_SHIFT_MS
    }
    if (candidates.any { it.timeStamp == newest.time }) return 0L
    val byTime = events.groupBy { it.timeStamp }
    val others = marks.drop(1).filter { it.time != newest.time || it.kind != newest.kind }
    var best: Long? = null
    var bestSupport = 0
    for (candidate in candidates) {
        val shift = candidate.timeStamp - newest.time
        val support = others.count { mark -> byTime[mark.time + shift].orEmpty().any(mark.startedBy) }
        if (support > bestSupport || (support > 0 && support == bestSupport && abs(shift) < abs(best!!))) {
            best = shift
            bestSupport = support
        }
    }
    return best
}

/** A session event read back from the bucket: start and end in ms, and its data. */
internal class StoredSession(val start: Long, val end: Long, val data: JSONObject?) {
    // A session starts at its app's ACTIVITY_RESUMED, and its data names that event's package
    // and, when Android gave one, its class.
    fun mark(): StoredMark {
        val packageName = data?.optString("package", "").orEmpty()
        val className = data?.optString("classname", "").orEmpty()
        return StoredMark(start, kind = packageName to className) {
            it.eventType == UsageEvents.Event.ACTIVITY_RESUMED && packageName.isNotEmpty() &&
                it.packageName == packageName && it.className == className
        }
    }
}

/** Marks for stored unlock times: an unlock is a KEYGUARD_HIDDEN event. */
internal fun unlockMarks(stored: List<Long>): List<StoredMark> = stored.map { time ->
    StoredMark(time, kind = UsageEvents.Event.KEYGUARD_HIDDEN) {
        it.eventType == UsageEvents.Event.KEYGUARD_HIDDEN
    }
}

/** What a run sends, and the moves of the history it found for each bucket. */
internal class IngestPlan(
    val sessions: List<SessionToSend>,
    val unlocks: List<Long>,
    val sessionShift: Long?,
    val unlockShift: Long?,
)

/**
 * What a run sends after [storedSessions] and [storedUnlocks], each bucket's newest events
 * (newest first), from [history] read up to [now].
 */
internal fun planIngest(
    history: List<UsageEvent>,
    storedSessions: List<StoredSession>,
    storedUnlocks: List<Long>,
    now: Long,
    appName: (packageName: String) -> String,
): IngestPlan {
    val sessionMarks = storedSessions.map(StoredSession::mark)
    val unlockMarks = unlockMarks(storedUnlocks)
    // The same runs write both buckets, so the events of one confirm a move for the other's
    // newest event: unlocks are sparse, and the newest one is often the only one stored since
    // the last correction.
    val sessionShift = if (sessionMarks.isEmpty()) null else findHistoryShift(history, sessionMarks + unlockMarks)
    val unlockShift = if (unlockMarks.isEmpty()) null else findHistoryShift(history, unlockMarks + sessionMarks)
    return IngestPlan(
        sessionsToSend(history, storedSessions, sessionShift, now, appName),
        unlocksToSend(history, storedUnlocks, unlockShift, now),
        sessionShift,
        unlockShift,
    )
}

/**
 * Reads the history with [read], from the time it is given up to [now], and plans a run as
 * [planIngest] does with the history read from [historyReadStart].
 *
 * The first read starts the largest move looked for before the older of the two buckets' newest
 * events. When both are still at their stored times, as after every run with no correction in
 * between, that read already holds everything the plan uses. Only when one is not is the history
 * read again from [historyReadStart], for the older stored events that confirm the move.
 */
internal fun readAndPlanIngest(
    storedSessions: List<StoredSession>,
    storedUnlocks: List<Long>,
    now: Long,
    read: (since: Long) -> List<UsageEvent>,
    appName: (packageName: String) -> String,
): IngestPlan {
    val fullStart = historyReadStart(storedSessions.map { it.start }, storedUnlocks)
    val newestSession = storedSessions.firstOrNull()?.start
    val newestUnlock = storedUnlocks.firstOrNull()
    if (newestSession == null || newestUnlock == null) {
        return planIngest(read(fullStart), storedSessions, storedUnlocks, now, appName)
    }
    val nearStart = maxOf(fullStart, minOf(newestSession, newestUnlock) - MAX_HISTORY_SHIFT_MS)
    val plan = planIngest(read(nearStart), storedSessions, storedUnlocks, now, appName)
    if (nearStart == fullStart || (plan.sessionShift == 0L && plan.unlockShift == 0L)) return plan
    return planIngest(read(fullStart), storedSessions, storedUnlocks, now, appName)
}

/** A session to send, and the data to send it with: null for the session's own. */
internal class SessionToSend(val session: AppSession, val storedData: JSONObject?)

/**
 * The sessions in [history] to send after [stored], the bucket's newest session events (newest
 * first), the session still open at [now] ending there. [shift] is how far Android has moved its
 * history since they were stored, from [findHistoryShift]; null is read as no move.
 *
 * The newest stored session is read again from where it now starts in [history], since it may
 * have been stored while still open. It is sent with its stored start and data: the datastore's
 * heartbeat (pulsetime 0) merges it into the stored event, keeping the later end, only when the
 * data is the same and it starts within that event. Re-deriving the data would read the app's
 * current label, which a language change or app update can alter, and the session would then be
 * inserted as a second copy.
 *
 * After a move back, the sessions that now start before the newest stored one ends, or as it
 * ends, are sent in the stored events' times, moved back by the move, which are their times
 * before it. One starting as it ends would otherwise be merged into it by the datastore. The
 * datastore extends only the event with the latest start, so a session stored before that
 * one could never be extended, and the next run would send it again; in the stored events'
 * times it comes after them, and the next run finds it at the same offset as them. Each is cut
 * where the first session sent at its current time starts, and at [now]. One left with no time
 * is not sent; when that is because of [now], a later run sends it. A move forward puts the
 * sessions after the stored one later still, so after it every session is sent at its current
 * time: one that starts as the stored one ends was recorded after the correction.
 *
 * After a move back, the events sent in the stored times, the newest stored session included, end
 * 1 ms before the first session sent at its current time. Ending where it starts, an event of
 * the same app and activity would have that session merged into it by the datastore, and the
 * next run, taking the whole event for one in the stored times, would move that session again.
 */
internal fun sessionsToSend(
    history: List<UsageEvent>,
    stored: List<StoredSession>,
    shift: Long?,
    now: Long,
    appName: (packageName: String) -> String,
): List<SessionToSend> {
    val newest = stored.firstOrNull()
        ?: return parseForegroundSessions(history, now, appName = appName).map { SessionToSend(it, null) }
    val move = shift ?: 0L
    val resume = newest.start + move
    val sessions = parseForegroundSessions(history.filter { it.timeStamp >= resume }, now, resume, appName)
    // Where sessions are sent at their current times: only a move back puts any before it.
    val currentFrom = if (move < 0L) newest.end + 1 else Long.MIN_VALUE
    val firstAsItIs = sessions.firstOrNull { it.startTime != resume && it.startTime >= currentFrom }?.startTime
    val storedTimesEnd = when {
        firstAsItIs == null -> Long.MAX_VALUE
        move < 0L -> firstAsItIs - 1
        else -> firstAsItIs
    }
    val toSend = mutableListOf<SessionToSend>()
    for (session in sessions) {
        when {
            session.startTime == resume -> {
                // The end stays where Android now has it, like the sessions after it, within the
                // parser's cap measured from the stored start.
                val end = minOf(
                    session.endTime, newest.start + MAX_REASONABLE_SESSION_DURATION - 1, storedTimesEnd,
                )
                // Already stored at least this long. After a move back, its end can even come
                // before its stored start.
                if (end <= newest.end) continue
                toSend += SessionToSend(session.withTimes(newest.start, end), newest.data)
            }
            session.startTime >= currentFrom -> toSend += SessionToSend(session, null)
            else -> {
                // Only after a move back. Moved back by the same amount, these sessions stay end
                // to end; only the first session sent at its current time can overlap them.
                val start = session.startTime - move
                val end = minOf(session.endTime - move, storedTimesEnd, now)
                if (end - start > MIN_SESSION_DURATION) toSend += SessionToSend(session.withTimes(start, end), null)
            }
        }
    }
    return toSend
}

// Built anew, not copied: durationMs, which is what gets sent, is computed at construction.
private fun AppSession.withTimes(startTime: Long, endTime: Long) =
    AppSession(packageName = packageName, appName = appName, className = className, startTime = startTime, endTime = endTime)

/**
 * The unlock (KEYGUARD_HIDDEN) times in [history] to send after [stored], the bucket's newest
 * unlock times (newest first). [shift] is how far Android has moved its history since they were
 * stored, from [findHistoryShift] with [unlockMarks]; null is read as no move.
 *
 * Unlocks are zero-duration and never extended, so only those after the newest stored one are
 * sent, measured from where that one now is in [history]: sending it again would insert a second
 * copy.
 *
 * After a move back, the unlocks that now come at or before the newest stored one's time are
 * sent in the stored unlocks' times, moved back by the move, which are their times before it.
 * Sent as they are, they would come before the newest stored unlock, and the next run, which
 * resumes after that one, would send them again. One that would then come after an unlock sent
 * as it is is not sent, so that the order stays, and one that would come at or after [now] is
 * left for a later run.
 */
internal fun unlocksToSend(history: List<UsageEvent>, stored: List<Long>, shift: Long?, now: Long): List<Long> {
    val unlocks = history.filter { it.eventType == UsageEvents.Event.KEYGUARD_HIDDEN }.map { it.timeStamp }.sorted()
    val newest = stored.firstOrNull() ?: return unlocks
    val move = shift ?: 0L
    val after = unlocks.filter { it > newest + move }
    val firstAsItIs = after.firstOrNull { it > newest }
    return after.mapNotNull { time ->
        if (time > newest) {
            time
        } else {
            (time - move).takeIf { it < now && (firstAsItIs == null || it < firstAsItIs) }
        }
    }
}

/**
 * Where to start reading Android's history: early enough to find the stored session starts and
 * unlock times again after the largest move looked for, or at its beginning while either bucket
 * is still empty. Stored events more than [STORED_EVENTS_SPAN_MS] before their bucket's newest
 * one may then fall before the read, and confirm no move.
 */
internal fun historyReadStart(storedSessionStarts: List<Long>, storedUnlocks: List<Long>): Long {
    if (storedSessionStarts.isEmpty() || storedUnlocks.isEmpty()) return 0L
    fun earliestRead(times: List<Long>) = maxOf(times.min(), times.max() - STORED_EVENTS_SPAN_MS)
    return (minOf(earliestRead(storedSessionStarts), earliestRead(storedUnlocks)) - MAX_HISTORY_SHIFT_MS)
        .coerceAtLeast(0L)
}
