package net.activitywatch.android.watcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.threeten.bp.Instant

class CallEventTrackerTest {
    private data class Emitted(
        val startMs: Long,
        val seconds: Double,
        val direction: String,
        val connected: Boolean,
        val talkSeconds: Double?,
    )

    private val emitted = mutableListOf<Emitted>()
    private val tracker = CallEventTracker { start, seconds, data ->
        emitted += Emitted(
            startMs = start.toEpochMilli(),
            seconds = seconds,
            direction = data.getString("direction"),
            connected = data.optBoolean("connected", false),
            talkSeconds = if (data.has("talk_seconds")) data.getDouble("talk_seconds") else null,
        )
    }

    private fun at(ms: Long) = Instant.ofEpochMilli(ms)

    private fun post(key: String, phase: CallPhase, ms: Long, pkg: String = "dialer") =
        tracker.onPosted(key, pkg, "Phone", phase, postedAt = at(ms), now = at(ms))

    @Test
    fun callRunsFromFirstNotificationToDismissal() {
        post("n1", CallPhase.UNKNOWN, 0)        // call notification appears
        post("n1", CallPhase.ACTIVE, 5_000)     // dialer shows a running timer
        assertTrue(tracker.onRemoved("n1", "dialer", at(65_000)))

        tracker.flushExpired(at(67_000))

        assertEquals(listOf(Emitted(0, 65.0, "unknown", true, 60.0)), emitted)
    }

    @Test
    fun replacementNotificationInsideGraceWindowContinuesTheSameCall() {
        post("ring", CallPhase.RINGING, 0)
        tracker.onRemoved("ring", "dialer", at(10_000))
        post("ongoing", CallPhase.ACTIVE, 10_500)   // dialer swaps the notification
        tracker.flushExpired(at(11_000))
        assertEquals(emptyList<Emitted>(), emitted)

        tracker.onRemoved("ongoing", "dialer", at(70_000))
        tracker.flushExpired(at(72_000))

        assertEquals(listOf(Emitted(0, 70.0, "incoming", true, 59.5)), emitted)
    }

    @Test
    fun missedCallIsWrittenWithoutTalkTime() {
        post("ring", CallPhase.RINGING, 0)
        tracker.onRemoved("ring", "dialer", at(20_000))

        tracker.flushExpired(at(21_000))        // still inside the grace window
        assertEquals(emptyList<Emitted>(), emitted)

        tracker.flushExpired(at(22_000))
        assertEquals(listOf(Emitted(0, 20.0, "incoming", false, null)), emitted)
    }

    @Test
    fun nextCallFromTheSameAppStartsANewEvent() {
        post("a", CallPhase.RINGING, 0)
        tracker.onRemoved("a", "dialer", at(20_000))

        post("b", CallPhase.UNKNOWN, 100_000)   // long after the grace window
        tracker.onRemoved("b", "dialer", at(130_000))
        tracker.flushExpired(at(140_000))

        assertEquals(
            listOf(
                Emitted(0, 20.0, "incoming", false, null),
                Emitted(100_000, 30.0, "unknown", false, null),
            ),
            emitted,
        )
    }

    @Test
    fun differentAppsAreTrackedSeparately() {
        post("w", CallPhase.UNKNOWN, 0, pkg = "whatsapp")
        post("d", CallPhase.UNKNOWN, 1_000, pkg = "dialer")
        tracker.onRemoved("d", "dialer", at(11_000))
        tracker.flushExpired(at(20_000))

        assertEquals(listOf(Emitted(1_000, 10.0, "unknown", false, null)), emitted)
    }

    @Test
    fun unknownNotificationRemovalIsIgnored() {
        assertFalse(tracker.onRemoved("never-seen", "dialer", at(1_000)))
        post("n1", CallPhase.UNKNOWN, 0)
        assertFalse(tracker.onRemoved("other", "dialer", at(1_000)))
        tracker.flushExpired(at(60_000))
        assertEquals(emptyList<Emitted>(), emitted)
    }

    @Test
    fun endAllClosesAStillRunningCallAtDisconnectTime() {
        post("n1", CallPhase.ACTIVE, 0)         // listener started mid-call
        tracker.endAll(at(30_000))

        assertEquals(listOf(Emitted(0, 30.0, "unknown", true, 30.0)), emitted)
    }

    @Test
    fun endAllUsesTheDismissalTimeForACallThatAlreadyEnded() {
        post("n1", CallPhase.UNKNOWN, 0)
        tracker.onRemoved("n1", "dialer", at(15_000))
        tracker.endAll(at(900_000))             // listener disconnects much later

        assertEquals(listOf(Emitted(0, 15.0, "unknown", false, null)), emitted)
    }

    @Test
    fun aNotificationThatIsNeverRemovedIsCappedAtSixHours() {
        post("n1", CallPhase.UNKNOWN, 0)
        tracker.endAll(at(10L * 60 * 60 * 1000))

        assertEquals(listOf(Emitted(0, 6 * 3600.0, "unknown", false, null)), emitted)
    }

    @Test
    fun ringingSeenAfterTheFirstNotificationMakesTheCallIncoming() {
        post("n1", CallPhase.UNKNOWN, 0)        // first notification has no ringing evidence
        post("n1", CallPhase.RINGING, 2_000)    // full-screen intent arrives 2 s later
        tracker.onRemoved("n1", "dialer", at(30_000))
        tracker.flushExpired(at(33_000))

        assertEquals(listOf(Emitted(0, 30.0, "incoming", false, null)), emitted)
    }

    @Test
    fun answeringDoesNotChangeTheDirectionOfAnIncomingCall() {
        post("n1", CallPhase.RINGING, 0)
        post("n1", CallPhase.ACTIVE, 5_000)
        tracker.onRemoved("n1", "dialer", at(65_000))
        tracker.flushExpired(at(67_000))

        assertEquals(listOf(Emitted(0, 65.0, "incoming", true, 60.0)), emitted)
    }
}