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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowMediaController
import org.robolectric.annotation.LooperMode
import org.threeten.bp.Instant
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class LiveWatcherPauseTest {
    private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private val prefs get() = AWPreferences(RuntimeEnvironment.getApplication())

    private fun pause() {
        assertTrue("Pause must persist before exercising private callbacks", prefs.setLiveCollectionPaused(true))
    }

    private fun resume() {
        android.os.SystemClock.setCurrentTimeMillis(System.currentTimeMillis() + 1_000L)
        assertTrue("Resume must succeed before exercising public callbacks", prefs.setLiveCollectionPaused(false))
    }

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
            pause()
            assertNull(tracker.currentBrowser)
            browserEvent(service, "private.example")
            assertNull(tracker.currentBrowser)
            resume()
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
            pause()
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
        val callback = service.javaClass.getDeclaredMethod(
            "createMediaCallback", android.media.session.MediaController::class.java
        ).apply { isAccessible = true }.invoke(service, session.controller) as android.media.session.MediaController.Callback
        fun observe(title: String) {
            val state = PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f).build()
            val metadata = MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title).build()
            shadowOf(session.controller).setPlaybackState(state)
            shadowOf(session.controller).setMetadata(metadata)
            callback.onMetadataChanged(metadata)
        }
        try {
            observe("before")
            pause()
            // Run a callback BEFORE the queued preference reset, as on a busy media looper.
            observe("private")
            segments.endAll(Instant.now().plusSeconds(5))
            assertTrue(emitted.isEmpty())
            resume()
            shadowOf(Looper.getMainLooper()).idle()
            observe("after")
            segments.endAll(Instant.now().plusSeconds(5))
            assertEquals(listOf("after"), emitted)

            // A quick pause/resume whose posted resets were canceled by disconnect still
            // invalidates the open segment; it cannot span the private interval.
            emitted.clear()
            observe("before-quick-pause")
            pause()
            resume()
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

    @Test
    fun mediaPausedPollsKeepCallbacksUntilGenerationChanges() {
        val context = RuntimeEnvironment.getApplication()
        val service = Robolectric.buildService(MediaWatcher::class.java).get()
        val emitted = mutableListOf<String>()
        val segments = MediaPlaybackSegments<MediaSession.Token> { _, _, data -> emitted += data.getString("title") }
        field(service, "segments").set(service, segments)
        field(service, "connected").set(service, true)
        val session = MediaSession(context, "paused-poll-test")
        val controller = session.controller
        shadowOf(controller).setPackageName(context.packageName)
        shadowOf(controller).setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
        shadowOf(controller).setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "public").build())
        @Suppress("UNCHECKED_CAST")
        val controllers = field(service, "activeControllers").get(service) as MutableMap<MediaSession.Token, android.media.session.MediaController>
        @Suppress("UNCHECKED_CAST")
        val callbacks = field(service, "activeCallbacks").get(service) as MutableMap<MediaSession.Token, android.media.session.MediaController.Callback>
        controllers[controller.sessionToken] = controller
        val poll = service.javaClass.getDeclaredMethod("pollActiveSessions").apply { isAccessible = true }
        try {
            pause()
            poll.invoke(service)
            val pausedCallback = callbacks[controller.sessionToken]
            assertNotNull("Entering a new generation must replace callback registrations", pausedCallback)
            repeat(3) { poll.invoke(service) }
            assertSame("Paused polls must not replace the same generation's callback", pausedCallback, callbacks[controller.sessionToken])
            segments.endAll(Instant.now().plusSeconds(5))
            assertTrue("Paused polls must not collect playback", emitted.isEmpty())

            resume()
            poll.invoke(service)
            assertNotSame("Resume must replace the paused generation's callback", pausedCallback, callbacks[controller.sessionToken])
            segments.endAll(Instant.now().plusSeconds(5))
            assertEquals(listOf("public"), emitted)
        } finally {
            callbacks[controller.sessionToken]?.let { controller.unregisterCallback(it) }
            session.release()
            (field(service, "handlerThread").get(service) as android.os.HandlerThread).quitSafely()
        }
    }

    @Test
    fun mediaDelayedPlaybackCallbacksPreserveTransitionsWithoutCollectionPause() {
        val context = RuntimeEnvironment.getApplication()
        val service = Robolectric.buildService(MediaWatcher::class.java).get()
        val emitted = mutableListOf<String>()
        val segments = MediaPlaybackSegments<MediaSession.Token> { _, _, data -> emitted += data.getString("state") }
        field(service, "segments").set(service, segments)
        field(service, "connected").set(service, true)
        val session = MediaSession(context, "delayed-playback-test")
        val controller = session.controller
        shadowOf(controller).setPackageName(context.packageName)
        shadowOf(controller).setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "public").build())
        val playing = PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f).build()
        val paused = PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 0, 0f).build()
        shadowOf(controller).setPlaybackState(playing)
        val callback = service.javaClass.getDeclaredMethod("createMediaCallback", android.media.session.MediaController::class.java)
            .apply { isAccessible = true }.invoke(service, controller) as android.media.session.MediaController.Callback
        try {
            // The current controller has already resumed by the time both callbacks drain.
            callback.onPlaybackStateChanged(paused)
            callback.onPlaybackStateChanged(playing)
            segments.endAll(Instant.now().plusSeconds(5))
            assertEquals(listOf("paused", "playing"), emitted)
        } finally {
            session.release()
            (field(service, "handlerThread").get(service) as android.os.HandlerThread).quitSafely()
        }
    }

    @Implements(android.media.session.MediaController::class)
    class RacingMediaController : ShadowMediaController() {
        var duringMetadataRead: (() -> Unit)? = null

        @Implementation
        public override fun getMetadata(): MediaMetadata? {
            val result = super.getMetadata()
            val race = duringMetadataRead
            duringMetadataRead = null
            race?.invoke()
            return result
        }
    }

    @Test
    fun mediaQueuedCallbacksDoNotReplayPrivatePayloadsAfterResume() {
        val context = RuntimeEnvironment.getApplication()
        val service = Robolectric.buildService(MediaWatcher::class.java).get()
        val emitted = mutableListOf<String>()
        val segments = MediaPlaybackSegments<MediaSession.Token> { _, _, data -> emitted += data.getString("title") }
        field(service, "segments").set(service, segments)
        field(service, "connected").set(service, true)
        val session = MediaSession(context, "queued-pause-test")
        val controller = session.controller
        shadowOf(controller).setPackageName(context.packageName)
        val callback = service.javaClass.getDeclaredMethod("createMediaCallback", android.media.session.MediaController::class.java)
            .apply { isAccessible = true }.invoke(service, controller) as android.media.session.MediaController.Callback
        fun metadata(title: String) = MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title).build()
        try {
            pause()
            val privateMetadata = metadata("private")
            val privatePlayback = PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 0, 0f).build()
            // Model payloads already queued on a blocked media handler while collection is paused.
            val queuedMetadata = Runnable { callback.onMetadataChanged(privateMetadata) }
            val queuedPlayback = Runnable { callback.onPlaybackStateChanged(privatePlayback) }
            resume()
            shadowOf(controller).setMetadata(metadata("after"))
            shadowOf(controller).setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
            queuedMetadata.run()
            queuedPlayback.run()
            segments.endAll(Instant.now().plusSeconds(5))
            assertEquals(listOf("after"), emitted)
        } finally {
            session.release()
            (field(service, "handlerThread").get(service) as android.os.HandlerThread).quitSafely()
        }
    }

    @Test
    @Config(shadows = [RacingMediaController::class])
    fun mediaPollDiscardsReadThatCrossesPauseResume() {
        val context = RuntimeEnvironment.getApplication()
        val service = Robolectric.buildService(MediaWatcher::class.java).get()
        val emitted = mutableListOf<String>()
        val segments = MediaPlaybackSegments<MediaSession.Token> { _, _, data -> emitted += data.getString("title") }
        field(service, "segments").set(service, segments)
        field(service, "connected").set(service, true)
        val session = MediaSession(context, "racing-pause-test")
        val controller = session.controller
        val shadow = Shadow.extract<RacingMediaController>(controller)
        shadow.setPackageName(context.packageName)
        shadow.setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
        shadow.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "private").build())
        @Suppress("UNCHECKED_CAST")
        val controllers = field(service, "activeControllers").get(service) as MutableMap<MediaSession.Token, android.media.session.MediaController>
        controllers[controller.sessionToken] = controller
        shadow.duringMetadataRead = {
            pause()
            resume()
        }
        val poll = service.javaClass.getDeclaredMethod("pollActiveSessions").apply { isAccessible = true }
        try {
            poll.invoke(service)
            segments.endAll(Instant.now().plusSeconds(5))
            assertTrue("Observation spanning the private interval must be discarded: $emitted", emitted.isEmpty())
            shadow.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "after").build())
            poll.invoke(service)
            segments.endAll(Instant.now().plusSeconds(5))
            assertEquals(listOf("after"), emitted)
        } finally {
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
