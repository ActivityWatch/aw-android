package net.activitywatch.android

import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Runs the actual hook in WebView with link creation explicitly before injection. */
@RunWith(AndroidJUnit4::class)
class WebUiThemeHookTest {
    @Test
    fun preInstallInFlightStylesheetWaitsForItsResponse() {
        val requested = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = ServerSocket(0)
        val responder = thread(isDaemon = true) {
            try {
                while (!server.isClosed) {
                    server.accept().use { socket ->
                        val input = socket.getInputStream().bufferedReader()
                        while (!input.readLine().isNullOrEmpty()) { }
                        requested.countDown()
                        check(release.await(15, TimeUnit.SECONDS))
                        val css = "body { background: rgb(15, 19, 26); }"
                        socket.getOutputStream().write((
                            "HTTP/1.1 200 OK\r\nContent-Type: text/css\r\n" +
                                "Content-Length: ${css.length}\r\nConnection: close\r\n\r\n$css"
                            ).toByteArray())
                    }
                }
            } catch (_: SocketException) {
                // Server closed by cleanup.
            }
        }
        try {
            withPage(baseUrl = "http://127.0.0.1:${server.localPort}/") { view ->
                evaluate(view, """
                    var link = document.createElement('link');
                    link.rel = 'stylesheet'; link.href = 'http://127.0.0.1:${server.localPort}/dark.css';
                    document.head.appendChild(link);
                """.trimIndent())
                assertTrue(requested.await(5, TimeUnit.SECONDS))
                evaluate(view, ANDROID_THEME_HOOK_JS)
                assertEquals("\"pending\"", evaluate(view, "window.__awAndroidThemeState()"))
                assertEquals("0", evaluate(view, "window.reports"))
                assertEquals("\"rgb(247, 250, 252)\"", evaluate(view, "getComputedStyle(document.body).backgroundColor"))
                release.countDown()
                await { evaluate(view, "window.reports") == "1" }
                assertEquals("\"rgb(15, 19, 26)\"", evaluate(view, "getComputedStyle(document.body).backgroundColor"))
            }
        } finally {
            release.countDown()
            server.close()
            responder.join(1000)
        }
    }

    @Test
    fun preInstallFailureSettlesAfterTimingEviction() {
        withPage { view ->
            evaluate(view, """
                window.failed = false;
                var link = document.createElement('link');
                link.rel = 'stylesheet'; link.href = 'http://127.0.0.1:1/dark.css';
                link.onerror = function () { window.failed = true; };
                document.head.appendChild(link);
            """.trimIndent())
            await { evaluate(view, "window.failed") == "true" }
            // An already-fired error cannot be observed by the hook's listener.
            // Resource Timing entries may have been cleared or evicted as well.
            evaluate(view, "performance.clearResourceTimings()")
            assertEquals("0", evaluate(view, "performance.getEntriesByName(link.href, 'resource').length"))

            assertEquals("0", evaluate(view, "window.reports"))
            evaluate(view, ANDROID_THEME_HOOK_JS)
            await { evaluate(view, "window.reports") == "1" }
            assertEquals("\"settled\"", evaluate(view, "window.__awAndroidThemeState()"))
        }
    }

    private fun withPage(baseUrl: String = "http://127.0.0.1/", test: (WebView) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var view: WebView
        instrumentation.runOnMainSync {
            view = WebView(instrumentation.targetContext)
            view.settings.javaScriptEnabled = true
            view.loadDataWithBaseURL(baseUrl, """
                <!doctype html><html><head>
                <style>body { background: rgb(247, 250, 252); }</style></head>
                <body><div id="app"><div id="wrapper">dashboard</div></div><script>
                window.reports = 0;
                window.Android = { reportColorScheme: function () {},
                  reportThemeSettled: function () { window.reports++; } };
                </script></body></html>
            """.trimIndent(), "text/html", "UTF-8", null)
        }
        try {
            await { evaluate(view, "document.readyState === 'complete' && typeof window.reports === 'number'") == "true" }
            test(view)
        } finally {
            instrumentation.runOnMainSync { view.destroy() }
        }
    }

    private fun evaluate(view: WebView, script: String): String {
        val done = CountDownLatch(1)
        var result = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.evaluateJavascript(script) { result = it; done.countDown() }
        }
        assertTrue(done.await(3, TimeUnit.SECONDS))
        return result
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        throw AssertionError("Timed out waiting for theme hook")
    }
}
