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

    @Test
    fun formatSyncStatus_includesJniErrorOnFailure() {
        assertEquals(
            "Last sync failed at 2026-09-01 01:30: Pull phase failed: connection refused",
            formatSyncStatus(
                SyncStatus(
                    completedAt = 1_788_226_200_000L,
                    success = false,
                    error = "Pull phase failed: connection refused",
                ),
                dateFormat,
            ),
        )
    }

    @Test
    fun formatSyncStatus_collapsesWhitespaceInError() {
        assertEquals(
            "Last sync failed at 2026-09-01 01:30: panic in syncBoth: boom",
            formatSyncStatus(
                SyncStatus(
                    completedAt = 1_788_226_200_000L,
                    success = false,
                    error = "panic in syncBoth:\n  boom\n",
                ),
                dateFormat,
            ),
        )
    }

    @Test
    fun normalizeError_capsLength() {
        val raw = "x".repeat(SyncStatus.MAX_ERROR_CHARS + 50)
        val normalized = SyncStatus.normalizeError(raw)
        assertEquals(SyncStatus.MAX_ERROR_CHARS, normalized!!.length)
    }
}
