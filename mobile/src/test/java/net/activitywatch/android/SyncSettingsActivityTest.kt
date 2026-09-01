package net.activitywatch.android

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class SyncSettingsActivityTest {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    @Test
    fun formatSyncStatus_reportsNeverBeforeFirstAttempt() {
        assertEquals("Last sync: never", formatSyncStatus(null, dateFormat))
    }

    @Test
    fun formatSyncStatus_reportsSuccessfulAttempt() {
        assertEquals(
            "Last sync succeeded at 2026-09-01 01:30",
            formatSyncStatus(
                SyncStatus(
                    completedAt = 1_788_226_200_000L,
                    success = true,
                ),
                dateFormat,
            ),
        )
    }

    @Test
    fun formatSyncStatus_reportsFailedAttempt() {
        assertEquals(
            "Last sync failed at 2026-09-01 01:30",
            formatSyncStatus(
                SyncStatus(
                    completedAt = 1_788_226_200_000L,
                    success = false,
                ),
                dateFormat,
            ),
        )
    }
}
