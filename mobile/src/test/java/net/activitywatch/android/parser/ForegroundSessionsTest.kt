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
}
