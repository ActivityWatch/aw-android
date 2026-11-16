package net.activitywatch.android.utils

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SafeIntentsTest {
    private lateinit var app: Application

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        // Throw ActivityNotFoundException for intents nothing resolves, like a real device.
        shadowOf(app).checkActivities(true)
    }

    @Test
    fun returnsFalseWhenNothingHandlesTheIntent() {
        val browse = Intent(Intent.ACTION_VIEW, Uri.parse("https://docs.activitywatch.net"))

        assertFalse(app.startActivitySafely(browse, settingsHomeIntent()))
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun fallsBackToTheFirstHandledIntent() {
        val component = ComponentName("com.android.settings", "com.android.settings.Settings")
        shadowOf(app.packageManager).addActivityIfNotPresent(component)
        shadowOf(app.packageManager).addIntentFilterForActivity(
            component,
            IntentFilter(Settings.ACTION_SETTINGS).apply { addCategory(Intent.CATEGORY_DEFAULT) },
        )

        val missing = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        assertTrue(app.startActivitySafely(missing, settingsHomeIntent()))
        assertEquals(Settings.ACTION_SETTINGS, shadowOf(app).nextStartedActivity.action)
    }
}
