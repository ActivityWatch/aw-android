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
    fun formatSyncStatus_pushOnlyDevice_suppressesZeroPeersWarning() {
        // aw-server-rust#687 "zero peers" warning is desktop-only; Android is push-only
        // until sync v2, so the warning must be filtered and replaced by a push-only note.
        assertEquals(
            "Last sync succeeded at 2026-09-01 01:30\n" +
                "pulled 0, pushed 0\n" +
                "Push-only on this device (pull arrives with sync v2)",
            formatSyncStatus(
                SyncStatus(
                    completedAt = 1_788_226_200_000L,
                    success = true,
                    hasReport = true,
                    warnings = listOf(
                        "zero peers in a configured sync dir is usually a layout or setup problem",
                    ),
                ),
                dateFormat,
                isPushOnlyDevice = true,
            ),
        )
    }

    @Test
    fun formatSyncStatus_pushOnlyDevice_preservesOtherWarnings() {
        // Non-zero-peers warnings (e.g. "push aborted after pull failure") still surface.
        assertEquals(
            "Last sync succeeded at 2026-09-01 01:30\n" +
                "pulled 0, pushed 0\n" +
                "push aborted after pull failure\n" +
                "Push-only on this device (pull arrives with sync v2)",
            formatSyncStatus(
                SyncStatus(
                    completedAt = 1_788_226_200_000L,
                    success = true,
                    hasReport = true,
                    warnings = listOf(
                        "zero peers in a configured sync dir is usually a layout or setup problem",
                        "push aborted after pull failure",
                    ),
                ),
                dateFormat,
                isPushOnlyDevice = true,
            ),
        )
    }

    @Test
    fun formatSyncStatus_pushOnlyDevice_appendsNoteEvenWhenNeverSynced() {
        assertEquals(
            "Last sync: never\nPush-only on this device (pull arrives with sync v2)",
            formatSyncStatus(null, dateFormat, isPushOnlyDevice = true),
        )
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

    @Test
    fun fromJniResponse_parsesPeersArray() {
        val status = SyncStatus.fromJniResponse(
            """
            {
              "success": true,
              "events_pulled": 10,
              "events_pushed": 0,
              "peers_imported": 2,
              "peers_skipped": 1,
              "peers_failed": 1,
              "peers": [
                {"device_id": "abc", "hostname": "desktop", "path": "/sync/desktop", "outcome": {"kind": "imported"}, "buckets": []},
                {"device_id": "def", "hostname": "laptop", "path": "/sync/laptop", "outcome": {"kind": "imported"}, "buckets": []},
                {"device_id": "ghi", "hostname": "workpc", "path": "/sync/workpc", "outcome": {"kind": "skipped", "reason": "up to date"}, "buckets": []},
                {"device_id": "jkl", "hostname": "server", "path": "/sync/server", "outcome": {"kind": "failed", "error": "read error"}, "buckets": []}
              ]
            }
            """.trimIndent(),
            completedAt = 1_788_226_200_000L,
        )

        assertEquals(4, status.peers.size)
        assertEquals(SyncPeer("desktop", "imported"), status.peers[0])
        assertEquals(SyncPeer("laptop", "imported"), status.peers[1])
        assertEquals(SyncPeer("workpc", "skipped"), status.peers[2])
        assertEquals(SyncPeer("server", "failed"), status.peers[3])
    }

    @Test
    fun fromJniResponse_emptyPeersWhenNoPeersKey() {
        val status = SyncStatus.fromJniResponse(
            """{"success": true, "events_pulled": 0, "peers_imported": 1}""",
            completedAt = 1_788_226_200_000L,
        )

        assertEquals(emptyList<SyncPeer>(), status.peers)
    }

    @Test
    fun formatSyncDetail_appendsImportedAndFailedPeerNames() {
        // Per-peer hostnames appear in parens after the aggregate text.
        // Skipped peers are intentionally omitted (not notable in normal operation).
        // Failed peers are prefixed with "!" to distinguish them without extra prose.
        assertEquals(
            "pulled 10, pushed 0 · peers 2/4 imported, 1 skipped, 1 failed (desktop, laptop, !server)",
            formatSyncDetail(
                SyncStatus(
                    completedAt = 1_788_226_200_000L,
                    success = true,
                    hasReport = true,
                    eventsPulled = 10,
                    peersImported = 2,
                    peersSkipped = 1,
                    peersFailed = 1,
                    peers = listOf(
                        SyncPeer("desktop", "imported"),
                        SyncPeer("laptop", "imported"),
                        SyncPeer("workpc", "skipped"),
                        SyncPeer("server", "failed"),
                    ),
                ),
            ),
        )
    }

    @Test
    fun formatSyncDetail_omitsParensWhenNoPeers() {
        // Older native libs return no peers array; aggregate text stays unchanged.
        assertEquals(
            "pulled 10, pushed 0 · peers 2/3 imported, 1 skipped",
            formatSyncDetail(
                SyncStatus(
                    completedAt = 1_788_226_200_000L,
                    success = true,
                    hasReport = true,
                    eventsPulled = 10,
                    peersImported = 2,
                    peersSkipped = 1,
                ),
            ),
        )
    }

    @Test
    fun encodeDecodePeers_roundTripsHostnamesAndOutcomes() {
        val peers = listOf(
            SyncPeer("desktop", "imported"),
            SyncPeer("laptop", "imported"),
            SyncPeer("workpc", "skipped"),
            SyncPeer("server", "failed"),
        )
        assertEquals(peers, SyncStatus.decodePeers(SyncStatus.encodePeers(peers)))
    }

    @Test
    fun encodePeers_returnsNullForEmpty() {
        assertEquals(null, SyncStatus.encodePeers(emptyList()))
    }

    @Test
    fun decodePeers_emptyOnNullOrBlank() {
        assertEquals(emptyList<SyncPeer>(), SyncStatus.decodePeers(null))
        assertEquals(emptyList<SyncPeer>(), SyncStatus.decodePeers(""))
        assertEquals(emptyList<SyncPeer>(), SyncStatus.decodePeers("   "))
    }

    @Test
    fun decodePeers_neverThrowsOnMalformed() {
        assertEquals(emptyList<SyncPeer>(), SyncStatus.decodePeers("not json"))
        assertEquals(emptyList<SyncPeer>(), SyncStatus.decodePeers("{]"))
    }

    @Test
    fun formatSyncDetail_usesDecodedPeersAfterPrefsRoundTrip() {
        // The settings UI reloads through SharedPreferences, so names must
        // survive encode → decode or the new hostname line never appears.
        val encoded = SyncStatus.encodePeers(
            listOf(
                SyncPeer("desktop", "imported"),
                SyncPeer("laptop", "imported"),
                SyncPeer("workpc", "skipped"),
                SyncPeer("server", "failed"),
            ),
        )
        assertEquals(
            "pulled 10, pushed 0 · peers 2/4 imported, 1 skipped, 1 failed (desktop, laptop, !server)",
            formatSyncDetail(
                SyncStatus(
                    completedAt = 1_788_226_200_000L,
                    success = true,
                    hasReport = true,
                    eventsPulled = 10,
                    peersImported = 2,
                    peersSkipped = 1,
                    peersFailed = 1,
                    peers = SyncStatus.decodePeers(encoded),
                ),
            ),
        )
    }
}
