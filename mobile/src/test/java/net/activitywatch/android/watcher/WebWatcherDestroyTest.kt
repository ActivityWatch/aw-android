package net.activitywatch.android.watcher

import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

// aw-android#409: the visit in progress is written only when the url changes, so
// destroying the service while a page is open must queue it before shutting the writer down.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class WebWatcherDestroyTest {
    private val browser = "org.example.browser"
    // Do not call onCreate: the native datastore and installed-package query are unrelated.
    private val service = Robolectric.buildService(WebWatcher::class.java).get()

    private class RecordingWriter : ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue()
    ) {
        var submitted = 0
        override fun execute(command: Runnable) {
            submitted++
        }
    }

    private fun field(name: String) =
        WebWatcher::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun installRecordingWriter(): RecordingWriter =
        RecordingWriter().also { field("writer").set(service, it) }

    private fun tracker() = field("sessionTracker").get(service) as BrowserSessionTracker

    // onDestroy unregisters the receiver onCreate registers; register it as onCreate would.
    private fun registerPackageReceiver() {
        val receiver = field("packageChangeReceiver").get(service) as BroadcastReceiver
        service.registerReceiver(receiver, IntentFilter(Intent.ACTION_PACKAGE_ADDED))
    }

    @Test
    fun `destroying the service queues the open browser visit`() {
        val writer = installRecordingWriter()
        registerPackageReceiver()
        tracker().handleUrl("https://example.org/page", browser)

        service.onDestroy()

        assertEquals(1, writer.submitted)
        assertEquals(null, tracker().currentBrowser)
    }

    @Test
    fun `destroying the service with no open visit writes nothing`() {
        val writer = installRecordingWriter()
        registerPackageReceiver()

        service.onDestroy()

        assertEquals(0, writer.submitted)
    }
}
