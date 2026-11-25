package net.activitywatch.android

import android.app.Application
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
            awaitMain {
                activity.findViewById<TextView>(R.id.tv_status).text.toString() ==
                    "Authentication enabled (restart app to apply)"
            }
            assertTrue(apiKeyView.text.toString().matches(GENERATED_KEY))

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

    @Test
    @Config(
        shadows = [BlockingConfigManager::class],
        instrumentedPackages = ["net.activitywatch.android.ConfigManager"],
    )
    fun disablingDuringRegenerationWaitsForSaveAndStaysDisabled() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions("${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        ConfigManager(context).generateAndSetApiKey()
        AWPreferences(context).setDashboardAuthEnabled(true)
        val controller = Robolectric.buildActivity(AuthSettingsActivity::class.java).setup()
        try {
            val activity = controller.get()
            val authSwitch = activity.findViewById<SwitchCompat>(R.id.switch_auth_enabled)
            awaitMain { authSwitch.isChecked }
            BlockingConfigManager.started = CountDownLatch(1)
            BlockingConfigManager.release = CountDownLatch(1)
            BlockingConfigManager.cleared = CountDownLatch(1)
            activity.findViewById<Button>(R.id.btn_regenerate_key).performClick()
            // Poll while yielding to the looper: a blocking await on the main thread
            // deadlocks when a queued refresh still holds the action mutex.
            awaitMain { BlockingConfigManager.started.count == 0L }
            authSwitch.isChecked = false
            ShadowLooper.idleMainLooper()
            assertFalse(
                "disable must not clear the key while regeneration is still saving",
                BlockingConfigManager.cleared.await(200, TimeUnit.MILLISECONDS),
            )
            BlockingConfigManager.release.countDown()
            awaitMain {
                activity.findViewById<TextView>(R.id.tv_status).text.toString() ==
                    "Authentication disabled (restart app to apply)"
            }
            assertFalse(authSwitch.isChecked)
            assertFalse(AWPreferences(context).isDashboardAuthEnabled())
            assertFalse(ConfigManager(context).readAuthConfig().isEnabled)
            assertEquals("", ensureDashboardApiKey(context))
        } finally {
            BlockingConfigManager.release.countDown()
            controller.pause().stop().destroy()
        }
    }

    @Implements(ConfigManager::class, isInAndroidSdk = false)
    class BlockingConfigManager {
        @RealObject
        private lateinit var real: ConfigManager

        @Implementation
        fun generateAndSetApiKey(): String? {
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "test did not release regeneration" }
            return Shadow.directlyOn(real, ConfigManager::class.java, "generateAndSetApiKey")
        }

        @Implementation
        fun clearApiKey(): Boolean {
            cleared.countDown()
            check(clearRelease.await(5, TimeUnit.SECONDS)) { "test did not release the clear" }
            return Shadow.directlyOn(real, ConfigManager::class.java, "clearApiKey")
        }

        companion object {
            var started = CountDownLatch(0)
            var release = CountDownLatch(0)
            var cleared = CountDownLatch(0)
            var clearRelease = CountDownLatch(0)
        }
    }


    @Test
    @Config(
        shadows = [BlockingConfigManager::class],
        instrumentedPackages = ["net.activitywatch.android.ConfigManager"],
    )
    fun pendingDisableSurvivesBackWhileRegenerationSaves() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions("${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        ConfigManager(context).generateAndSetApiKey()
        AWPreferences(context).setDashboardAuthEnabled(true)
        val controller = Robolectric.buildActivity(AuthSettingsActivity::class.java).setup()
        try {
            val activity = controller.get()
            val authSwitch = activity.findViewById<SwitchCompat>(R.id.switch_auth_enabled)
            awaitMain { authSwitch.isChecked }
            BlockingConfigManager.started = CountDownLatch(1)
            BlockingConfigManager.release = CountDownLatch(1)
            BlockingConfigManager.cleared = CountDownLatch(1)
            activity.findViewById<Button>(R.id.btn_regenerate_key).performClick()
            awaitMain { BlockingConfigManager.started.count == 0L }
            authSwitch.isChecked = false
            ShadowLooper.idleMainLooper()
            // Back while the disable is still queued behind the regeneration.
            controller.pause().stop().destroy()
            BlockingConfigManager.release.countDown()
            awaitMain { !AWPreferences(context).isDashboardAuthEnabled() }
            assertFalse(
                "a disable the user asked for must not be dropped by Back",
                ConfigManager(context).readAuthConfig().isEnabled,
            )
            assertEquals("", ensureDashboardApiKey(context))
        } finally {
            BlockingConfigManager.release.countDown()
            BlockingConfigManager.clearRelease.countDown()
        }
    }

    @Test
    @Config(
        shadows = [BlockingConfigManager::class],
        instrumentedPackages = ["net.activitywatch.android.ConfigManager"],
    )
    fun olderSaveDoesNotResetTheSwitch() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions("${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        File(context.filesDir, "config.toml").delete()
        AWPreferences(context).setDashboardAuthEnabled(false)
        val controller = Robolectric.buildActivity(AuthSettingsActivity::class.java).setup()
        try {
            val activity = controller.get()
            val authSwitch = activity.findViewById<SwitchCompat>(R.id.switch_auth_enabled)
            awaitMain { !authSwitch.isChecked }
            BlockingConfigManager.started = CountDownLatch(1)
            BlockingConfigManager.release = CountDownLatch(1)
            BlockingConfigManager.cleared = CountDownLatch(1)
            BlockingConfigManager.clearRelease = CountDownLatch(1)
            authSwitch.isChecked = true
            awaitMain { BlockingConfigManager.started.count == 0L }
            // Off arrives while On is still saving: the older save must not flip the
            // switch back to On when it finishes.
            authSwitch.isChecked = false
            ShadowLooper.idleMainLooper()
            BlockingConfigManager.release.countDown()
            awaitMain { BlockingConfigManager.cleared.count == 0L }
            ShadowLooper.idleMainLooper()
            assertFalse(
                "an older save must not restore an intent the user already replaced",
                authSwitch.isChecked,
            )
            BlockingConfigManager.clearRelease.countDown()
            awaitMain { !AWPreferences(context).isDashboardAuthEnabled() }
            assertEquals("", ensureDashboardApiKey(context))
        } finally {
            BlockingConfigManager.release.countDown()
            BlockingConfigManager.clearRelease.countDown()
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
