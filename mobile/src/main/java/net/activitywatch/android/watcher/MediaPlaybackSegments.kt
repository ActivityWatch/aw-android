package net.activitywatch.android.watcher

import org.json.JSONObject
import org.threeten.bp.Duration
import org.threeten.bp.Instant

// Longest stretch of playback held in memory before it is written. Bounds how much is
// lost if the process dies mid-track, and how stale the bucket is during a long episode.
internal val MEDIA_SEGMENT_MAX_LENGTH: Duration = Duration.ofMinutes(5)

/**
 * Turns per-player playback observations into discrete events.
 *
 * Playback used to be sent as heartbeats, but the server only merges a heartbeat into the
 * newest event in the bucket. With two players active, their heartbeats alternated, never
 * merged, and every poll inserted new zero-duration events. Instead each player's playing
 * time is tracked as an open segment with a known start and written once, with its real
 * duration, when the track or state changes, the session ends, or the segment reaches
 * [MEDIA_SEGMENT_MAX_LENGTH].
 *
 * Not thread-safe; callers serialize access.
 */
internal class MediaPlaybackSegments(
    private val emit: (start: Instant, durationSeconds: Double, data: JSONObject) -> Unit,
) {
    private class Segment(val key: String, val data: JSONObject, val start: Instant)

    private val open = HashMap<String, Segment>()
    private val lastStateKeys = HashMap<String, String>()

    /** Returns true when this observation is a change worth logging. */
    fun observe(player: String, key: String, data: JSONObject, playing: Boolean, now: Instant): Boolean {
        val segment = open[player]
        if (playing) {
            if (segment != null && segment.key == key) {
                if (Duration.between(segment.start, now) >= MEDIA_SEGMENT_MAX_LENGTH) {
                    flush(segment, now)
                    open[player] = Segment(key, data, now)
                }
                return false
            }
            segment?.let { flush(it, now) }
            open[player] = Segment(key, data, now)
            lastStateKeys[player] = key
            return true
        }

        segment?.let { flush(it, now) }
        open.remove(player)
        if (lastStateKeys[player] == key) return false
        lastStateKeys[player] = key
        // Pauses and stops have no duration; record the transition itself.
        emit(now, 0.0, data)
        return true
    }

    /** Writes and forgets the player's open segment, e.g. when its session is destroyed. */
    fun end(player: String, now: Instant) {
        open.remove(player)?.let { flush(it, now) }
        lastStateKeys.remove(player)
    }

    fun endAll(now: Instant) {
        open.values.forEach { flush(it, now) }
        open.clear()
        lastStateKeys.clear()
    }

    private fun flush(segment: Segment, end: Instant) {
        val millis = Duration.between(segment.start, end).toMillis()
        if (millis > 0) emit(segment.start, millis / 1000.0, segment.data)
    }
}
