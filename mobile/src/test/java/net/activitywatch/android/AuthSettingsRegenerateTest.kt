package net.activitywatch.android

import android.app.Application
import android.widget.Button
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File

/** Regenerating a key must re-enable auth everywhere, not only in config.toml. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AuthSettingsRegenerateTest {
    @Test
    fun regenerateWhileDisabledReenablesDashboardAuth() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions("${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        File(context.filesDir, "config.toml").delete()
        AWPreferences(context).setDashboardAuthEnabled(false)

        val controller = Robolectric.buildActivity(AuthSettingsActivity::class.java).setup()
        try {
            val activity = controller.get()
            val apiKeyView = activity.findViewById<TextView>(R.id.tv_api_key)
            activity.findViewById<Button>(R.id.btn_regenerate_key).performClick()
            awaitMain { apiKeyView.text.toString().matches(GENERATED_KEY) }

            val configuredKey = ConfigManager(context).readAuthConfig().apiKey
            assertNotNull("regenerate must write a key to config.toml", configuredKey)
            assertTrue(
                "regenerate must turn the dashboard auth preference back on",
                AWPreferences(context).isDashboardAuthEnabled(),
            )
            // The dashboard must load with the key the server will enforce.
            assertEquals(configuredKey, ensureDashboardApiKey(context))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun awaitMain(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for UI update" }
            Thread.sleep(10)
            ShadowLooper.idleMainLooper()
        }
    }

    private companion object {
        val GENERATED_KEY = Regex("[0-9a-f]{32}")
    }
}
