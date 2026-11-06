package net.activitywatch.android

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.graphics.writeToTestStorage
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "ScreenshotTest"

class ScreenshotTest {
    @get:Rule
    var nameRule = TestName()

    @Test
    fun testScreenshot() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = context.getSharedPreferences(AWPreferences.PREFERENCES_NAME, Context.MODE_PRIVATE)
        val fixtureKeys = listOf("isFirstTime", "hasRequestedNotificationPermission")
        val originalPreferences = fixtureKeys.associateWith { preferences.all[it] }
        val originalUsageMode = Regex("GET_USAGE_STATS: (\\w+)")
            .find(shell("appops get ${context.packageName} GET_USAGE_STATS"))
            ?.groupValues?.get(1) ?: "default"
        try {
            // Usage access is an AppOp, not a runtime permission. Avoid onboarding
            // on a fresh emulator, then restore the original fixture state.
            shell("appops set ${context.packageName} GET_USAGE_STATS allow")
            preferences.edit().putBoolean("isFirstTime", false)
                .putBoolean("hasRequestedNotificationPermission", true).commit()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitDashboard(scenario)
                val bitmap = instrumentation.uiAutomation.takeScreenshot()
                assertNotNull("Dashboard screenshot was not captured", bitmap)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    bitmap!!.writeToTestStorage("${javaClass.simpleName}_${nameRule.methodName}")
                } else {
                    Log.i(TAG, "Screenshot not saved to test storage, API level too low")
                }
            }
        } finally {
            preferences.edit().apply {
                originalPreferences.forEach { (key, value) ->
                    if (value == null) remove(key) else putBoolean(key, value as Boolean)
                }
            }.commit()
            shell("appops set ${context.packageName} GET_USAGE_STATS $originalUsageMode")
        }
    }

    private fun awaitDashboard(scenario: ActivityScenario<MainActivity>) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val ready = AtomicBoolean(false)
            val evaluated = CountDownLatch(1)
            scenario.onActivity { activity ->
                val webView = activity.findViewById<WebView>(R.id.webview)
                if (webView == null || !webView.isShown) {
                    evaluated.countDown()
                } else {
                    // Visibility alone also accepts the fail-open non-dashboard document.
                    // Production reveals the dashboard after its visual-state callback.
                    webView.evaluateJavascript(
                        "document.readyState === 'complete' && " +
                            "!!document.getElementById('wrapper') && " +
                            "!!window.__awAndroidThemeState && " +
                            "window.__awAndroidThemeState() === 'settled'"
                    ) { result ->
                        ready.set(result == "true")
                        evaluated.countDown()
                    }
                }
            }
            assertTrue("WebView readiness callback timed out", evaluated.await(3, TimeUnit.SECONDS))
            if (ready.get()) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Dashboard did not render within 30 seconds")
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ).bufferedReader().use { it.readText() }
}
