package net.activitywatch.android

import android.Manifest
import android.app.Activity
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Run on API 36 with a display cutout, and an older API, in both navigation modes. */
@RunWith(AndroidJUnit4::class)
class NativeWindowInsetsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val packageName = instrumentation.targetContext.packageName
    private var originalUsageAccessMode: String? = null
    private var notificationPermissionWasGranted = false

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)
        ).bufferedReader().use { it.readText() }

    @Before
    fun grantRequiredAccess() {
        // PACKAGE_USAGE_STATS is controlled by AppOps, not a runtime permission.
        // GrantPermissionRule cannot enable it on a fresh CI emulator.
        originalUsageAccessMode = Regex("GET_USAGE_STATS: (\\w+)")
            .find(shell("appops get $packageName GET_USAGE_STATS"))
            ?.groupValues?.get(1) ?: "default"
        shell("appops set $packageName GET_USAGE_STATS allow")
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermissionWasGranted = instrumentation.targetContext
                .checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!notificationPermissionWasGranted) {
                instrumentation.uiAutomation.grantRuntimePermission(
                    packageName,
                    Manifest.permission.POST_NOTIFICATIONS
                )
            }
        }
    }

    @After
    fun restoreUsageAccess() {
        originalUsageAccessMode?.let {
            shell("appops set $packageName GET_USAGE_STATS $it")
        }
    }

    private fun assertSafeContent(activity: Activity) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val insets = requireNotNull(ViewCompat.getRootWindowInsets(root))
        val safe = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        val position = IntArray(2)
        root.getLocationOnScreen(position)
        assertTrue("Content top must clear status bar/cutout", position[1] + root.paddingTop >= safe.top)
        assertTrue("Content left must clear cutout", position[0] + root.paddingLeft >= safe.left)
        assertTrue("Content bottom must clear navigation", position[1] + root.height - root.paddingBottom <= device.displayHeight - safe.bottom)
        assertTrue("Content right must clear cutout", position[0] + root.width - root.paddingRight <= device.displayWidth - safe.right)
        assertTrue("Light native surface needs dark status icons",
            WindowCompat.getInsetsController(activity.window, root).isAppearanceLightStatusBars)

        val padding = listOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
        repeat(3) { ViewCompat.dispatchApplyWindowInsets(root, insets) }
        assertEquals("Redispatch must not accumulate padding", padding,
            listOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom))
    }

    private fun <A : Activity> checkWindow(type: Class<A>) {
        ActivityScenario.launch(type).use { scenario ->
            device.waitForIdle()
            scenario.onActivity { assertSafeContent(it) }
            scenario.recreate()
            device.waitForIdle()
            scenario.onActivity { assertSafeContent(it) }
        }
    }

    @Test fun onboardingClearsSystemBarsAfterRecreation() = checkWindow(OnboardingActivity::class.java)

    @Test fun authClearsSystemBarsAfterRecreation() = checkWindow(AuthSettingsActivity::class.java)

    /**
     * Tap the centre of a view through the input pipeline (a real screen tap, not
     * View.performClick), locating it from the activity's own layout. UiAutomator's
     * accessibility lookup was the flaky part here: on a loaded CI emulator it kept
     * answering from the previous window for seconds after the activity was displayed
     * and never found the switch (v0.14.1 tag build, 2026-09-14).
     */
    private fun tapViewCenter(scenario: ActivityScenario<*>, viewId: Int, what: String) {
        val bounds = android.graphics.Rect()
        device.waitForIdle()
        scenario.onActivity { activity ->
            val view = activity.findViewById<View>(viewId)
            assertNotNull("$what must exist", view)
            assertTrue("$what must be laid out on screen", view.getGlobalVisibleRect(bounds))
            assertTrue("$what must have a tappable area", bounds.width() > 0 && bounds.height() > 0)
        }
        assertTrue("$what tap must be injected", device.click(bounds.centerX(), bounds.centerY()))
    }

    private fun awaitSyncEnabled(prefs: AWPreferences, expected: Boolean, message: String) {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline && prefs.isSyncEnabled() != expected) {
            Thread.sleep(100)
        }
        assertEquals(message, expected, prefs.isSyncEnabled())
    }

    @Test fun syncToggleReceivesRealTap() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = AWPreferences(context)
        val original = prefs.isSyncEnabled()
        try {
            ActivityScenario.launch(SyncSettingsActivity::class.java).use { scenario ->
                scenario.onActivity { assertSafeContent(it) }
                tapViewCenter(scenario, R.id.switch_sync_enabled, "Sync switch")
                awaitSyncEnabled(prefs, !original, "A screen tap must change the persisted setting")
                scenario.recreate()
                device.waitForIdle()
                scenario.onActivity { assertSafeContent(it) }
                assertEquals(!original, prefs.isSyncEnabled())
                tapViewCenter(scenario, R.id.switch_sync_enabled, "Sync switch")
                awaitSyncEnabled(prefs, original, "A second tap must restore the persisted setting")
            }
        } finally {
            prefs.setSyncEnabled(original)
        }
    }

    @Test fun drawerAndWebContentClearSystemBars() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = AWPreferences(context)
        val wasFirstTime = prefs.isFirstTime()
        prefs.setFirstTimeRunFlag()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                device.waitForIdle()
                scenario.onActivity {
                    assertSafeContent(it)
                    it.findViewById<DrawerLayout>(R.id.drawer_layout).openDrawer(GravityCompat.START, false)
                }
                device.waitForIdle()
                scenario.onActivity {
                    val logo = it.findViewById<View>(R.id.imageView)
                    val bounds = android.graphics.Rect()
                    assertTrue(logo.getGlobalVisibleRect(bounds))
                    assertEquals("The logo must not be clipped", logo.height, bounds.height())
                }
                scenario.recreate()
                device.waitForIdle()
                scenario.onActivity { assertSafeContent(it) }
            }
        } finally {
            if (wasFirstTime) prefs.resetFirstTimeRunFlag()
        }
    }
}
