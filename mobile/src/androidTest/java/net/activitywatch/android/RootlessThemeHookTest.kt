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
