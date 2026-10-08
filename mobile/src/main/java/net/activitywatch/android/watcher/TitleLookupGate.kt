package net.activitywatch.android.watcher

import android.os.SystemClock

// Minimum time between page-title lookups while the current page's title is unknown, and
// once it has been found (so in-page title changes are still picked up).
internal const val TITLE_LOOKUP_MS = 500L
internal const val TITLE_RECHECK_MS = 5_000L

// WebView title lookups walk the accessibility tree (up to MAX_TRAVERSAL_NODES binder calls
// on the service's main thread), and content-change events arrive up to every 100ms while a
// page is open. This decides when a lookup may run.
internal class TitleLookupGate(
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    private var lastLookup: Long? = null

    /**
     * Milliseconds to wait before the next lookup: 0 means look up now (and the lookup is
     * recorded), null means never for this browser.
     */
    fun delayBeforeLookup(browser: String, hasTitle: Boolean): Long? {
        // See WebWatcher.findWebView: the lookup never matches Firefox.
        if (browser == FIREFOX_PACKAGE) return null
        val current = now()
        val interval = if (hasTitle) TITLE_RECHECK_MS else TITLE_LOOKUP_MS
        val wait = lastLookup?.let { interval - (current - it) } ?: 0L
        if (wait > 0) return wait
        lastLookup = current
        return 0L
    }

    companion object {
        const val FIREFOX_PACKAGE = "org.mozilla.firefox"
    }
}
