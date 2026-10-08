package net.activitywatch.android.watcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.threeten.bp.Instant

class MediaPlaybackSegmentsTest {
    private data class Emitted(val start: Long, val seconds: Double, val title: String)

    private val emitted = mutableListOf<Emitted>()
    private val segments = MediaPlaybackSegments { start, seconds, data ->
        emitted += Emitted(start.epochSecond, seconds, data.getString("title"))
    }

    private fun at(seconds: Long) = Instant.ofEpochSecond(seconds)

    private fun observe(player: String, title: String, playing: Boolean, seconds: Long) {
        val state = if (playing) "playing" else "paused"
        segments.observe(
            player, "$player|$title|$state", JSONObject().put("title", title), playing, at(seconds)
        )
    }

    @Test
    fun twoPlayersPollingTogetherWriteOneEventEachWhenTheyStop() {
        // Both players are polled every 15s. The old heartbeats alternated between the two
        // and inserted a new zero-duration event on every poll.
        for (t in 0L..120L step 15) {
            observe("music", "song", playing = true, seconds = t)
            observe("podcast", "episode", playing = true, seconds = t)
        }
        assertEquals(emptyList<Emitted>(), emitted)

        observe("music", "song", playing = false, seconds = 130)
        segments.end("podcast", at(140))

        assertEquals(
            listOf(
                Emitted(0, 130.0, "song"),
                Emitted(130, 0.0, "song"), // the pause itself
                Emitted(0, 140.0, "episode"),
            ),
            emitted,
        )
    }

    @Test
    fun trackChangeEndsThePreviousTrack() {
        observe("music", "first", playing = true, seconds = 0)
        observe("music", "second", playing = true, seconds = 200)
        segments.endAll(at(260))

        assertEquals(
            listOf(Emitted(0, 200.0, "first"), Emitted(200, 60.0, "second")),
            emitted,
        )
    }

    @Test
    fun longPlaybackIsWrittenInBoundedChunks() {
        val chunk = MEDIA_SEGMENT_MAX_LENGTH.seconds
        for (t in 0L..chunk + 30 step 15) {
            observe("podcast", "episode", playing = true, seconds = t)
        }
        // The first chunk is written as soon as a poll sees it reach the limit, without
        // waiting for the episode to end.
        assertEquals(listOf(Emitted(0, chunk.toDouble(), "episode")), emitted)
    }

    @Test
    fun repeatedPauseIsRecordedOnce() {
        observe("music", "song", playing = true, seconds = 0)
        observe("music", "song", playing = false, seconds = 10)
        observe("music", "song", playing = false, seconds = 25)

        assertEquals(listOf(Emitted(0, 10.0, "song"), Emitted(10, 0.0, "song")), emitted)
    }
}
