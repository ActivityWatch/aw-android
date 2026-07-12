package net.activitywatch.android.watcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
