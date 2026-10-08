package net.activitywatch.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncSchedulerTest {
    private val now = 1_000_000_000L

    @Test
    fun alarmSkipsWhenSchedulerSyncedRecently() {
        assertTrue(alarmSyncIsRedundant(now - 60_000L, now))
    }

    @Test
    fun alarmSyncsWhenLastPassIsStaleOrMissing() {
        assertFalse(alarmSyncIsRedundant(null, now))
        assertFalse(alarmSyncIsRedundant(now - SYNC_INTERVAL_MS, now))
    }

    @Test
    fun alarmSyncsWhenClockMovedBackwards() {
        // A completion time in the future means the wall clock stepped back; don't let
        // that suppress fallback syncs.
        assertFalse(alarmSyncIsRedundant(now + 60_000L, now))
    }
}
