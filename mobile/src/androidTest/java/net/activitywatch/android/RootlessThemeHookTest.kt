package net.activitywatch.android

import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Executes the real hook in a WebView, without depending on a navigation race. */
@RunWith(AndroidJUnit4::class)
class RootlessThemeHookTest {
    class ThemeBridge {
        val reports = AtomicInteger()
        val navigation = AtomicReference<String>()
        val settled = CountDownLatch(1)
        val checkpoint = CountDownLatch(1)

        @JavascriptInterface
        fun reportColorScheme(scheme: String) { }

        @JavascriptInterface
        fun reportThemeSettled(value: String) {
            navigation.set(value)
            reports.incrementAndGet()
            settled.countDown()
        }

        @JavascriptInterface
        fun reportCheckpoint() {
            checkpoint.countDown()
        }
    }

    @Test
    fun postLoadStylesheetPresentBeforeHookMustStillWaitForLoad() = pendingStylesheet(false)

    @Test
    fun documentStartRecorderWaitsForPostLoadStylesheet() = pendingStylesheet(true)

    private fun pendingStylesheet(withRecorder: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bridge = ThemeBridge()
        val loaded = CountDownLatch(1)
        val requested = CountDownLatch(1)
        val release = CountDownLatch(1)
        lateinit var webView: WebView
        instrumentation.runOnMainSync {
            webView = WebView(instrumentation.targetContext)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.addJavascriptInterface(bridge, "Android")
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    loaded.countDown()
                }

                override fun shouldInterceptRequest(
                    view: WebView, request: android.webkit.WebResourceRequest,
                ): android.webkit.WebResourceResponse? {
                    if (!request.url.path.orEmpty().endsWith("dark.css")) return null
                    requested.countDown()
                    check(release.await(30, TimeUnit.SECONDS)) { "CSS fixture hold expired" }
                    return android.webkit.WebResourceResponse(
                        "text/css", "UTF-8",
                        "body { background: rgb(15, 19, 26); }".byteInputStream(),
                    )
                }
            }
            if (withRecorder) {
                org.junit.Assume.assumeTrue(androidx.webkit.WebViewFeature.isFeatureSupported(
                    androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT,
                ))
                installThemeStylesheetRecorder(webView)
            }
            webView.loadDataWithBaseURL(
                "https://theme-hook.invalid/",
                "<html><head></head><body style='color: black'><div id='app'></div></body></html>",
                "text/html", "UTF-8", null,
            )
        }
        try {
            assertTrue("Fixture document did not load", loaded.await(10, TimeUnit.SECONDS))
            val installed = CountDownLatch(1)
            val state = AtomicReference<String>()
            instrumentation.runOnMainSync {
                webView.evaluateJavascript(
                    """
                    window.__awAndroidNavigation = 'pending-at-install';
                    var link = document.createElement('link');
                    link.rel = 'stylesheet'; link.href = '/dark.css';
                    document.head.appendChild(link);
                    var wrapper = document.createElement('div'); wrapper.id = 'wrapper';
                    document.body.appendChild(wrapper);
                    $ANDROID_THEME_HOOK_JS
                    window.__awAndroidThemeState();
                    """.trimIndent(),
                ) { value -> state.set(value); installed.countDown() }
            }
            assertTrue("Hook did not install", installed.await(10, TimeUnit.SECONDS))
            assertTrue("Stylesheet was not requested", requested.await(10, TimeUnit.SECONDS))
            assertEquals("A post-load stylesheet is pending, not already failed", "\"pending\"", state.get())
            assertEquals("Must not report before CSS loads", 0, bridge.reports.get())
            release.countDown()
            assertTrue("Loaded stylesheet never settled", bridge.settled.await(10, TimeUnit.SECONDS))
            assertEquals("pending-at-install", bridge.navigation.get())
            assertEquals(1, bridge.reports.get())
        } finally {
            release.countDown()
            instrumentation.runOnMainSync { webView.destroy() }
        }
    }

    @Test
    fun alreadyFailedStylesheetSettlesWithoutRecorder() = failedStylesheet(false)

    @Test
    fun documentStartRecorderRemembersEarlyStylesheetError() = failedStylesheet(true)

    private fun failedStylesheet(withRecorder: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bridge = ThemeBridge()
        val loaded = CountDownLatch(1)
        lateinit var webView: WebView
        instrumentation.runOnMainSync {
            webView = WebView(instrumentation.targetContext)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.addJavascriptInterface(bridge, "Android")
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) { loaded.countDown() }
                override fun shouldInterceptRequest(
                    view: WebView, request: android.webkit.WebResourceRequest,
                ): android.webkit.WebResourceResponse? =
                    if (request.url.path == "/dark.css") android.webkit.WebResourceResponse(
                        "text/css", "UTF-8", 404, "Not Found", emptyMap(), "".byteInputStream(),
                    ) else null
            }
            if (withRecorder) {
                org.junit.Assume.assumeTrue(androidx.webkit.WebViewFeature.isFeatureSupported(
                    androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT,
                ))
                installThemeStylesheetRecorder(webView)
            }
            webView.loadDataWithBaseURL(
                "https://theme-hook.invalid/",
                """
                <html><head><link rel='stylesheet' href='/dark.css' onerror='window.cssFailed = true'></head>
                <body><div id='app'><div id='wrapper'></div></div></body></html>
                """.trimIndent(), "text/html", "UTF-8", null,
            )
        }
        try {
            assertTrue("Fixture document did not load", loaded.await(10, TimeUnit.SECONDS))
            val installed = CountDownLatch(1)
            val failed = AtomicReference<String>()
            instrumentation.runOnMainSync {
                webView.evaluateJavascript("window.cssFailed === true") { value ->
                    failed.set(value)
                    installed.countDown()
                }
            }
            assertTrue(installed.await(10, TimeUnit.SECONDS))
            assertEquals("404 must occur before hook installation", "true", failed.get())
            instrumentation.runOnMainSync {
                webView.evaluateJavascript(
                    "window.__awAndroidNavigation = 'early-error'; $ANDROID_THEME_HOOK_JS", null,
                )
            }
            val didSettle = bridge.settled.await(10, TimeUnit.SECONDS)
            val inspected = CountDownLatch(1)
            val diagnostic = AtomicReference<String>()
            instrumentation.runOnMainSync {
                webView.evaluateJavascript("JSON.stringify({state: window.__awAndroidThemeState(), recorder: window.__awThemeStylesheetRecorder, link: document.querySelector('link').outerHTML, watched: document.querySelector('link').__awWatched, terminal: document.querySelector('link').__awSettled, sheet: !!document.querySelector('link').sheet})") {
                    diagnostic.set(it); inspected.countDown()
                }
            }
            assertTrue(inspected.await(10, TimeUnit.SECONDS))
            assertTrue("Early error never settled: ${diagnostic.get()}", didSettle)
            assertEquals("early-error", bridge.navigation.get())
            assertEquals(1, bridge.reports.get())
        } finally {
            instrumentation.runOnMainSync { webView.destroy() }
        }
    }

    @Test
    fun hookSurvivesMissingRootAndReportsSettlementOnce() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bridge = ThemeBridge()
        val loaded = CountDownLatch(1)
        lateinit var webView: WebView
        instrumentation.runOnMainSync {
            webView = WebView(instrumentation.targetContext)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.addJavascriptInterface(bridge, "Android")
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    loaded.countDown()
                }
            }
            webView.loadDataWithBaseURL(
                "https://theme-hook.invalid/",
                "<html><head></head><body><div id='app'></div></body></html>",
                "text/html", "UTF-8", null
            )
        }
        try {
            assertTrue("Fixture document did not load", loaded.await(10, TimeUnit.SECONDS))
            val installed = CountDownLatch(1)
            val result = AtomicReference<String>()
            instrumentation.runOnMainSync {
                webView.evaluateJavascript(
                    """
                    (function () {
                      localStorage.setItem('theme', 'light');
                      window.__awAndroidNavigation = 'rootless-test';
                      document.documentElement.remove();
                      if (document.documentElement !== null) return 'root still exists';
                      try {
                        $ANDROID_THEME_HOOK_JS
                        return 'installed without root';
                      } catch (error) {
                        return String(error);
                      }
                    })();
                    """.trimIndent()
                ) { value ->
                    result.set(value)
                    installed.countDown()
                }
            }
            assertTrue("Hook evaluation did not finish", installed.await(10, TimeUnit.SECONDS))
            assertEquals("\"installed without root\"", result.get())
            assertEquals("A rootless document must not settle", 0, bridge.reports.get())

            instrumentation.runOnMainSync {
                webView.evaluateJavascript(
                    """
                    var root = document.createElement('html');
                    root.innerHTML = '<head></head><body><div id="app"><div id="wrapper"></div></div></body>';
                    document.appendChild(root);
                    """.trimIndent(), null
                )
            }
            assertTrue("Restored dashboard never settled", bridge.settled.await(10, TimeUnit.SECONDS))
            assertEquals("rootless-test", bridge.navigation.get())

            // More mutations and a repeated injection must not report twice.
            instrumentation.runOnMainSync {
                webView.evaluateJavascript(
                    """
                    document.getElementById('wrapper').appendChild(document.createElement('span'));
                    $ANDROID_THEME_HOOK_JS
                    setTimeout(function () { Android.reportCheckpoint(); }, 100);
                    """.trimIndent(), null
                )
            }
            assertTrue("Final JS checkpoint missing", bridge.checkpoint.await(10, TimeUnit.SECONDS))
            assertEquals("Settlement must be reported once per document", 1, bridge.reports.get())
        } finally {
            instrumentation.runOnMainSync {
                webView.removeJavascriptInterface("Android")
                webView.destroy()
            }
        }
    }
}
