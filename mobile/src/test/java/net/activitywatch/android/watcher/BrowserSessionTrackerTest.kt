package net.activitywatch.android.watcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.threeten.bp.Instant

class BrowserSessionTrackerTest {

    // A clock we can advance/rewind by hand so duration math is deterministic and we can
    // reproduce a backward clock step (NTP sync, manual change) without waiting on real time.
    private class FakeClock(private var instant: Instant) {
        fun advanceSeconds(seconds: Long) { instant = instant.plusSeconds(seconds) }
        fun rewindSeconds(seconds: Long) { instant = instant.minusSeconds(seconds) }
        fun now(): Instant = instant
    }

    @Test
    fun `first url does not emit a completed session`() {
        val clock = FakeClock(Instant.ofEpochSecond(1000))
        val tracker = BrowserSessionTracker(clock::now)

        val completed = tracker.handleUrl("example.com", "chrome")

        assertNull(completed)
    }

    @Test
    fun `same url and browser does not emit a completed session`() {
        val clock = FakeClock(Instant.ofEpochSecond(1000))
        val tracker = BrowserSessionTracker(clock::now)

        tracker.handleUrl("example.com", "chrome")
        val completed = tracker.handleUrl("example.com", "chrome")

        assertNull(completed)
    }

    @Test
    fun `url change emits the previous url as a completed session with correct duration`() {
        val clock = FakeClock(Instant.ofEpochSecond(1000))
        val tracker = BrowserSessionTracker(clock::now)

        tracker.handleUrl("example.com", "chrome")
        clock.advanceSeconds(30)
        val completed = tracker.handleUrl("example.org", "chrome")

        checkNotNull(completed)
        assertEquals("example.com", completed.url)
        assertEquals("chrome", completed.browser)
        assertEquals(Instant.ofEpochSecond(1000), completed.start)
        assertEquals(30L, completed.duration.seconds)
    }

    @Test
    fun `browser change alone (same url) also ends the session`() {
        val clock = FakeClock(Instant.ofEpochSecond(1000))
        val tracker = BrowserSessionTracker(clock::now)

        tracker.handleUrl("example.com", "chrome")
        clock.advanceSeconds(5)
        val completed = tracker.handleUrl("example.com", "firefox")

        checkNotNull(completed)
        assertEquals("chrome", completed.browser)
    }

    @Test
    fun `title set before the url changes is attached to the completed session`() {
        val clock = FakeClock(Instant.ofEpochSecond(1000))
        val tracker = BrowserSessionTracker(clock::now)

        tracker.handleUrl("example.com", "chrome")
        tracker.handleWindowTitle("Example Domain")
        val completed = tracker.handleUrl("example.org", "chrome")

        checkNotNull(completed)
        assertEquals("Example Domain", completed.title)
    }

    @Test
    fun `missing title defaults to empty string`() {
        val clock = FakeClock(Instant.ofEpochSecond(1000))
        val tracker = BrowserSessionTracker(clock::now)

        tracker.handleUrl("example.com", "chrome")
        val completed = tracker.handleUrl("example.org", "chrome")

        checkNotNull(completed)
        assertEquals("", completed.title)
    }

    @Test
    fun `title does not carry over into the next session`() {
        val clock = FakeClock(Instant.ofEpochSecond(1000))
        val tracker = BrowserSessionTracker(clock::now)

        tracker.handleUrl("example.com", "chrome")
        tracker.handleWindowTitle("Example Domain")
        tracker.handleUrl("example.org", "chrome")

        // The title for the new page hasn't arrived yet - should not leak the old title.
        val completed = tracker.handleUrl("example.net", "chrome")

        checkNotNull(completed)
        assertEquals("", completed.title)
    }

    @Test
    fun `handleWindowTitle reports whether the title actually changed`() {
        val tracker = BrowserSessionTracker()

        assertEquals(true, tracker.handleWindowTitle("Example Domain"))
        assertEquals(false, tracker.handleWindowTitle("Example Domain"))
        assertEquals(true, tracker.handleWindowTitle("Something else"))
    }

    @Test
    fun `negative duration from a backward clock step is clamped to zero`() {
        val clock = FakeClock(Instant.ofEpochSecond(1000))
        val tracker = BrowserSessionTracker(clock::now)

        tracker.handleUrl("example.com", "chrome")
        clock.rewindSeconds(60) // simulate NTP sync stepping the clock backward
        val completed = tracker.handleUrl("example.org", "chrome")

        checkNotNull(completed)
        assertEquals(0L, completed.duration.seconds)
    }
    @Test
    fun `audible defaults to false and is attached to the completed session`() {
        val clock = FakeClock(Instant.ofEpochSecond(1000))
        val tracker = BrowserSessionTracker(clock::now)

        tracker.handleUrl("example.com", "chrome")
        val first = tracker.handleUrl("example.org", "chrome", audible = true)
        val second = tracker.handleUrl("example.net", "chrome")

        checkNotNull(first)
        assertFalse(first.audible)
        checkNotNull(second)
        assertTrue(second.audible)
    }

    @Test
    fun `audible change splits the session and keeps url browser and title`() {
        val clock = FakeClock(Instant.ofEpochSecond(1000))
        val tracker = BrowserSessionTracker(clock::now)

        tracker.handleUrl("example.com", "chrome", audible = false)
        tracker.handleWindowTitle("Example Domain")
        clock.advanceSeconds(10)
        val silent = tracker.handleAudible(true)
        clock.advanceSeconds(20)
        val playing = tracker.handleUrl("example.org", "chrome")

        checkNotNull(silent)
        assertEquals("example.com", silent.url)
        assertEquals("chrome", silent.browser)
        assertEquals("Example Domain", silent.title)
        assertFalse(silent.audible)
        assertEquals(Instant.ofEpochSecond(1000), silent.start)
        assertEquals(10L, silent.duration.seconds)

        checkNotNull(playing)
        assertEquals("example.com", playing.url)
        assertEquals("Example Domain", playing.title)
        assertTrue(playing.audible)
        assertEquals(Instant.ofEpochSecond(1010), playing.start)
        assertEquals(20L, playing.duration.seconds)
    }

    @Test
    fun `unchanged audible state does not split the session`() {
        val clock = FakeClock(Instant.ofEpochSecond(1000))
        val tracker = BrowserSessionTracker(clock::now)

        tracker.handleUrl("example.com", "chrome", audible = true)
        clock.advanceSeconds(5)

        assertNull(tracker.handleAudible(true))
    }

    @Test
    fun `audible without an active session is ignored`() {
        val tracker = BrowserSessionTracker()

        assertNull(tracker.handleAudible(true))

        // Ending a session (window changed away) also leaves nothing to split.
        tracker.handleUrl("example.com", "chrome")
        tracker.handleUrl(null, null)
        assertNull(tracker.handleAudible(true))
    }
}
