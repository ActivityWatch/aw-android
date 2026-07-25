package net.activitywatch.android.workers

import org.junit.Assert.assertEquals
import org.junit.Test
import org.threeten.bp.LocalDate
import org.threeten.bp.LocalDateTime

class NotifyWorkerTest {
    @Test
    fun logicalDayDate_usesPreviousDateBeforeConfiguredBoundary() {
        val now = LocalDateTime.of(2026, 7, 24, 3, 59)

        assertEquals(LocalDate.of(2026, 7, 23), logicalDayDate(now, 4))
    }

    @Test
    fun logicalDayDate_usesCurrentDateAtConfiguredBoundary() {
        val now = LocalDateTime.of(2026, 7, 24, 4, 0)

        assertEquals(LocalDate.of(2026, 7, 24), logicalDayDate(now, 4))
    }
}
