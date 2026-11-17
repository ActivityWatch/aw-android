package net.activitywatch.android.watcher

import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class BrowserProbeRetryTest {
    private val browser = "org.example.browser"
    // Do not call onCreate: the native datastore and installed-package query are unrelated.
    private val service = Robolectric.buildService(WebWatcher::class.java).get()
    private val memory = BrowserProbeMemory(null) {}

    private fun setField(name: String, value: Any) {
        WebWatcher::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }

    private fun tracker(): BrowserSessionTracker =
        WebWatcher::class.java.getDeclaredField("sessionTracker").apply { isAccessible = true }
            .get(service) as BrowserSessionTracker

    private fun root(pkg: String, url: String? = null): AccessibilityNodeInfo =
        AccessibilityNodeInfo.obtain().apply {
            packageName = pkg
            if (url != null) {
                val bar = AccessibilityNodeInfo.obtain().apply {
                    viewIdResourceName = "ADDRESSBAR_URL_BOX"
                    contentDescription = "$url. Search or enter address"
                }
                shadowOf(this).addChild(bar)
            }
            // Framework by-id queries require service-delivered (sealed) nodes.
            AccessibilityNodeInfo::class.java.getDeclaredMethod("setSealed", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(this, true)
        }

    private fun event() = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
        packageName = browser
    }

    private fun beginBackoff() {
        setField("probeMemory", memory)
        setField("detectedBrowsers", mapOf(browser to 1L))
        shadowOf(service).setRootInActiveWindow(root(browser))
        val first = event()
        assertEquals(browser, first.packageName.toString())
        service.onAccessibilityEvent(first)
        org.junit.Assert.assertTrue(memory.retryDelayMs(browser, 1L, System.currentTimeMillis())!! in 1L..1_000L)
        assertNull(tracker().currentBrowser)
    }

    @Test
    fun `quiet first page is read when backoff expires without another event`() {
        beginBackoff()
        shadowOf(service).setRootInActiveWindow(root(browser, "example.org"))
        // Probe state persists wall-clock deadlines; idling the looper advances uptime only.
        Thread.sleep(1_100)
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        assertEquals(org.robolectric.shadows.ShadowLog.getLogs().toString(), browser, tracker().currentBrowser)
        assertEquals("example.org", tracker().handleUrl(null, null)?.url)
    }

    @Test
    fun `retry does not attribute another apps tree to the browser`() {
        beginBackoff()
        shadowOf(service).setRootInActiveWindow(root("org.other.app", "wrong.example"))
        Thread.sleep(1_100)
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        assertNull(tracker().currentBrowser)
    }
}
