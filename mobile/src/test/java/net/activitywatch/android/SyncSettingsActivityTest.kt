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
    fun formatNextSyncStatus_reportsDisabledWhenSyncOff() {
        assertEquals(
            "Next sync: sync is disabled",
            formatNextSyncStatus(enabled = false, lastStatus = null, dateFormat = dateFormat),
        )
    }

    @Test
    fun formatNextSyncStatus_reportsShortlyBeforeFirstSync() {
        assertEquals(
            "Next sync: shortly (first sync runs about a minute after ActivityWatch starts)",
            formatNextSyncStatus(enabled = true, lastStatus = null, dateFormat = dateFormat),
        )
    }

    @Test
    fun formatNextSyncStatus_addsIntervalToLastCompletedRun() {
        // 2026-09-01 01:30:00 UTC + 15 minutes = 2026-09-01 01:45:00 UTC
        val completedAt = 1_788_226_200_000L
        assertEquals(
            "Next sync: 2026-09-01 01:45",
            formatNextSyncStatus(
                enabled = true,
                lastStatus = SyncStatus(completedAt = completedAt, success = true),
                dateFormat = dateFormat,
                now = completedAt,
            ),
        )
    }

    @Test
    fun formatNextSyncStatus_reportsDueNowWhenIntervalHasElapsed() {
        val completedAt = 1_788_226_200_000L
        val wellPastInterval = completedAt + 60 * 60 * 1000L
        assertEquals(
            "Next sync: due now",
            formatNextSyncStatus(
                enabled = true,
                lastStatus = SyncStatus(completedAt = completedAt, success = true),
                dateFormat = dateFormat,
                now = wellPastInterval,
            ),
        )
    }

    @Test
    fun formatNextSyncStatus_usesSchedulerNextRunAtOverComputedInterval() {
        // After a service restart, scheduler schedules first run at +60s, not lastCompletedAt+15min.
        // The UI must show the scheduler-registered time, not the computed one.
        val completedAt = 1_788_226_200_000L       // 2026-09-01 01:30:00 UTC
        val now = completedAt + 5 * 1000L           // 5s after last sync
        val schedulerNextRunAt = now + 60 * 1000L   // scheduler registered +60s from restart
        assertEquals(
            "Next sync: 2026-09-01 01:31",
            formatNextSyncStatus(
                enabled = true,
                lastStatus = SyncStatus(completedAt = completedAt, success = true),
                dateFormat = dateFormat,
                now = now,
                schedulerNextRunAt = schedulerNextRunAt,
            ),
        )
    }

    @Test
    fun normalizeError_capsLength() {
        val raw = "x".repeat(SyncStatus.MAX_ERROR_CHARS + 50)
        val normalized = SyncStatus.normalizeError(raw)
        assertEquals(SyncStatus.MAX_ERROR_CHARS, normalized!!.length)
    }

    @Test
    fun formatSyncStatus_omitsCountsWhenPayloadCarriedNoReport() {
        // Pre-SyncReport payloads must render exactly as they did before.
        assertEquals(
            "Last sync succeeded at 2026-09-01 01:30",
            formatSyncStatus(
                SyncStatus(completedAt = 1_788_226_200_000L, success = true, hasReport = false),
                dateFormat,
            ),
        )
    }

    @Test
    fun formatSyncStatus_showsThatASuccessfulPassMovedNothing() {
        // The whole point of #274: a no-op success must not read as "succeeded".
        assertEquals(
            "Last sync succeeded at 2026-09-01 01:30\npulled 0, pushed 0",
            formatSyncStatus(
                SyncStatus(completedAt = 1_788_226_200_000L, success = true, hasReport = true),
                dateFormat,
            ),
        )
    }

    @Test
    fun formatSyncStatus_reportsCountsAndPeerOutcomes() {
        assertEquals(
            "Last sync succeeded at 2026-09-01 01:30\n" +
                "pulled 1200, pushed 3 · peers 2/4 imported, 1 skipped, 1 failed",
            formatSyncStatus(
                SyncStatus(
                    completedAt = 1_788_226_200_000L,
                    success = true,
                    hasReport = true,
                    eventsPulled = 1200,
                    eventsPushed = 3,
                    peersImported = 2,
                    peersSkipped = 1,
                    peersFailed = 1,
                ),
                dateFormat,
            ),
        )
    }

    @Test
    fun formatSyncStatus_listsWarningsBelowTheCounts() {
        assertEquals(
            "Last sync succeeded at 2026-09-01 01:30\n" +
                "pulled 0, pushed 0\n" +
                "no readable peers in sync folder\n" +
                "push aborted after pull failure",
            formatSyncStatus(
                SyncStatus(
                    completedAt = 1_788_226_200_000L,
                    success = true,
                    hasReport = true,
                    warnings = listOf(
                        "no readable peers in sync folder",
                        "push aborted after pull failure",
                    ),
                ),
                dateFormat,
            ),
        )
    }

    @Test
    fun formatSyncStatus_showsCountsBesideAFailure() {
        assertEquals(
            "Last sync failed at 2026-09-01 01:30: push failed: no such host\n" +
                "pulled 12, pushed 0 · peers 1/2 imported, 1 failed",
            formatSyncStatus(
                SyncStatus(
                    completedAt = 1_788_226_200_000L,
                    success = false,
                    error = "push failed: no such host",
                    hasReport = true,
                    eventsPulled = 12,
                    peersImported = 1,
                    peersFailed = 1,
                ),
                dateFormat,
            ),
        )
    }

    @Test
    fun fromJniResponse_parsesSyncReport() {
        val status = SyncStatus.fromJniResponse(
            """
            {
              "success": true,
              "message": "Synced 1200 events in from 2/4 peers (1 skipped, 1 failed)",
              "events_pulled": 1200,
              "events_pushed": 3,
              "peers_imported": 2,
              "peers_skipped": 1,
              "peers_failed": 1,
              "warnings": ["push aborted after pull failure"]
            }
            """.trimIndent(),
            completedAt = 1_788_226_200_000L,
        )

        assertEquals(true, status.success)
        assertEquals(true, status.hasReport)
        assertEquals(1200, status.eventsPulled)
        assertEquals(3, status.eventsPushed)
        assertEquals(2, status.peersImported)
        assertEquals(1, status.peersSkipped)
        assertEquals(1, status.peersFailed)
        assertEquals(listOf("push aborted after pull failure"), status.warnings)
        assertEquals(null, status.error)
    }

    @Test
    fun fromJniResponse_acceptsPayloadWithoutReportFields() {
        val status = SyncStatus.fromJniResponse(
            """{"success": true, "message": "Successfully pulled from all hosts"}""",
            completedAt = 1_788_226_200_000L,
        )

        assertEquals(true, status.success)
        assertEquals(false, status.hasReport)
        assertEquals(0, status.eventsPulled)
    }

    @Test
    fun fromJniResponse_treatsErrorPayloadAsFailure() {
        val status = SyncStatus.fromJniResponse(
            """{"success": false, "error": "Sync pull failed: connection refused"}""",
            completedAt = 1_788_226_200_000L,
        )

        assertEquals(false, status.success)
        assertEquals("Sync pull failed: connection refused", status.error)
        assertEquals(false, status.hasReport)
    }

    @Test
    fun fromJniResponse_neverThrowsOnUnreadableResponse() {
        val status = SyncStatus.fromJniResponse(
            "not json at all",
            completedAt = 1_788_226_200_000L,
        )

        assertEquals(false, status.success)
        assertEquals(true, status.error!!.startsWith("Unreadable sync response:"))
    }

    @Test
    fun fromJniResponse_fallsBackToGenericErrorWhenReasonMissing() {
        val status = SyncStatus.fromJniResponse(
            """{"success": false}""",
            completedAt = 1_788_226_200_000L,
        )

        assertEquals("sync failed", status.error)
    }

    @Test
    fun fromJniResponse_capsAndNormalizesWarnings() {
        val warnings = "\"first\\n  warning\"," +
            (2..6).joinToString(",") { "\"warning $it\"" }
        val status = SyncStatus.fromJniResponse(
            """{"success": true, "events_pulled": 0, "warnings": [$warnings]}""",
            completedAt = 1_788_226_200_000L,
        )

        assertEquals(SyncStatus.MAX_WARNINGS, status.warnings.size)
        assertEquals("first warning", status.warnings[0])
    }

    @Test
    fun fromJniResponse_ignoresNegativeCounts() {
        val status = SyncStatus.fromJniResponse(
            """{"success": true, "events_pulled": -5, "events_pushed": 2}""",
            completedAt = 1_788_226_200_000L,
        )

        assertEquals(0, status.eventsPulled)
        assertEquals(2, status.eventsPushed)
    }
}
