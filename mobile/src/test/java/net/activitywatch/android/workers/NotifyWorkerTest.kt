package net.activitywatch.android.workers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun parseAlerts_parsesAggregateAlert() {
        val json = """[{"category":null,"label":"All","thresholdMinutes":[60,120,240],"positive":false}]"""
        val alerts = parseAlerts(json)
        assertEquals(1, alerts.size)
        assertNull(alerts[0].category)
        assertEquals("All", alerts[0].label)
        assertEquals(listOf(60, 120, 240), alerts[0].thresholdMinutes)
        assertEquals(false, alerts[0].positive)
    }

    @Test
    fun parseAlerts_parsesCategoryAlertWithPositive() {
        val json = """[{"category":"Work","label":"Work","thresholdMinutes":[15,30,60],"positive":true}]"""
        val alerts = parseAlerts(json)
        assertEquals(1, alerts.size)
        assertEquals("Work", alerts[0].category)
        assertEquals(true, alerts[0].positive)
    }

    @Test
    fun parseAlerts_returnsEmptyForEmptyArray() {
        assertEquals(0, parseAlerts("[]").size)
    }

    @Test
    fun parseAlerts_parsesMultipleAlerts() {
        val json = """[
            {"category":null,"label":"All","thresholdMinutes":[60],"positive":false},
            {"category":"Work","label":"Work","thresholdMinutes":[30],"positive":true}
        ]"""
        assertEquals(2, parseAlerts(json).size)
    }
}
