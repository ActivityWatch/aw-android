package net.activitywatch.android

import android.Manifest
import android.app.Activity
import android.content.res.Configuration
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
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

    private companion object {
        /** Upper bound for one idle drain, so `awaitRotatedLayout`'s timeout actually holds. */
        const val DRAIN_TIMEOUT_MS = 250L
        const val STABLE_ROTATED_SAMPLES = 3
    }

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

    private fun assertSafeContent(activity: Activity, requireLightStatusIcons: Boolean = true) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val insets = requireNotNull(ViewCompat.getRootWindowInsets(root))
        val safe = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        val position = IntArray(2)
        root.getLocationOnScreen(position)
        assertTrue("Content top must clear status bar/cutout", position[1] + root.paddingTop >= safe.top)
        assertTrue("Content left must clear cutout", position[0] + root.paddingLeft >= safe.left)
        assertTrue(
            "Content bottom must clear navigation: positionY=${position[1]} height=${root.height} " +
                "paddingBottom=${root.paddingBottom} displayHeight=${device.displayHeight} safeBottom=${safe.bottom}",
            position[1] + root.height - root.paddingBottom <= device.displayHeight - safe.bottom,
        )
        assertTrue("Content right must clear cutout", position[0] + root.width - root.paddingRight <= device.displayWidth - safe.right)
        if (requireLightStatusIcons) {
            assertTrue("Light native surface needs dark status icons",
                WindowCompat.getInsetsController(activity.window, root).isAppearanceLightStatusBars)
        }

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

    /**
     * After `requestedOrientation`, `device.waitForIdle()` only drains the looper: the
     * display metrics can already be landscape while this activity's view tree is still
     * portrait, so a post-rotation inset assertion can read stale geometry and fail on a
     * healthy app (CI runs 35171459452 / 35171939211 / 35174360915, 2026-09-17). Poll
     * (bounded) until the view tree has actually rotated — the activity's configuration
     * reports landscape and the complete rotated geometry stays stable across three
     * samples — so the assertion runs against settled layout.
     *
     * This waits for the re-dispatch — it does not retry the assertion until it passes,
     * and the assertion below stays strict. On timeout it fails with its own message.
     *
     * The timeout is a failure budget, not a latency target: the helper returns as soon
     * as three stable rotated samples land, so a generous bound costs nothing on the
     * happy path. 3s was too tight on a loaded CI emulator — runs 35185908412 and
     * 35187675397 both failed with "did not stabilise within 3000ms (width=640
     * height=320)" even though the tree had already reached landscape, because rotation
     * plus the inset re-dispatch finished too close to the deadline to record three
     * consecutive samples. The bound still fails loudly if the layout never settles.
     */
    private fun <A : Activity> awaitRotatedLayout(scenario: ActivityScenario<A>, timeoutMs: Long = 10_000) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var previous: List<Any>? = null
        var consecutiveStableSamples = 0
        var sawRotated = false
        while (true) {
            // Bound each drain: `waitForIdle()` blocks up to 10s while System UI is busy
            // (e.g. mid-rotation), which would overshoot the timeout instead of enforcing it.
            device.waitForIdle(DRAIN_TIMEOUT_MS)
            var width = 0
            var height = 0
            var landscapeConfiguration = false
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                width = root.width
                height = root.height
                // The configuration change is what triggers the insets re-dispatch; the
                // root can already measure landscape one frame before it lands.
                landscapeConfiguration = activity.resources.configuration.orientation ==
                    Configuration.ORIENTATION_LANDSCAPE
            }
            val displayWidth = device.displayWidth
            val displayHeight = device.displayHeight
            val rotated = width > 0 && height > 0 && width > height &&
                landscapeConfiguration && displayWidth > displayHeight
            // Require the complete rotated state to match across three consecutive samples.
            // Keeping configuration and display geometry in the sample guards against
            // returning while either side of the rotation is still transitioning.
            if (rotated) {
                sawRotated = true
                val current = listOf(width, height, landscapeConfiguration, displayWidth, displayHeight)
                consecutiveStableSamples = if (current == previous) consecutiveStableSamples + 1 else 1
                if (consecutiveStableSamples >= STABLE_ROTATED_SAMPLES) return
                previous = current
            } else {
                previous = null
                consecutiveStableSamples = 0
            }
            if (SystemClock.uptimeMillis() >= deadline) {
                // Fail loudly rather than asserting against mid-rotation geometry: a
                // silent return would keep the original flake (or pass on a tree that
                // never rotated at all).
                throw AssertionError(
                    if (sawRotated) {
                        "Rotated layout did not stabilise within ${timeoutMs}ms (width=$width height=$height)"
                    } else {
                        "Timed out after ${timeoutMs}ms waiting for the view tree to rotate to landscape " +
                            "(width=$width height=$height displayWidth=${device.displayWidth} " +
                            "displayHeight=${device.displayHeight})"
                    },
                )
            }
            Thread.sleep(50)
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

    /**
     * Tap [viewId] and poll for [prefs.isSyncEnabled()] == [expected], retrying the tap
     * when the preference does not flip within [pollMs]. Before each attempt the helper
     * waits (bounded) for the view to be enabled and its bounds to stabilise across two
     * consecutive samples, so a tap that lands before the Activity finishes settling its
     * async state does not silently consume an attempt.
     *
     * Addresses two no-ANR flake classes observed in CI after the UiAutomator-node fix:
     *  1. The tap lands while [viewId] is laid out but not yet interactive — the switch
     *     ignores the event and the preference never changes.
     *  2. The preference write completes after [pollMs] ms on a loaded emulator; a
     *     subsequent attempt then reads the already-changed value immediately.
     *
     * The assertion stays strict: a genuinely broken toggle causes the test to fail after
     * [maxAttempts] taps rather than returning silently.
     */
    private fun tapUntilPrefChanges(
        scenario: ActivityScenario<*>,
        viewId: Int,
        what: String,
        prefs: AWPreferences,
        expected: Boolean,
        maxAttempts: Int = 3,
        pollMs: Long = 2000L,
    ) {
        repeat(maxAttempts) { attempt ->
            if (attempt > 0) device.waitForIdle(DRAIN_TIMEOUT_MS)
            // Wait until the view is enabled and its bounds are stable across two samples.
            val bounds = android.graphics.Rect()
            val prevBounds = android.graphics.Rect()
            val stableDeadline = SystemClock.uptimeMillis() + 2000L
            while (SystemClock.uptimeMillis() < stableDeadline) {
                var isEnabled = false
                scenario.onActivity { activity ->
                    val view = activity.findViewById<View>(viewId)
                    isEnabled = view?.isEnabled == true
                    view?.getGlobalVisibleRect(bounds)
                }
                if (isEnabled && bounds == prevBounds && bounds.width() > 0) break
                prevBounds.set(bounds)
                Thread.sleep(50)
            }
            tapViewCenter(scenario, viewId, what)
            val deadline = SystemClock.uptimeMillis() + pollMs
            while (SystemClock.uptimeMillis() < deadline && prefs.isSyncEnabled() != expected) {
                Thread.sleep(100)
            }
            if (prefs.isSyncEnabled() == expected) return
        }
        assertEquals("A screen tap must change the persisted setting", expected, prefs.isSyncEnabled())
    }

    @Test fun syncToggleReceivesRealTap() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = AWPreferences(context)
        val original = prefs.isSyncEnabled()
        try {
            ActivityScenario.launch(SyncSettingsActivity::class.java).use { scenario ->
                scenario.onActivity { assertSafeContent(it) }
                tapUntilPrefChanges(scenario, R.id.switch_sync_enabled, "Sync switch", prefs, !original)
                scenario.recreate()
                device.waitForIdle()
                scenario.onActivity { assertSafeContent(it) }
                assertEquals(!original, prefs.isSyncEnabled())
                tapUntilPrefChanges(scenario, R.id.switch_sync_enabled, "Sync switch", prefs, original)
            }
        } finally {
            prefs.setSyncEnabled(original)
        }
    }

    @Test fun rotatingMainActivityKeepsTheSameWebView() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = AWPreferences(context)
        val wasFirstTime = prefs.isFirstTime()
        prefs.setFirstTimeRunFlag()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                device.waitForIdle()
                var activityId = 0
                var webViewId = 0
                scenario.onActivity { activity ->
                    val webView = activity.findViewById<android.webkit.WebView>(R.id.webview)
                    assertNotNull("WebView must exist before rotation", webView)
                    activityId = System.identityHashCode(activity)
                    webViewId = System.identityHashCode(webView)
                    activity.requestedOrientation =
                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
                awaitRotatedLayout(scenario)
                scenario.onActivity { activity ->
                    assertEquals(
                        "MainActivity must survive rotation instead of being recreated",
                        activityId,
                        System.identityHashCode(activity),
                    )
                    val webView = activity.findViewById<android.webkit.WebView>(R.id.webview)
                    assertNotNull("WebView must still exist after rotation", webView)
                    assertEquals(
                        "The same WebView instance must survive rotation",
                        webViewId,
                        System.identityHashCode(webView),
                    )
                    assertSafeContent(activity, requireLightStatusIcons = false)
                    activity.requestedOrientation =
                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
                device.waitForIdle()
            }
        } finally {
            if (wasFirstTime) prefs.resetFirstTimeRunFlag()
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
                    assertSafeContent(it, requireLightStatusIcons = false)
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
                scenario.onActivity { assertSafeContent(it, requireLightStatusIcons = false) }
            }
        } finally {
            if (wasFirstTime) prefs.resetFirstTimeRunFlag()
        }
    }
}
