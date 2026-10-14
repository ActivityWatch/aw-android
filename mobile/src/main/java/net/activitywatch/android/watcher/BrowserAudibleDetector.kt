package net.activitywatch.android.watcher

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.util.Log

// Reports whether a browser is currently playing audio, for the `audible` field on
// aw-watcher-android-web events. A browser is considered audible when it owns a media
// session in STATE_PLAYING (Chrome/Firefox publish one for page audio/video). That needs
// the MediaWatcher notification-listener access; without it the answer is always false.
//
// AudioManager.isMusicActive() is deliberately not used as a fallback: it's device-wide,
// so a music app in the background would mark silent browser sessions audible, and
// aw-webui treats audible browser events as not-AFK evidence.
internal class BrowserAudibleDetector(context: Context) {
    private val TAG = "BrowserAudibleDetector"
    private val appContext = context.applicationContext
    private val listenerComponent = ComponentName(appContext, MediaWatcher::class.java)
    private val sessionManager =
        appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager

    private var cachedBrowser: String? = null
    private var cachedAt = 0L
    private var cachedResult = false

    // onAccessibilityEvent runs on the service's main thread and fires many times a second
    // while scrolling; getActiveSessions is a binder IPC that also builds a MediaController
    // per session, and blocking that thread is what produced the WebWatcher ANRs in
    // aw-android#261. So the answer is cached briefly instead of being recomputed per event.
    fun isAudible(browserPackage: String): Boolean {
        val nowMs = SystemClock.elapsedRealtime()
        if (browserPackage == cachedBrowser && nowMs - cachedAt < CACHE_MS) return cachedResult

        cachedResult = browserHasPlayingSession(browserPackage)
        cachedBrowser = browserPackage
        cachedAt = nowMs
        return cachedResult
    }

    private fun browserHasPlayingSession(browserPackage: String): Boolean {
        val manager = sessionManager ?: return false
        if (!MediaWatcher.isNotificationAccessGranted(appContext)) return false
        return try {
            manager.getActiveSessions(listenerComponent).any { controller ->
                controller.packageName == browserPackage &&
                    controller.playbackState?.state == PlaybackState.STATE_PLAYING
            }
        } catch (e: SecurityException) {
            // Access can be revoked between the settings check and the call.
            Log.w(TAG, "Media session access denied: ${e.message}")
            false
        }
    }

    companion object {
        private const val CACHE_MS = 1000L
    }
}
