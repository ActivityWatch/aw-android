package net.activitywatch.android.watcher

import org.json.JSONObject
import org.threeten.bp.Duration
import org.threeten.bp.Instant

/** How far along a call looked when one of its notifications was seen. */
internal enum class CallPhase {
    /** Incoming call that has not been answered yet. */
    RINGING,

    /** The dialer shows a running call timer, so the other side picked up. */
    ACTIVE,

    /** A call notification with no further hint (for example "Calling..."). */
    UNKNOWN,
}

/**
 * Dialers often remove the "incoming call" notification and post an "ongoing call" one a
 * moment later. A notification for the same app that appears within this window continues
 * the same call instead of starting a new one.
 */
internal const val CALL_GRACE_MILLIS = 2_000L

/** Upper bound for one call, so a notification that is never removed cannot run forever. */
internal val CALL_MAX_LENGTH: Duration = Duration.ofHours(6)

/**
 * Turns call-notification observations into one event per call: it starts when the first
 * call notification of an app appears and ends when the last one is dismissed.
 *
 * Only the app, the direction and the timing are recorded. Notification text (contact name
 * or number) is never read.
 *
 * Not thread-safe; callers serialize access. Time is passed in, so nothing here sleeps:
 * the caller schedules [flushExpired] after a removal.
 */
internal class CallEventTracker(
    private val graceMillis: Long = CALL_GRACE_MILLIS,
    private val emit: (start: Instant, durationSeconds: Double, data: JSONObject) -> Unit,
) {
    private class OpenCall(
        val packageName: String,
        val appName: String,
        val start: Instant,
        val direction: String,
    ) {
        /** Notification keys of this call that are still showing. */
        val keys = HashSet<String>()
        var connectedAt: Instant? = null

        /** Set while [keys] is empty: when the last notification went away. */
        var closedAt: Instant? = null
    }

    // One call per app at a time.
    private val open = HashMap<String, OpenCall>()

    fun onPosted(
        key: String,
        packageName: String,
        appName: String,
        phase: CallPhase,
        postedAt: Instant,
        now: Instant,
    ) {
        var call = open[packageName]

        if (call != null) {
            val closedAt = call.closedAt
            val goneForGood = closedAt != null &&
                    Duration.between(closedAt, now).toMillis() > graceMillis
            val stuck = Duration.between(call.start, now) > CALL_MAX_LENGTH
            if (goneForGood || stuck) {
                // The earlier call is over: write it before starting this one.
                finish(call, closedAt ?: now)
                open.remove(packageName)
                call = null
            }
        }

        if (call == null) {
            val direction = when (phase) {
                CallPhase.RINGING -> "incoming"
                CallPhase.UNKNOWN -> "outgoing"
                // We only saw the call once it was already running (listener started mid-call).
                CallPhase.ACTIVE -> "unknown"
            }
            val start = if (postedAt.isBefore(now)) postedAt else now
            call = OpenCall(packageName, appName, start, direction)
            open[packageName] = call
        }

        call.keys += key
        call.closedAt = null
        if (phase == CallPhase.ACTIVE && call.connectedAt == null) {
            call.connectedAt = now
        }
    }

    /**
     * Returns true when [key] belonged to a tracked call, so the caller knows to schedule
     * [flushExpired]. Notifications this class never saw are ignored.
     */
    fun onRemoved(key: String, packageName: String, now: Instant): Boolean {
        val call = open[packageName] ?: return false
        if (!call.keys.remove(key)) return false
        if (call.keys.isEmpty()) call.closedAt = now
        return true
    }

    /** Writes the calls whose last notification went away more than the grace window ago. */
    fun flushExpired(now: Instant) {
        val iterator = open.values.iterator()
        while (iterator.hasNext()) {
            val call = iterator.next()
            val closedAt = call.closedAt ?: continue
            if (Duration.between(closedAt, now).toMillis() >= graceMillis) {
                iterator.remove()
                finish(call, closedAt)
            }
        }
    }

    /** Writes every open call, for example when the listener disconnects. */
    fun endAll(now: Instant) {
        for (call in open.values) finish(call, call.closedAt ?: now)
        open.clear()
    }

    private fun finish(call: OpenCall, endAt: Instant) {
        val longest = call.start.plus(CALL_MAX_LENGTH)
        val end = if (endAt.isAfter(longest)) longest else endAt
        val millis = Duration.between(call.start, end).toMillis()
        if (millis <= 0) return

        val data = JSONObject()
        data.put("app", call.appName)
        data.put("package", call.packageName)
        data.put("direction", call.direction)
        val connectedAt = call.connectedAt

        if (connectedAt != null) {
            data.put("connected", true)
            val talkMillis = Duration.between(connectedAt, end).toMillis().coerceAtLeast(0L)
            data.put("talk_seconds", talkMillis / 1000.0)
        }
        emit(call.start, millis / 1000.0, data)
    }
}