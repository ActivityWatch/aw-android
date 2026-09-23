package net.activitywatch.android.watcher

import org.threeten.bp.Duration
import org.threeten.bp.Instant

internal data class CompletedBrowserSession(
    val url: String,
    val browser: String,
    val title: String,
    val audible: Boolean,
    val start: Instant,
    val duration: Duration
)

// Pure session/state-machine logic extracted out of WebWatcher so it can be unit-tested
// (mobile/src/test) without an AccessibilityService or device/emulator.
internal class BrowserSessionTracker(
    private val now: () -> Instant = { Instant.ofEpochMilli(System.currentTimeMillis()) }
) {
    private var lastUrlTimestamp: Instant? = null
    private var lastUrl: String? = null
    private var lastBrowser: String? = null
    private var lastWindowTitle: String? = null
    private var lastAudible: Boolean = false

    // Returns the just-completed session (previous url/browser/title) when the url or
    // browser changes, so the caller can log it. We wait for the url to change before
    // logging so we have a chance to receive the page title, which often only arrives
    // after the page loads and/or the user interacts with it.
    fun handleUrl(newUrl: String?, newBrowser: String?, audible: Boolean = false): CompletedBrowserSession? {
        // Same page: nothing to log for the url, but playback may have started/stopped.
        if (newUrl == lastUrl && newBrowser == lastBrowser) return handleAudible(audible)

        val completed = completeCurrentSession()

        lastUrlTimestamp = now()
        lastUrl = newUrl
        lastBrowser = newBrowser
        lastWindowTitle = null
        lastAudible = audible
        return completed
    }

    // Splits the current session when its audible state flips, so the logged events carry
    // the right `audible` value for each stretch of time (like the desktop web watcher,
    // where a data change starts a new event). The url, browser and title carry over into
    // the new session because it's still the same page.
    fun handleAudible(audible: Boolean): CompletedBrowserSession? {
        if (lastUrl == null || audible == lastAudible) return null

        val completed = completeCurrentSession()

        lastUrlTimestamp = now()
        lastAudible = audible
        return completed
    }

    // Returns true when the title actually changed (so the caller can log it).
    fun handleWindowTitle(newWindowTitle: String): Boolean {
        if (newWindowTitle == lastWindowTitle) return false
        lastWindowTitle = newWindowTitle
        return true
    }

    private fun completeCurrentSession(): CompletedBrowserSession? {
        val url = lastUrl ?: return null
        val browser = lastBrowser ?: return null
        val start = lastUrlTimestamp!!
        return CompletedBrowserSession(
            url = url,
            browser = browser,
            title = lastWindowTitle ?: "",
            audible = lastAudible,
            start = start,
            // Clock can step backward (NTP sync, manual change) between `start` and now;
            // don't report a negative duration in that case.
            duration = Duration.between(start, now()).coerceAtLeast(Duration.ZERO)
        )
    }
}
