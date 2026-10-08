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
    fun alarmSkipsWhileTheHandlerChainIsOnSchedule() {
        // The Handler syncs at minute 1 and then every 15 minutes; the alarm first fires
        // near minute 15, when that sync is about 14 minutes old.
        val minute = 60_000L
        val handlerSync = now + 1 * minute
        assertTrue(alarmSyncIsRedundant(handlerSync, now + 15 * minute))
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
