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
