package net.activitywatch.android.workers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.threeten.bp.LocalDate
import org.threeten.bp.LocalDateTime

class NotifyWorkerTest {
    @Test
    fun parseStartOfDayHour_readsNativeSettingAndFallsBack() {
        assertEquals(6, parseStartOfDayHour("\"06:00\""))
        assertEquals(4, parseStartOfDayHour("null"))
        assertEquals(4, parseStartOfDayHour("\"99:00\""))
    }

    @Test
    fun alertsFromSetting_readsCanonicalSetting() {
        val alerts = alertsFromSetting(
            """{"alerts":[{"category":"Work","label":"Focus","thresholds_minutes":[30],"positive":true}]}"""
        )

        assertEquals(1, alerts.size)
        assertEquals("Work", alerts[0].category)
        assertEquals("Focus", alerts[0].label)
        assertEquals(listOf(30), alerts[0].thresholdMinutes)
        assertEquals(true, alerts[0].positive)
    }

    @Test
    fun alertsFromSetting_mapsCanonicalAllCategoryToAggregate() {
        val alerts = alertsFromSetting(
            """{"alerts":[{"category":"All","label":null,"thresholds_minutes":[60],"positive":false}]}"""
        )

        assertEquals(1, alerts.size)
        assertNull(alerts[0].category)
        assertEquals("All", alerts[0].label)
    }

    @Test
    fun alertsFromSetting_preservesEmptyCanonicalAlerts() {
        assertEquals(emptyList<CategoryAlert>(), alertsFromSetting("""{"alerts":[]}"""))
    }

    @Test
    fun alertsFromSetting_skipsMalformedCanonicalAlertWithoutDroppingValidAlerts() {
        val alerts = alertsFromSetting(
            """{"alerts":[
                {"category":"Work","label":"Focus","thresholds_minutes":[30],"positive":true},
                {"category":"Media","label":"Media","positive":false}
            ]}"""
        )

        assertEquals(1, alerts.size)
        assertEquals("Focus", alerts[0].label)
    }

    @Test
    fun alertsFromSetting_fallsBackWhenAllCanonicalAlertsAreMalformed() {
        val alerts = alertsFromSetting(
            """{"alerts":[{"category":"Work","label":"Focus","positive":true}]}"""
        )

        assertEquals("All", alerts[0].label)
    }

    @Test
    fun alertsFromSetting_rejectsNonPositiveOrFractionalThresholds() {
        val alerts = alertsFromSetting(
            """{"alerts":[
                {"category":"Work","label":"Zero","thresholds_minutes":[0],"positive":true},
                {"category":"Media","label":"Fractional","thresholds_minutes":[1.5],"positive":false}
            ]}"""
        )

        assertEquals("All", alerts[0].label)
    }

    @Test
    fun alertsFromSetting_preservesLegacySettingCompatibility() {
        val alerts = alertsFromSetting(
            """[{"category":"Work","label":"Focus","thresholdMinutes":[30],"positive":true}]"""
        )

        assertEquals(1, alerts.size)
        assertEquals("Focus", alerts[0].label)
        assertEquals(listOf(30), alerts[0].thresholdMinutes)
        assertEquals(true, alerts[0].positive)
    }

    @Test
    fun alertsFromSetting_fallsBackForMissingOrInvalidSetting() {
        assertEquals("All", alertsFromSetting("null")[0].label)
        assertEquals("All", alertsFromSetting("not-json")[0].label)
    }

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

    @Test
    fun alertConfigHash_differsWhenThresholdsChange() {
        val alert60  = CategoryAlert("Work", "Work", listOf(60, 120), false)
        val alert30  = CategoryAlert("Work", "Work", listOf(30, 60),  false)
        val alertPos = CategoryAlert("Work", "Work", listOf(60, 120), true)

        // Same config → stable pref key (no spurious re-fires)
        assertEquals(alertConfigHash(alert60), alertConfigHash(alert60))

        // Different thresholds → different hash → old triggered state ignored
        assert(alertConfigHash(alert60) != alertConfigHash(alert30)) {
            "Changing thresholds must change the pref-key hash to reset daily state"
        }

        // Different positive flag → different hash
        assert(alertConfigHash(alert60) != alertConfigHash(alertPos)) {
            "Changing positive flag must change the pref-key hash"
        }
    }
}
