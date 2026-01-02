package net.activitywatch.android.watcher

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.util.Function
import net.activitywatch.android.RustInterface
import org.json.JSONObject
import org.threeten.bp.Duration
import org.threeten.bp.Instant

private typealias UrlExtractor = (AccessibilityEvent) -> String?

private fun extractTextByViewId(
    event: AccessibilityEvent,
    viewId: String,
    transformer: Function<String, String>? = null
) = event.source
    ?.findAccessibilityNodeInfosByViewId(viewId)
    ?.firstOrNull()?.text?.toString()
    ?.let { originalValue -> transformer?.apply(originalValue) ?: originalValue }

class WebWatcher : AccessibilityService() {

    private val stripProtocol: (String) -> String = { url ->
        url.removePrefix("http://").removePrefix("https://")
    }

    private val TAG = "WebWatcher"
    private val bucket_id = "aw-watcher-android-web"

    private var ri : RustInterface? = null

    private var lastUrlTimestamp : Instant? = null
    private var lastUrl : String? = null
    private var lastBrowser: String? = null
    private var lastWindowTitle : String? = null
    private var lastWindowId: Int? = null

    private val urlExtractors : Map<String, UrlExtractor> = mapOf(
        "com.android.chrome" to { event ->
            extractTextByViewId(event, "com.android.chrome:id/url_bar")
        },
        "org.mozilla.firefox" to { event ->
            // Firefox has multiple variants depending on version
            extractTextByViewId(event, "org.mozilla.firefox:id/url_bar_title")
                ?: extractTextByViewId(event, "org.mozilla.firefox:id/mozac_browser_toolbar_url_view")
        },
        "com.sec.android.app.sbrowser" to { event ->
            extractTextByViewId(event, "com.sec.android.app.sbrowser:id/location_bar_edit_text")
                ?: extractTextByViewId(event, "com.sec.android.app.sbrowser:id/custom_tab_toolbar_url_bar_text", transformer = stripProtocol)
        },
        "com.opera.browser" to { event ->
            extractTextByViewId(event, "com.opera.browser:id/url_field")
                ?: extractTextByViewId(event, "com.opera.browser:id/address_field")
        },
        "com.microsoft.emmx" to { event ->
            extractTextByViewId(event, "com.microsoft.emmx:id/url_bar")
        }
    )

    override fun onCreate() {
        Log.i(TAG, "Creating WebWatcher")
        ri = RustInterface(applicationContext).also { it.createBucketHelper(bucket_id, "web.tab.current") }
    }

    // TODO: This method is called very often, which might affect performance. Future optimizations needed.
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (shouldIgnoreEvent(event)) {
            return
        }

        val packageName = event.packageName?.toString()
        val urlExtractor = urlExtractors[packageName]

        val windowChanged = windowChanged(event.windowId)
        lastWindowId = event.windowId

        if (urlExtractor == null) {
            // for some browsers like Firefox event.packageName can be null (no extractor matched)
            // but we are still on the same window
            if (windowChanged) {
                handleUrl(null, newBrowser = null)
            }

            return
        }

        try {
            event.source?.let { source ->
                val newUrl = urlExtractor(event)

                newUrl?.let { handleUrl(it, newBrowser = packageName) }.also {
                    findWebView(source)
                        ?.let { handleWindowTitle(it.text.toString()) }
                }
            }
        } catch(ex : Exception) {
            Log.e(TAG, ex.message!!)
        }
    }

    private fun windowChanged(windowId: Int): Boolean = windowId != lastWindowId

    private fun shouldIgnoreEvent(event: AccessibilityEvent) =
        event.packageName == "com.android.systemui"

    private fun findWebView(info : AccessibilityNodeInfo) : AccessibilityNodeInfo? {
        if (info.className == "android.webkit.WebView" && info.text != null) return info

        return (0 until info.childCount)
            .mapNotNull { info.getChild(it) }
            .firstNotNullOfOrNull { child ->
                findWebView(child).also { if (it == null) child.recycle() }
            }
    }

    private fun handleUrl(newUrl : String?, newBrowser: String?) {
        if (newUrl != lastUrl || newBrowser != lastBrowser) {
            newUrl?.let { Log.i(TAG, "Url: $it, browser: $newBrowser") }
            lastUrl?.let { url ->
                lastBrowser?.let { browser ->
                    // Log last URL and title as a completed browser event.
                    // We wait for the event to complete (marked by a change in URL) to ensure that
                    // we had a chance to receive the title of the page, which often only arrives after
                    // the page loads completely and/or the user interacts with the page.
                    val windowTitle = lastWindowTitle ?: ""
                    logBrowserEvent(url, browser, windowTitle, lastUrlTimestamp!!)
                }
            }

            lastUrlTimestamp = Instant.ofEpochMilli(System.currentTimeMillis())
            lastUrl = newUrl
            lastBrowser = newBrowser
            lastWindowTitle = null
        }
    }

    private fun handleWindowTitle(newWindowTitle: String) {
        if (newWindowTitle != lastWindowTitle) {
            lastWindowTitle = newWindowTitle
            Log.i(TAG, "Title: $lastWindowTitle")
        }
    }

    private fun logBrowserEvent(url: String, browser: String, windowTitle: String, lastUrlTimestamp : Instant) {
        val now = Instant.ofEpochMilli(System.currentTimeMillis())
        val start = lastUrlTimestamp
        val duration = Duration.between(lastUrlTimestamp, now)

        val data = JSONObject()
            .put("url", url)
            .put("browser", browser)
            .put("title", windowTitle)
            .put("audible", false) // TODO
            .put("incognito", false) // TODO

        Log.i(TAG, "Registered event: $data")
        ri?.heartbeatHelper(bucket_id, start, duration.seconds.toDouble(), data, 1.0)
    }

    override fun onInterrupt() {}
}
