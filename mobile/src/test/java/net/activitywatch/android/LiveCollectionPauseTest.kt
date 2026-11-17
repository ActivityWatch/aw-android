package net.activitywatch.android

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
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
