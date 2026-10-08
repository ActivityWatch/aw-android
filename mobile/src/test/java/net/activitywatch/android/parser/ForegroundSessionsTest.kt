package net.activitywatch.android.parser

import android.app.usage.UsageEvents
import net.activitywatch.android.data.UsageEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundSessionsTest {
    private val hour = 60 * 60 * 1000L

    private fun event(type: Int, time: Long, pkg: String = "") = UsageEvent(type, time, pkg, "")

    private fun parse(vararg events: UsageEvent) =
        parseForegroundSessions(events.toList()) { it }.map { Triple(it.packageName, it.startTime, it.endTime) }

    @Test
    fun keepsSessionLongerThanFourHours() {
        val sessions = parse(
            event(UsageEvents.Event.ACTIVITY_RESUMED, 0, "maps"),
            event(UsageEvents.Event.ACTIVITY_PAUSED, 5 * hour, "maps"),
        )
        assertEquals(listOf(Triple("maps", 0L, 5 * hour)), sessions)
    }

    @Test
    fun screenOffEndsSessionWhosePauseNeverArrives() {
        val sessions = parse(
            event(UsageEvents.Event.ACTIVITY_RESUMED, 0, "reader"),
            event(UsageEvents.Event.SCREEN_NON_INTERACTIVE, 10 * 60 * 1000L),
            // Next morning: a different app resumes with no PAUSE recorded for "reader".
            event(UsageEvents.Event.ACTIVITY_RESUMED, 9 * hour, "mail"),
            event(UsageEvents.Event.ACTIVITY_PAUSED, 9 * hour + 60_000L, "mail"),
        )
        assertEquals(
            listOf(
                Triple("reader", 0L, 10 * 60 * 1000L),
                Triple("mail", 9 * hour, 9 * hour + 60_000L),
            ),
            sessions,
        )
    }

    @Test
    fun sameAppResumingAgainKeepsTheOriginalStart() {
        val sessions = parse(
            event(UsageEvents.Event.ACTIVITY_RESUMED, 0, "chat"),
            event(UsageEvents.Event.ACTIVITY_RESUMED, 30_000L, "chat"),
            event(UsageEvents.Event.ACTIVITY_PAUSED, 60_000L, "chat"),
        )
        assertEquals(listOf(Triple("chat", 0L, 60_000L)), sessions)
    }

    @Test
    fun anotherAppResumingEndsTheSessionWithoutAPause() {
        val sessions = parse(
            event(UsageEvents.Event.ACTIVITY_RESUMED, 0, "chat"),
            event(UsageEvents.Event.ACTIVITY_RESUMED, 60_000L, "mail"),
            event(UsageEvents.Event.ACTIVITY_PAUSED, 90_000L, "mail"),
            // chat's PAUSE arrives late; it must not end or extend anything.
            event(UsageEvents.Event.ACTIVITY_PAUSED, 95_000L, "chat"),
        )
        assertEquals(
            listOf(Triple("chat", 0L, 60_000L), Triple("mail", 60_000L, 90_000L)),
            sessions,
        )
    }

    @Test
    fun stalePauseFromAnotherAppDoesNotEndTheSession() {
        val sessions = parse(
            event(UsageEvents.Event.ACTIVITY_RESUMED, 0, "chat"),
            event(UsageEvents.Event.ACTIVITY_PAUSED, 10_000L, "mail"),
            event(UsageEvents.Event.ACTIVITY_PAUSED, 60_000L, "chat"),
        )
        assertEquals(listOf(Triple("chat", 0L, 60_000L)), sessions)
    }

    @Test
    fun trailingOpenSessionIsNotEmitted() {
        val sessions = parse(
            event(UsageEvents.Event.ACTIVITY_RESUMED, 0, "chat"),
            event(UsageEvents.Event.ACTIVITY_PAUSED, 60_000L, "chat"),
            event(UsageEvents.Event.ACTIVITY_RESUMED, 70_000L, "mail"),
        )
        assertEquals(listOf(Triple("chat", 0L, 60_000L)), sessions)
    }
}
