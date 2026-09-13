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
    private lateinit var originalUsageAccessMode: String
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
        shell("appops set $packageName GET_USAGE_STATS $originalUsageAccessMode")
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

    @Test fun syncToggleReceivesRealTap() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = AWPreferences(context)
        val original = prefs.isSyncEnabled()
        try {
            ActivityScenario.launch(SyncSettingsActivity::class.java).use { scenario ->
                val toggle = device.wait(Until.findObject(By.res(context.packageName, "switch_sync_enabled")), 5000)
                assertNotNull("Sync switch must be visible", toggle)
                scenario.onActivity { assertSafeContent(it) }
                toggle.click()
                instrumentation.waitForIdleSync()
                assertEquals("A screen tap must change the persisted setting", !original, prefs.isSyncEnabled())
                scenario.recreate()
                device.waitForIdle()
                scenario.onActivity { assertSafeContent(it) }
                assertEquals(!original, prefs.isSyncEnabled())
                device.findObject(By.res(context.packageName, "switch_sync_enabled")).click()
                instrumentation.waitForIdleSync()
                assertEquals(original, prefs.isSyncEnabled())
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
