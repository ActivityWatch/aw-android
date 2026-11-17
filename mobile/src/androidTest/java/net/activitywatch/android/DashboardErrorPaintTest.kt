package net.activitywatch.android

import android.content.Context
import android.os.ParcelFileDescriptor
import android.view.View
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.activitywatch.android.fragments.WebUIFragment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Exercises real Chromium error callbacks, rather than a copy of the reload policy. */
@RunWith(AndroidJUnit4::class)
class DashboardErrorPaintTest {
    @Test
    fun connectionRefusedDocumentStaysHiddenUntilRetrySucceeds() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageName = instrumentation.targetContext.packageName
        val originalUsageMode = Regex("GET_USAGE_STATS: (\\w+)")
            .find(shell("appops get $packageName GET_USAGE_STATS"))
            ?.groupValues?.get(1) ?: "default"
        val preferences = instrumentation.targetContext.getSharedPreferences(
            AWPreferences.PREFERENCES_NAME, Context.MODE_PRIVATE
        )
        val wasFirstRun = preferences.getBoolean("isFirstTime", true)
        val hadRequestedPermission = preferences.getBoolean("hasRequestedNotificationPermission", false)
        try {
            shell("appops set $packageName GET_USAGE_STATS allow")
            preferences.edit().putBoolean("isFirstTime", false)
                .putBoolean("hasRequestedNotificationPermission", true).commit()
            // Reserve then release a loopback port: the first main-frame request must fail.
            val port = ServerSocket(0).use { it.localPort }
            val url = "http://127.0.0.1:$port/"
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var webView: WebView
                scenario.onActivity { activity ->
                    activity.supportFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, WebUIFragment.newInstance(url))
                        .commitNow()
                    webView = activity.findViewById(R.id.webview)
                }
                // Wait for an actual failed document, not a fixed startup sleep.
                await { evaluate(webView, "location.href").contains("chrome-error") }
                scenario.onActivity {
                    assertEquals(
                        "Chromium's error document must not be painted",
                        View.INVISIBLE, webView.visibility
                    )
                }
                val served = CountDownLatch(1)
                ServerSocket(port).use { server ->
                    server.soTimeout = 10000
                    val responder = thread(isDaemon = true) {
                        server.accept().use { socket ->
                            socket.soTimeout = 5000
                            val input = socket.getInputStream().bufferedReader()
                            while (!input.readLine().isNullOrEmpty()) { }
                            val body = "<!doctype html><html><body style='background:#0f131a'>ready</body></html>"
                            val response = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n" +
                                "Content-Length: ${body.toByteArray().size}\r\n" +
                                "Connection: close\r\n\r\n$body"
                            socket.getOutputStream().write(response.toByteArray())
                        }
                        served.countDown()
                    }
                    assertTrue(
                        "The existing backoff must retry the original URL",
                        served.await(15, TimeUnit.SECONDS)
                    )
                    await {
                        var visible = false
                        scenario.onActivity { visible = webView.visibility == View.VISIBLE }
                        visible && evaluate(webView, "document.body.textContent").contains("ready")
                    }
                    responder.join(1000)
                }
            }
        } finally {
            preferences.edit().putBoolean("isFirstTime", wasFirstRun)
                .putBoolean("hasRequestedNotificationPermission", hadRequestedPermission).commit()
            shell("appops set $packageName GET_USAGE_STATS $originalUsageMode")
        }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }

    private fun evaluate(view: WebView, expression: String): String {
        val done = CountDownLatch(1)
        var result = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.evaluateJavascript(expression) { result = it; done.countDown() }
        }
        assertTrue(done.await(3, TimeUnit.SECONDS))
        return result
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        throw AssertionError("Timed out waiting for WebView state")
    }
}
