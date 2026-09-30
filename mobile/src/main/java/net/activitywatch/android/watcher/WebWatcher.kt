package net.activitywatch.android.watcher

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import net.activitywatch.android.RustInterface
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import net.activitywatch.android.AWPreferences
import org.json.JSONObject

private fun extractTextByViewId(event: AccessibilityEvent, viewId: String): String? {
    event.source?.let { source ->
        val nodes = source.findAccessibilityNodeInfosByViewId(viewId)
        try {
            return processExtractedText(nodes.firstOrNull()?.text?.toString())
        } finally {
            nodes.forEach { it.recycle() }
            source.recycle()
        }
    }
    return null
}

class WebWatcher : AccessibilityService() {

    // The toolbar is a sibling of the content area, so we search from the window root.
    // findAccessibilityNodeInfosByViewId requires "package:id/name" format and silently
    // rejects bare testTag names, so we traverse manually.
    private fun extractFirefoxUrl(event: AccessibilityEvent): String? {
        val root = rootInActiveWindow ?: return null
        // In split screen the active window can be another browser; its URL bar would be
        // logged under this event's package (and teach BrowserProbeMemory a wrong style).
        if (root.packageName != event.packageName) {
            root.recycle()
            return null
        }
        try {
            val found = findNode(root) { it.viewIdResourceName == "ADDRESSBAR_URL_BOX" }
            val result = parseFirefoxAddressBarContentDescription(found?.contentDescription?.toString())
            if (found !== root) found?.recycle()
            return result
        } finally {
            root.recycle()
        }
    }

    private val TAG = "WebWatcher"
    private val bucket_id = "aw-watcher-android-web"
    private val lastDiagnosticDump = mutableMapOf<String, Long>()

    @Volatile private var ri : RustInterface? = null
    private var lastWindowId: Int? = null
    private val sessionTracker = BrowserSessionTracker()

    // Installed apps that can open https links, other than the built-in browsers. Looked
    // up via PackageManager instead of scanning each app's accessibility tree, so events
    // from non-browser apps stay a set lookup.
    // Package -> versionCode, so a browser update re-learns its URL bar style.
    @Volatile private var detectedBrowsers: Map<String, Long> = emptyMap()
    private lateinit var probeMemory: BrowserProbeMemory
    // PackageManager queries can be slow; the service thread must keep handling events.
    // One thread keeps refreshes in order, so the newest result is published last.
    private val detectionExecutor = Executors.newSingleThreadExecutor()

    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "Package change (${intent.action} ${intent.data}), re-detecting browsers")
            detectionExecutor.execute { refreshDetectedBrowsers() }
        }
    }

    private fun extractChromiumUrl(packageName: String, event: AccessibilityEvent): String? =
        extractTextByViewId(event, "$packageName:id/url_bar")

    private fun extractGeckoUrl(packageName: String, event: AccessibilityEvent): String? =
        // Compose toolbar (current)
        extractFirefoxUrl(event)
            // View-based toolbar (older Firefox versions)
            ?: extractTextByViewId(event, "$packageName:id/url_bar_title")
            ?: extractTextByViewId(event, "$packageName:id/mozac_browser_toolbar_url_view")

    // Applies stripProtocol uniformly to whatever extractor matched, so the logged url is
    // formatted identically no matter which browser/view-variant produced it.
    private fun extractUrl(packageName: String, event: AccessibilityEvent): String? = when (packageName) {
        "com.android.chrome" -> extractTextByViewId(event, "com.android.chrome:id/url_bar")
        "org.mozilla.firefox" ->
            // Compose toolbar (current)
            extractFirefoxUrl(event)
                // View-based toolbar (older Firefox versions)
                ?: extractTextByViewId(event, "org.mozilla.firefox:id/url_bar_title")
                ?: extractTextByViewId(event, "org.mozilla.firefox:id/mozac_browser_toolbar_url_view")
        "com.sec.android.app.sbrowser" ->
            extractTextByViewId(event, "com.sec.android.app.sbrowser:id/location_bar_edit_text")
                ?: extractTextByViewId(event, "com.sec.android.app.sbrowser:id/custom_tab_toolbar_url_bar_text")
        "com.opera.browser" ->
            extractTextByViewId(event, "com.opera.browser:id/url_field")
                ?: extractTextByViewId(event, "com.opera.browser:id/address_field")
        "com.microsoft.emmx" -> extractTextByViewId(event, "com.microsoft.emmx:id/url_bar")
        in detectedBrowsers -> probeMemory.extract(packageName, detectedBrowsers.getValue(packageName), System.currentTimeMillis()) {
            when (it) {
                UrlBarStyle.CHROMIUM -> extractChromiumUrl(packageName, event)
                UrlBarStyle.GECKO -> extractGeckoUrl(packageName, event)
            }
        }
        else -> null
    }?.let(stripProtocol)

    private fun refreshDetectedBrowsers() {
        // A hostless https URI matches only generic handlers, not apps claiming their own
        // domains (App Links). QUERY_ALL_PACKAGES makes every such app visible.
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://")).addCategory(Intent.CATEGORY_BROWSABLE)
        val resolved = try {
            packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL).map { it.activityInfo.packageName }
        } catch (ex: RuntimeException) {
            Log.e(TAG, "Failed to query browsers: ${ex.message}")
            return
        }
        detectedBrowsers = selectDetectedBrowsers(resolved, KNOWN_BROWSER_PACKAGES, packageName)
            .associateWith { versionCodeOf(it) }
        probeMemory.retain(detectedBrowsers.keys)
        Log.i(TAG, "Detected browsers without a dedicated extractor: ${detectedBrowsers.keys}")
    }

    private fun versionCodeOf(pkg: String): Long = try {
        PackageInfoCompat.getLongVersionCode(packageManager.getPackageInfo(pkg, 0))
    } catch (ex: PackageManager.NameNotFoundException) {
        -1L
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Creating WebWatcher")
        val prefs = AWPreferences(this)
        probeMemory = BrowserProbeMemory(prefs.getBrowserProbeState(), prefs::setBrowserProbeState)
        detectionExecutor.execute { refreshDetectedBrowsers() }
        ContextCompat.registerReceiver(
            this,
            packageChangeReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // createBucketHelper() blocks on the datastore worker. Doing that on the
        // accessibility service's main thread produced "Executing service
        // WebWatcher" ANRs whenever the worker was busy (aw-android#261), so
        // initialize off the main thread; events arriving earlier are dropped by
        // the null-safe ri?. calls, same as MediaWatcher.
        thread(name = "WebWatcher-init") {
            try {
                ri = RustInterface(applicationContext).also { it.createBucketHelper(bucket_id, "web.tab.current") }
            } catch (ex: Throwable) {
                // Catch Throwable (not just Exception) because System.loadLibrary() throws
                // UnsatisfiedLinkError (an Error subclass) when the native library is missing.
                Log.e(TAG, "Failed to initialize RustInterface: ${ex.message}")
            }
        }
    }

    // TODO: This method is called very often, which might affect performance. Future optimizations needed.
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (shouldIgnoreEvent(event)) {
            return
        }

        val packageName = event.packageName?.toString()
        // A backed-off package is handled like any non-browser app, which also skips the
        // WebView walk below.
        val isKnownBrowser = packageName != null && (packageName in KNOWN_BROWSER_PACKAGES ||
            detectedBrowsers[packageName]?.let { probeMemory.isActive(packageName, it, System.currentTimeMillis()) } == true)

        val windowChanged = windowChanged(event.windowId)
        lastWindowId = event.windowId

        if (!isKnownBrowser) {
            // for some browsers like Firefox event.packageName can be null (no extractor matched)
            // but we are still on the same window
            if (windowChanged) {
                Log.i(TAG, "Window changed away from a tracked browser (new package: $packageName); ending session")
                handleUrl(null, newBrowser = null)
            }

            return
        }

        try {
            event.source?.let { source ->
                try {
                    val browser = packageName!!
                    val newUrl = extractUrl(browser, event)

                    if (newUrl == null) {
                        maybeDumpTree(browser)
                    } else {
                        handleUrl(newUrl, newBrowser = browser)
                    }
                    findWebView(source)?.let { webView ->
                        handleWindowTitle(webView.text.toString())
                        if (webView !== source) webView.recycle()
                    }
                } finally {
                    source.recycle()
                }
            }
        } catch(ex : Exception) {
            Log.e(TAG, ex.message ?: ex.toString())
        }
    }

    private fun windowChanged(windowId: Int): Boolean = windowId != lastWindowId

    private fun shouldIgnoreEvent(event: AccessibilityEvent) =
        event.packageName == "com.android.systemui"

    // TODO(maintainer): this never finds a match for Firefox, so its page title is never
    // captured (logged events show title:""). Confirmed live on-device (2026-07-01, Fenix,
    // GeckoView content): dumping the full accessibility tree of rootInActiveWindow while a
    // real page was loaded showed `browserLayout` has exactly 3 children - a ComposeView
    // (whose only child had zero further descendants), an unexplained childless node with a
    // null className, and the toolbar (composable_toolbar, which is where ADDRESSBAR_URL_BOX
    // lives - see extractFirefoxUrl above). The page content is not a descendant of this
    // window's root at all, so no restructuring of findWebView's search root will find it.
    // GeckoView most likely exposes its content as a *separate* accessibility window rather
    // than as nodes within this one. Fixing this needs AccessibilityService#getWindows() to
    // be enumerated to find the window that hosts GeckoView's content (if any - it's also
    // possible the title isn't exposed as an accessibility node at all, e.g. only via a
    // window title property), which is a bigger change than this function's shape allows for.
    private fun findWebView(info: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findNode(info) { it.className == "android.webkit.WebView" && it.text != null }

    // Dumps the accessibility tree to logcat at debug level, rate-limited to once per minute
    // per browser. Helps diagnose URL extraction failures when adding support for new browsers
    // or browser versions that have changed their view hierarchy. Enable with:
    //   adb shell setprop log.tag.WebWatcher DEBUG
    private fun maybeDumpTree(packageName: String) {
        if (!Log.isLoggable(TAG, Log.DEBUG)) return
        val now = System.currentTimeMillis()
        if (now - (lastDiagnosticDump[packageName] ?: 0L) < 60_000L) return
        lastDiagnosticDump[packageName] = now
        val root = rootInActiveWindow ?: return
        Log.d(TAG, "URL extraction failed for $packageName — accessibility tree:")
        try {
            forEachNode(root) { node, depth ->
                val id = node.viewIdResourceName ?: ""
                val cd = node.contentDescription?.toString()?.take(120) ?: ""
                val text = node.text?.toString()?.take(120) ?: ""
                if (id.isNotEmpty() || cd.isNotEmpty() || text.isNotEmpty()) {
                    Log.d(TAG, "${"  ".repeat(depth)}class=${node.className} id=$id cd=\"$cd\" text=\"$text\"")
                }
            }
        } finally {
            root.recycle()
        }
    }

    private fun handleUrl(newUrl : String?, newBrowser: String?) {
        newUrl?.let { Log.i(TAG, "Url: $it, browser: $newBrowser") }
        sessionTracker.handleUrl(newUrl, newBrowser)?.let { logBrowserEvent(it) }
    }

    private fun handleWindowTitle(newWindowTitle: String) {
        if (sessionTracker.handleWindowTitle(newWindowTitle)) {
            Log.i(TAG, "Title: $newWindowTitle")
        }
    }

    private fun logBrowserEvent(session: CompletedBrowserSession) {
        val data = JSONObject()
            .put("url", session.url)
            .put("browser", session.browser)
            .put("title", session.title)
            .put("audible", false) // TODO
            .put("incognito", false) // TODO

        Log.i(TAG, "Registered event: $data")
        ri?.heartbeatHelper(bucket_id, session.start, session.duration.seconds.toDouble(), data, 1.0)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        unregisterReceiver(packageChangeReceiver)
        detectionExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        internal val KNOWN_BROWSER_PACKAGES = setOf(
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.sec.android.app.sbrowser",
            "com.opera.browser",
            "com.microsoft.emmx"
        )
    }
}
