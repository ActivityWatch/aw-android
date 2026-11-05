package net.activitywatch.android

import android.app.Application
import android.content.SharedPreferences
import net.activitywatch.android.watcher.PauseIntervalStore
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LiveCollectionPauseTest {
    @Test
    fun pauseDefaultsToOffAndSurvivesNewPreferencesInstance() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = AWPreferences(context)
        assertFalse(prefs.isLiveCollectionPaused())
        prefs.setLiveCollectionPaused(true)
        assertTrue(AWPreferences(context).isLiveCollectionPaused())
        prefs.setLiveCollectionPaused(false)
        assertFalse(AWPreferences(context).isLiveCollectionPaused())
    }

    @Test
    fun backwardsClockResumeDoesNotEraseThePrivacyBoundaryOrReportSuccess() {
        val context = RuntimeEnvironment.getApplication()
        // Robolectric refuses backwards setCurrentTimeMillis calls. Persist a future
        // pause boundary to model the file restored after a wall-clock correction.
        val pauseStart = System.currentTimeMillis() + 60_000L
        assertTrue(PauseIntervalStore(context).recordPause(pauseStart))
        val prefs = AWPreferences(context)
        assertTrue(prefs.isLiveCollectionPaused())
        val generation = prefs.liveCollectionGeneration()
        assertFalse(prefs.setLiveCollectionPaused(false))
        assertTrue(AWPreferences(context).isLiveCollectionPaused())
        assertEquals(generation, prefs.liveCollectionGeneration())
        val intervals = PauseIntervalStore(context).loadOrMigrate(true, 550_000L)
        assertEquals(0L, intervals.single().startMs)
        assertNull(intervals.single().endMs)
        assertTrue(net.activitywatch.android.watcher.PauseIntervals.isExcluded(intervals, pauseStart + 5_000L))
    }

    @Test
    fun corruptHistoryFailsClosedWithoutOverwritingPrivacyEvidence() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.filesDir, PauseIntervalStore.FILE_NAME)
        val corruptions = listOf(
            "{",
            "{\"version\":99,\"intervals\":[]}",
            "{\"version\":1}",
            "{\"version\":1,\"intervals\":[{\"startMs\":-1}]}",
            "{\"version\":1,\"intervals\":[{\"startMs\":100,\"endMs\":50}]}"
        )
        for (contents in corruptions) {
            file.writeText(contents)
            val store = PauseIntervalStore(context)
            assertTrue("Corruption must not authorize live collection: $contents", store.isPaused(false))
            val pauses = store.loadOrMigrate(false, 30_000L)
            assertTrue(net.activitywatch.android.watcher.PauseIntervals.allowedWindows(pauses, 0L, 30_000L).isEmpty())
            assertFalse(store.recordPause(31_000L))
            assertFalse(store.recordResume(32_000L))
            store.prune(40_000L)
            assertEquals(contents, file.readText())
        }
    }

    @Test
    fun rollbackExpandsAndPersistsTheExclusionBeforeHistoryImport() {
        val context = RuntimeEnvironment.getApplication()
        val store = PauseIntervalStore(context)
        assertTrue(store.recordPause(600_000L))
        val pauses = store.loadOrMigrate(false, 550_000L)
        assertTrue(net.activitywatch.android.watcher.PauseIntervals.allowedWindows(pauses, 0L, 550_000L).isEmpty())
        assertTrue(net.activitywatch.android.watcher.PauseIntervals.isExcluded(pauses, 540_000L))
        // Preserve the original resume refusal floor even though exclusion now starts at epoch.
        assertFalse(PauseIntervalStore(context).recordResume(560_000L))
        // Once detected, catching up and resuming must not expose rolled-back timestamps.
        assertTrue(store.recordResume(700_000L))
        val persisted = PauseIntervalStore(context).loadOrMigrate(false, 710_000L)
        assertTrue(net.activitywatch.android.watcher.PauseIntervals.isExcluded(persisted, 540_000L))
        assertFalse(net.activitywatch.android.watcher.PauseIntervals.isExcluded(persisted, 700_000L))
    }

    @Test
    fun stalePausedFlagCannotReopenACompletedPause() {
        val context = RuntimeEnvironment.getApplication()
        val store = PauseIntervalStore(context)
        assertTrue(store.recordPause(10_000L))
        assertTrue(store.recordResume(20_000L))
        // An importer sampled the preference before resume finished, then acquired the
        // file lock after recordResume. Existing interval history is authoritative.
        val intervals = store.loadOrMigrate(flagPaused = true, nowMs = 21_000L)
        assertEquals(1, intervals.size)
        assertEquals(20_000L, intervals.single().endMs)
        assertEquals(intervals, PauseIntervalStore(context).loadOrMigrate(false, 22_000L))
    }

    @Test
    fun intervalHistoryRecoversCollectionStateAfterInterruptedPreferenceWrite() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences(AWPreferences.PREFERENCES_NAME, android.content.Context.MODE_PRIVATE)
        val store = PauseIntervalStore(context)
        assertTrue(store.recordPause(10_000L))
        // Process death between the interval rename and preference write on pause.
        assertFalse(preferences.getBoolean(AWPreferences.LIVE_COLLECTION_PAUSED, false))
        assertTrue(AWPreferences(context).isLiveCollectionPaused())
        preferences.edit().putBoolean(AWPreferences.LIVE_COLLECTION_PAUSED, true).commit()
        assertTrue(store.recordResume(20_000L))
        // The reverse crash window on resume must not leave the UI/live gate paused
        // while history is already importing again.
        assertTrue(preferences.getBoolean(AWPreferences.LIVE_COLLECTION_PAUSED, false))
        assertFalse(AWPreferences(context).isLiveCollectionPaused())
    }

    @Test
    fun failedPauseDoesNotReportSuccessOrNotifyCollectors() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = AWPreferences(context)
        val generation = prefs.liveCollectionGeneration()
        val tmp = File(context.filesDir, PauseIntervalStore.FILE_NAME + ".tmp")
        assertTrue(tmp.mkdir()) // deterministic write failure, including when running as root
        try {
            assertEquals(false, prefs.setLiveCollectionPaused(true))
            assertFalse(prefs.isLiveCollectionPaused())
            assertEquals(generation, prefs.liveCollectionGeneration())
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun migrationOnlyCreatesAnIntervalForAnOldPausedFlag() {
        val context = RuntimeEnvironment.getApplication()
        val store = PauseIntervalStore(context)
        assertTrue(store.loadOrMigrate(false, 9_000L).isEmpty())
        val intervals = store.loadOrMigrate(true, 10_000L)
        assertEquals(10_000L, intervals.single().startMs)
        assertNull(intervals.single().endMs)
        assertEquals(intervals, PauseIntervalStore(context).loadOrMigrate(true, 11_000L))
    }

    @Test
    fun failedResumeKeepsTheOpenIntervalAndPausedCollectors() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = AWPreferences(context)
        assertTrue(prefs.setLiveCollectionPaused(true))
        val generation = prefs.liveCollectionGeneration()
        val store = PauseIntervalStore(context)
        val intervals = store.loadOrMigrate(true, System.currentTimeMillis())
        val tmp = File(context.filesDir, PauseIntervalStore.FILE_NAME + ".tmp")
        assertTrue(tmp.mkdir())
        try {
            assertFalse(prefs.setLiveCollectionPaused(false))
            assertTrue(prefs.isLiveCollectionPaused())
            assertEquals(generation, prefs.liveCollectionGeneration())
            assertEquals(intervals, store.loadOrMigrate(true, System.currentTimeMillis()))
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun observersSeePauseAndResumeAndCanUnregister() {
        val prefs = AWPreferences(RuntimeEnvironment.getApplication())
        val observed = mutableListOf<Boolean>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == AWPreferences.LIVE_COLLECTION_PAUSED) observed += prefs.isLiveCollectionPaused()
        }
        prefs.registerListener(listener)
        prefs.setLiveCollectionPaused(true)
        prefs.setLiveCollectionPaused(false)
        prefs.unregisterListener(listener)
        prefs.setLiveCollectionPaused(true)
        assertEquals(listOf(true, false), observed)
    }
}
