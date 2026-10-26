package net.activitywatch.android

import android.content.Context
import android.os.ParcelFileDescriptor
import android.view.View
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.activitywatch.android.fragments.WebUIFragment
import net.activitywatch.android.fragments.THEME_SETTLE_MAX_EXTENSIONS
import net.activitywatch.android.fragments.themeSettleFailsafeMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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
        val stylesheetRequests = AtomicInteger()
        val releaseStylesheet = AtomicReference(CountDownLatch(1))
        val stylesheetMissing = AtomicBoolean(false)
        val server = ServerSocket(0)
        val responder = thread(isDaemon = true) {
            try {
                while (true) {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val input = socket.getInputStream().bufferedReader()
                        val path = input.readLine()?.split(" ")?.getOrNull(1) ?: "/"
                        while (!input.readLine().isNullOrEmpty()) { }
                        if (path == "/dark.css" && stylesheetMissing.get()) {
                            socket.getOutputStream().write(
                                "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()
                            )
                            return@use
                        }
                        val (type, body) = when (path) {
                            "/" -> "text/html" to PAGE
                            "/early" -> "text/html" to PAGE_EARLY
                            "/settings" -> "application/json" to "{\"theme\":\"dark\"}"
                            "/dark.css" -> {
                                val release = releaseStylesheet.get()
                                stylesheetRequests.incrementAndGet()
                                release.await(15, TimeUnit.SECONDS)
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
        val originalFailsafeMs = themeSettleFailsafeMs
        try {
            // Only the page's settle report may reveal it, not the fail-open bound.
            themeSettleFailsafeMs = TimeUnit.MINUTES.toMillis(5)
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
                await { stylesheetRequests.get() == 1 }
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
                releaseStylesheet.get().countDown()
                await {
                    var visible = false
                    scenario.onActivity { visible = webView.visibility == View.VISIBLE }
                    visible
                }
                assertEquals(
                    "\"rgb(15, 19, 26)\"",
                    evaluate(webView, "getComputedStyle(document.body).backgroundColor")
                )

                // A page-initiated reload replaces the document without a new
                // fragment; its light frame must be hidden too.
                releaseStylesheet.set(CountDownLatch(1))
                evaluate(webView, "location.reload()")
                await { stylesheetRequests.get() == 2 }
                await { evaluate(webView, "document.readyState") == "\"complete\"" }
                assertEquals(
                    "\"rgb(247, 250, 252)\"",
                    evaluate(webView, "getComputedStyle(document.body).backgroundColor")
                )
                scenario.onActivity {
                    assertEquals(
                        "A reloaded light-styled document must not be painted before dark.css applies",
                        View.INVISIBLE, webView.visibility
                    )
                }
                releaseStylesheet.get().countDown()
                await {
                    var visible = false
                    scenario.onActivity { visible = webView.visibility == View.VISIBLE }
                    visible
                }

                // A failed stylesheet still reveals the readable light page.
                stylesheetMissing.set(true)
                evaluate(webView, "window.__previousDocument = true; location.reload()")
                await {
                    var visible = false
                    scenario.onActivity { visible = webView.visibility == View.VISIBLE }
                    visible && evaluate(
                        webView,
                        "!window.__previousDocument && !!document.getElementById('wrapper')",
                    ) == "true"
                }
                assertEquals(
                    "\"rgb(247, 250, 252)\"",
                    evaluate(webView, "getComputedStyle(document.body).backgroundColor")
                )

                // A stylesheet that failed before the hook was injected has
                // already dispatched its error event. The hook must read the
                // null sheet as settled instead of waiting for a listener that
                // can never fire (the failsafe is disabled here).
                evaluate(webView, "location.href = '/early'")
                await {
                    var visible = false
                    scenario.onActivity { visible = webView.visibility == View.VISIBLE }
                    visible && evaluate(webView, "document.readyState") == "\"complete\""
                }
                assertEquals(
                    "\"rgb(247, 250, 252)\"",
                    evaluate(webView, "getComputedStyle(document.body).backgroundColor")
                )

                // A dashboard that takes longer than the failsafe bound to
                // settle must still be waited for: revealing it there would
                // paint the light base styles first.
                stylesheetMissing.set(false)
                themeSettleFailsafeMs = 1_000
                releaseStylesheet.set(CountDownLatch(1))
                evaluate(webView, "location.href = '/'")
                // Wait until the WebView has actually navigated to `/` before
                // polling the theme state. Evaluating on the unloading /early
                // document returns undefined for __awAndroidThemeState, which
                // would cause the final await to time out spuriously.
                await { evaluate(webView, "location.href") == "\"$url\"" }
                await { stylesheetRequests.get() == 3 }
                await { evaluate(webView, "document.readyState") == "\"complete\"" }
                await { evaluate(webView, "window.__awAndroidThemeState()") == "\"pending\"" }
                // Past every polling attempt plus scheduling margin, not just
                // the first extension. The old cap revealed this light page.
                Thread.sleep(themeSettleFailsafeMs * (THEME_SETTLE_MAX_EXTENSIONS + 1) + 1_500)
                assertEquals(
                    "The held stylesheet must still be pending after the cap",
                    "\"pending\"", evaluate(webView, "window.__awAndroidThemeState()")
                )
                assertEquals(
                    "\"rgb(247, 250, 252)\"",
                    evaluate(webView, "getComputedStyle(document.body).backgroundColor")
                )
                scenario.onActivity {
                    assertEquals(
                        "A slow dashboard must stay hidden even after the polling cap",
                        View.INVISIBLE, webView.visibility
                    )
                }
                releaseStylesheet.get().countDown()
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
            themeSettleFailsafeMs = originalFailsafeMs
            releaseStylesheet.get().countDown()
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
        // The link is appended while the document is still parsing, so its 404
        // error fires before the load event (and therefore before the hook is
        // injected from onPageFinished).
        val PAGE_EARLY = """
            <!doctype html>
            <html><head><script>
              var link = document.createElement('link');
              link.rel = 'stylesheet';
              link.href = '/dark.css';
              document.head.appendChild(link);
            </script></head>
            <body><div id="app"><div id="wrapper">dashboard</div></div></body></html>
        """.trimIndent()

        val PAGE = """
            <!doctype html>
            <html><head><style>body { background: rgb(247, 250, 252); }</style></head>
            <body><div id="app"></div><script>
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
