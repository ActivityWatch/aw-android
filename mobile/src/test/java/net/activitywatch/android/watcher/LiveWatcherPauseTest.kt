package net.activitywatch.android.watcher

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.content.SharedPreferences
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import net.activitywatch.android.AWPreferences
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.threeten.bp.Instant
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class LiveWatcherPauseTest {
    private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private val prefs get() = AWPreferences(RuntimeEnvironment.getApplication())

    @Test
    fun webDropsOpenVisitAndIgnoresPrivateEventsThenStartsFresh() {
        // No onCreate: JNI initialization is unrelated to collection and unavailable in JVM tests.
        val service = Robolectric.buildService(WebWatcher::class.java).get()
        val listener = field(service, "pauseListener").get(service) as SharedPreferences.OnSharedPreferenceChangeListener
        prefs.registerListener(listener)
        val tracker = field(service, "sessionTracker").get(service) as BrowserSessionTracker
        try {
            tracker.handleUrl("before.example", "org.mozilla.firefox")
            tracker.handleWindowTitle("Before")
            prefs.setLiveCollectionPaused(true)
            assertNull(tracker.currentBrowser)
            browserEvent(service, "private.example")
            assertNull(tracker.currentBrowser)
            prefs.setLiveCollectionPaused(false)
            browserEvent(service, "after.example")
            val completed = tracker.handleUrl(null, null)!!
            assertEquals("after.example", completed.url)
            assertEquals("", completed.title)
        } finally {
            prefs.unregisterListener(listener)
        }
    }

    @Test
    fun webPauseCancelsPendingTitleLookup() {
        val service = Robolectric.buildService(WebWatcher::class.java).get()
        val listener = field(service, "pauseListener").get(service) as SharedPreferences.OnSharedPreferenceChangeListener
        prefs.registerListener(listener)
        try {
            val tracker = field(service, "sessionTracker").get(service) as BrowserSessionTracker
            tracker.handleUrl("before.example", "com.android.chrome")
            service.javaClass.getDeclaredMethod("scheduleTitleLookup", String::class.java, Long::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(service, "com.android.chrome", 500L)
            assertNotNull(field(service, "titleFollowUp").get(service))
            prefs.setLiveCollectionPaused(true)
            shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
            assertNull(field(service, "titleFollowUp").get(service))
            assertNull(tracker.currentBrowser)
        } finally {
            prefs.unregisterListener(listener)
        }
    }

    @Test
    fun mediaDropsBufferedPlaybackAndDoesNotRecordPausedCallbacks() {
        val context = RuntimeEnvironment.getApplication()
        val service = Robolectric.buildService(MediaWatcher::class.java).get()
        val emitted = mutableListOf<String>()
        val segments = MediaPlaybackSegments<MediaSession.Token> { _, _, data -> emitted += data.getString("title") }
        field(service, "segments").set(service, segments)
        field(service, "handler").set(service, Handler(Looper.getMainLooper()))
        field(service, "connected").set(service, true)
        val listener = field(service, "pauseListener").get(service) as SharedPreferences.OnSharedPreferenceChangeListener
        prefs.registerListener(listener)
        val session = MediaSession(context, "pause-test")
        shadowOf(session.controller).setPackageName(context.packageName)
        val playback = service.javaClass.getDeclaredMethod(
            "handlePlaybackChange", android.media.session.MediaController::class.java,
            PlaybackState::class.java, MediaMetadata::class.java
        ).apply { isAccessible = true }
        fun observe(title: String) {
            playback.invoke(service, session.controller,
                PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f).build(),
                MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title).build())
        }
        try {
            observe("before")
            prefs.setLiveCollectionPaused(true)
            // Run a callback BEFORE the queued preference reset, as on a busy media looper.
            observe("private")
            segments.endAll(Instant.now().plusSeconds(5))
            assertTrue(emitted.isEmpty())
            prefs.setLiveCollectionPaused(false)
            shadowOf(Looper.getMainLooper()).idle()
            observe("after")
            segments.endAll(Instant.now().plusSeconds(5))
            assertEquals(listOf("after"), emitted)

            // A quick pause/resume whose posted resets were canceled by disconnect still
            // invalidates the open segment; it cannot span the private interval.
            emitted.clear()
            observe("before-quick-pause")
            prefs.setLiveCollectionPaused(true)
            prefs.setLiveCollectionPaused(false)
            (field(service, "handler").get(service) as Handler).removeCallbacksAndMessages(null)
            observe("after-quick-pause")
            segments.endAll(Instant.now().plusSeconds(5))
            assertEquals(listOf("after-quick-pause"), emitted)
        } finally {
            prefs.unregisterListener(listener)
            session.release()
            (field(service, "handlerThread").get(service) as android.os.HandlerThread).quitSafely()
        }
    }

    private fun browserEvent(service: WebWatcher, url: String) {
        val root = AccessibilityNodeInfo.obtain().apply {
            packageName = "org.mozilla.firefox"
            val bar = AccessibilityNodeInfo.obtain().apply {
                viewIdResourceName = "ADDRESSBAR_URL_BOX"
                contentDescription = "$url. Search or enter address"
            }
            shadowOf(this).addChild(bar)
        }
        shadowOf(service).setRootInActiveWindow(root)
        service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
            packageName = "org.mozilla.firefox"
        })
    }
}
