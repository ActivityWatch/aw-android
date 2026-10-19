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
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A successful dashboard document paints its light base styles before it has
 * loaded settings and applied dark.css (aw-android#300). The page below follows
 * aw-webui's App.vue: after load it fetches settings, then appends the dark
 * stylesheet and renders `#wrapper`. The server holds dark.css, so the light
 * document is provably loaded while the stylesheet is pending.
 */
@RunWith(AndroidJUnit4::class)
class DashboardThemePaintTest {
    @Test
    fun lightDocumentStaysHiddenUntilDarkStylesheetApplies() {
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
        val stylesheetRequested = CountDownLatch(1)
        val releaseStylesheet = CountDownLatch(1)
        val server = ServerSocket(0)
        val responder = thread(isDaemon = true) {
            try {
                while (true) {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val input = socket.getInputStream().bufferedReader()
                        val path = input.readLine()?.split(" ")?.getOrNull(1) ?: "/"
                        while (!input.readLine().isNullOrEmpty()) { }
                        val (type, body) = when (path) {
                            "/" -> "text/html" to PAGE
                            "/settings" -> "application/json" to "{\"theme\":\"dark\"}"
                            "/dark.css" -> {
                                stylesheetRequested.countDown()
                                releaseStylesheet.await(15, TimeUnit.SECONDS)
                                "text/css" to "body { background: rgb(15, 19, 26); }"
                            }
                            else -> "text/plain" to ""
                        }
                        val response = "HTTP/1.1 200 OK\r\nContent-Type: $type\r\n" +
                            "Content-Length: ${body.toByteArray().size}\r\n" +
                            "Cache-Control: no-store\r\nConnection: close\r\n\r\n$body"
                        socket.getOutputStream().write(response.toByteArray())
                    }
                }
            } catch (_: SocketException) {
                // Closed by the test.
            }
        }
        try {
            shell("appops set $packageName GET_USAGE_STATS allow")
            preferences.edit().putBoolean("isFirstTime", false)
                .putBoolean("hasRequestedNotificationPermission", true).commit()
            val url = "http://127.0.0.1:${server.localPort}/"
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var webView: WebView
                scenario.onActivity { activity ->
                    activity.supportFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, WebUIFragment.newInstance(url))
                        .commitNow()
                    webView = activity.findViewById(R.id.webview)
                }
                assertTrue(
                    "The page must request its dark stylesheet",
                    stylesheetRequested.await(15, TimeUnit.SECONDS)
                )
                await { evaluate(webView, "document.readyState") == "\"complete\"" }
                assertEquals(
                    "The finished document is still light-styled",
                    "\"rgb(247, 250, 252)\"",
                    evaluate(webView, "getComputedStyle(document.body).backgroundColor")
                )
                scenario.onActivity {
                    assertEquals(
                        "A light-styled document must not be painted before dark.css applies",
                        View.INVISIBLE, webView.visibility
                    )
                }
                releaseStylesheet.countDown()
                await {
                    var visible = false
                    scenario.onActivity { visible = webView.visibility == View.VISIBLE }
                    visible
                }
                assertEquals(
                    "\"rgb(15, 19, 26)\"",
                    evaluate(webView, "getComputedStyle(document.body).backgroundColor")
                )
            }
        } finally {
            releaseStylesheet.countDown()
            server.close()
            responder.join(1000)
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

    private companion object {
        val PAGE = """
            <!doctype html>
            <html><head><style>body { background: rgb(247, 250, 252); }</style></head>
            <body><script>
              window.addEventListener('load', function () {
                fetch('/settings').then(function (r) { return r.json(); }).then(function () {
                  var link = document.createElement('link');
                  link.rel = 'stylesheet';
                  link.href = '/dark.css';
                  document.head.appendChild(link);
                  var wrapper = document.createElement('div');
                  wrapper.id = 'wrapper';
                  wrapper.textContent = 'dashboard';
                  document.body.appendChild(wrapper);
                });
              });
            </script></body></html>
        """.trimIndent()
    }
}
