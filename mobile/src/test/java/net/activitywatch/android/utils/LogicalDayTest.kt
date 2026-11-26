package net.activitywatch.android.utils

import org.junit.Assert.assertEquals
import org.junit.Test
import org.threeten.bp.LocalDate
import org.threeten.bp.LocalTime
import org.threeten.bp.ZoneId

class LogicalDayTest {
    private val stockholm = ZoneId.of("Europe/Stockholm")
    private val fourAm = LocalTime.of(4, 0)

    @Test
    fun springForwardDayStartsAtWallClockStartOfDay() {
        // 2026-03-29: clocks jump 02:00 -> 03:00, between midnight and the 04:00 boundary.
        val (start, _) = logicalDayWindow(LocalDate.of(2026, 3, 29), fourAm, stockholm)
        assertEquals(fourAm, start.toLocalTime())
    }

    @Test
    fun fallBackDayStartsAtWallClockStartOfDay() {
        // 2026-10-25: clocks fall back 03:00 -> 02:00.
        val (start, end) = logicalDayWindow(LocalDate.of(2026, 10, 25), fourAm, stockholm)
        assertEquals(fourAm, start.toLocalTime())
        assertEquals(fourAm, end.toLocalTime())
    }

    @Test
    fun springGapUsesTheResolvedBoundary() {
        val date = LocalDate.of(2026, 3, 29)
        val boundary = LocalTime.of(2, 30)
        assertEquals(date.minusDays(1), logicalDate(date.atTime(3, 10).atZone(stockholm), boundary))
        assertEquals(date, logicalDate(date.atTime(3, 30).atZone(stockholm), boundary))
    }

    @Test
    fun repeatedHourStaysInTheDayThatAlreadyStarted() {
        val date = LocalDate.of(2026, 10, 25)
        val boundary = LocalTime.of(2, 30)
        val now = date.atTime(2, 10).atZone(stockholm).withLaterOffsetAtOverlap()
        assertEquals(date, logicalDate(now, boundary))
        val firstOccurrence = now.withEarlierOffsetAtOverlap()
        assertEquals(date.minusDays(1), logicalDate(firstOccurrence, boundary))
        assertEquals(date, logicalDate(date.atTime(boundary).atZone(stockholm), boundary))
    }

    @Test
    fun adjacentDaysShareTheirBoundaryAcrossDst() {
        for (date in listOf(LocalDate.of(2026, 3, 28), LocalDate.of(2026, 10, 24))) {
            val (_, prevEnd) = logicalDayWindow(date, fourAm, stockholm)
            val (nextStart, _) = logicalDayWindow(date.plusDays(1), fourAm, stockholm)
            assertEquals("gap or overlap after $date", prevEnd.toInstant(), nextStart.toInstant())
        }
    }
}
