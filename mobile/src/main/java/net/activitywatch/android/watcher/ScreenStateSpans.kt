package net.activitywatch.android.watcher

import android.app.usage.UsageEvents
import net.activitywatch.android.data.UsageEvent

/** Whether the screen is interactive and whether the keyguard (lock screen) is shown. */
internal data class ScreenState(val interactive: Boolean, val keyguardShown: Boolean)

/** A stretch of time, in epoch milliseconds, during which [state] held throughout. */
internal data class ScreenStateSpan(val start: Long, val end: Long, val state: ScreenState)

internal data class ScreenStateParse(
    val spans: List<ScreenStateSpan>,
    /**
     * Startups that follow another startup with no shutdown between them, from the end of
     * the stored span on.
     */
    val startupsWithoutShutdown: List<Long>,
)

/** The usage event types the screen and keyguard states are built from. */
internal val SCREEN_STATE_EVENT_TYPES = setOf(
    UsageEvents.Event.SCREEN_INTERACTIVE,
    UsageEvents.Event.SCREEN_NON_INTERACTIVE,
    UsageEvents.Event.KEYGUARD_SHOWN,
    UsageEvents.Event.KEYGUARD_HIDDEN,
    UsageEvents.Event.DEVICE_SHUTDOWN,
    UsageEvents.Event.DEVICE_STARTUP,
)

/**
 * Turns screen and keyguard events into spans of constant state.
 *
 * The two states are tracked separately and a span ends when either changes, so the keyguard
 * being shown or hidden while the screen is off is a change too. A span exists only while
 * both states are known from an event: a shutdown makes both unknown, and the events
 * recorded at the next startup establish them again. The events of one millisecond are
 * applied together, in the order given, and only the state after the last of them counts:
 * Android records several at once, at a startup for instance, and the states they pass
 * through on the way last no time.
 *
 * [stored] is the last span already stored, and [events] are read from its start. The state
 * resumes from it, so that span comes out again with the same start and state and only a
 * later end: the server's heartbeat merge, which needs identical data and a start within the
 * stored event, then extends that event instead of adding a second one. Its start and state
 * are taken from [stored], not from the events read again: Android shifts the times of the
 * events it has saved when the clock is corrected, and a start moved before the stored one
 * would not merge.
 *
 * Before the end of [stored], the events read again count only by the state they lead to.
 * Whenever they come back to the stored state, what they went through was either counted
 * already or moved there by a clock correction, which can bring a change from just before the
 * stored span into it; only changes after the last return are new, from events Android added
 * after the read that stored the span. Such a change is placed at the end of [stored], so no
 * span starts inside it and spans never overlap.
 *
 * Android records a shutdown before every startup, dated at the last moment it saved, even
 * after a crash. The read that stored [stored] had seen every startup before its end,
 * including one recorded with the events that began it. So if the first shutdown or startup
 * from that end on is a startup, its shutdown is dated before the end: [stored] no longer
 * holds, its span is left as stored, and the state starts unknown at that end. The events
 * before it are not applied again, as they would bring the discarded state back.
 *
 * The span still open after the last event ends at [now].
 */
internal fun parseScreenStateSpans(
    events: List<UsageEvent>,
    stored: ScreenStateSpan?,
    now: Long,
): ScreenStateParse {
    // sortedBy is stable: events of the same millisecond keep the order Android gave them.
    val sorted = events.sortedBy { it.timeStamp }
    val resumed = stored?.takeIf { span ->
        val firstFromEnd = sorted.firstOrNull {
            it.timeStamp >= span.end && (it.eventType == UsageEvents.Event.DEVICE_SHUTDOWN ||
                it.eventType == UsageEvents.Event.DEVICE_STARTUP)
        }
        firstFromEnd?.eventType != UsageEvents.Event.DEVICE_STARTUP
    }

    val spans = mutableListOf<ScreenStateSpan>()
    val startupsWithoutShutdown = mutableListOf<Long>()
    var interactive = resumed?.state?.interactive
    var keyguardShown = resumed?.state?.keyguardShown
    var open = resumed?.state
    var openStart = resumed?.start ?: 0L
    // The read that stored the last span had seen everything before its end, apart from events
    // Android added later.
    val storedEnd = stored?.end ?: Long.MIN_VALUE
    var lastShutdownOrStartup: Int? = null

    fun moveTo(state: ScreenState?, at: Long) {
        if (state == open) return
        open?.let { if (at > openStart) spans.add(ScreenStateSpan(openStart, at, it)) }
        open = state
        openStart = at
    }

    val applied = if (stored != null && resumed == null) {
        sorted.filter { it.timeStamp >= stored.end }
    } else {
        sorted
    }
    for ((at, sameMillisecond) in applied.groupBy { it.timeStamp }) {
        for (event in sameMillisecond) {
            when (event.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE -> interactive = true
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> interactive = false
                UsageEvents.Event.KEYGUARD_SHOWN -> keyguardShown = true
                UsageEvents.Event.KEYGUARD_HIDDEN -> keyguardShown = false
                UsageEvents.Event.DEVICE_SHUTDOWN -> {
                    interactive = null
                    keyguardShown = null
                    if (event.timeStamp >= storedEnd) lastShutdownOrStartup = event.eventType
                }
                // The startup itself changes nothing: the screen and keyguard events
                // recorded with it, just before it, set the state.
                UsageEvents.Event.DEVICE_STARTUP -> if (event.timeStamp >= storedEnd) {
                    if (lastShutdownOrStartup == UsageEvents.Event.DEVICE_STARTUP) {
                        startupsWithoutShutdown.add(event.timeStamp)
                    }
                    lastShutdownOrStartup = event.eventType
                }
            }
        }
        val i = interactive
        val k = keyguardShown
        val state = if (i != null && k != null) ScreenState(i, k) else null
        if (resumed != null && at < resumed.end && state == resumed.state) {
            spans.clear()
            open = resumed.state
            openStart = resumed.start
        } else {
            // The server's merge can only lengthen the stored event: a change dated before its
            // end, from an event added after the read that stored it, is placed at that end, or
            // the two would overlap.
            moveTo(state, maxOf(at, storedEnd))
        }
    }
    moveTo(null, now)

    return ScreenStateParse(spans, startupsWithoutShutdown)
}
