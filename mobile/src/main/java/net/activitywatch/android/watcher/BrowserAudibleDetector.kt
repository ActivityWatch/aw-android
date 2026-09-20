package net.activitywatch.android.watcher

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.util.Log

// Decides the `audible` value for a browser session from the two signals we can get:
//
//  * `browserPlaying`: whether the browser package itself owns a media session that is
//    currently playing. This is the precise signal (Chrome/Firefox publish a media session
//    for page audio/video) but it needs the MediaWatcher notification-listener access,
//    so it's null when that isn't granted.
//  * `musicActive`: AudioManager.isMusicActive(), which is global (any app) and so can't
//    tell browser audio apart from e.g. a music app playing in the background. Only used
//    as a coarse fallback when the precise signal is unavailable.
internal fun resolveAudible(browserPlaying: Boolean?, musicActive: Boolean): Boolean =
    browserPlaying ?: musicActive

internal class BrowserAudibleDetector(context: Context) {
    private val TAG = "BrowserAudibleDetector"
    private val appContext = context.applicationContext
    private val listenerComponent = ComponentName(appContext, MediaWatcher::class.java)
    private val sessionManager =
        appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var cachedBrowser: String? = null
    private var cachedAt = 0L
    private var cachedResult = false

    // onAccessibilityEvent fires many times a second while scrolling; getActiveSessions is a
    // binder call, so the answer is cached briefly instead of being recomputed per event.
    fun isAudible(browserPackage: String): Boolean {
        val nowMs = SystemClock.elapsedRealtime()
        if (browserPackage == cachedBrowser && nowMs - cachedAt < CACHE_MS) return cachedResult

        cachedResult = resolveAudible(
            browserPlaying = browserHasPlayingSession(browserPackage),
            musicActive = audioManager?.isMusicActive == true,
        )
        cachedBrowser = browserPackage
        cachedAt = nowMs
        return cachedResult
    }

    private fun browserHasPlayingSession(browserPackage: String): Boolean? {
        val manager = sessionManager ?: return null
        if (!MediaWatcher.isNotificationAccessGranted(appContext)) return null
        return try {
            manager.getActiveSessions(listenerComponent).any { controller ->
                controller.packageName == browserPackage &&
                    controller.playbackState?.state == PlaybackState.STATE_PLAYING
            }
        } catch (e: SecurityException) {
            // Access can be revoked between the settings check and the call.
            Log.w(TAG, "Media session access denied: ${e.message}")
            null
        }
    }

    companion object {
        private const val CACHE_MS = 1000L
    }
}
