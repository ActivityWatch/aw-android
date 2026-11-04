package net.activitywatch.android.watcher

import net.activitywatch.android.data.AppSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionPauseIntervalsTest {
    private val minute = 60_000L
    private val hour = 60 * minute

    private fun window(start: Long, end: Long) = AllowedWindow(start, end)

    private fun session(start: Long, end: Long, pkg: String = "chat") =
        AppSession(packageName = pkg, appName = pkg, startTime = start, endTime = end)

    @Test
    fun noPausesKeepsTheWholeRange() {
        assertEquals(listOf(window(0L, hour)), PauseIntervals.allowedWindows(emptyList(), 0L, hour))
    }

    @Test
    fun pauseStrictlyInsideSplitsIntoTwoWindows() {
        val pauses = listOf(PauseInterval(startMs = 10 * minute, endMs = 20 * minute))
        assertEquals(
            listOf(window(0L, 10 * minute), window(20 * minute, hour)),
            PauseIntervals.allowedWindows(pauses, 0L, hour),
        )
    }

    @Test
    fun boundariesAreHalfOpen() {
        // Start inclusive, end exclusive: no zero-length windows at either edge.
        val pauses = listOf(PauseInterval(startMs = 0L, endMs = 10 * minute))
        assertEquals(
            listOf(window(10 * minute, hour)),
            PauseIntervals.allowedWindows(pauses, 0L, hour),
        )

        val trailing = listOf(PauseInterval(startMs = 50 * minute, endMs = hour))
        assertEquals(
            listOf(window(0L, 50 * minute)),
            PauseIntervals.allowedWindows(trailing, 0L, hour),
        )
    }

    @Test
    fun ongoingPauseStopsTheWindowAtItsStart() {
        val pauses = listOf(PauseInterval(startMs = 30 * minute, endMs = null))
        assertEquals(
            listOf(window(0L, 30 * minute)),
            PauseIntervals.allowedWindows(pauses, 0L, hour),
        )
    }

    @Test
    fun pauseCoveringTheRangeYieldsNoWindows() {
        val pauses = listOf(PauseInterval(startMs = -hour, endMs = 2 * hour))
        assertTrue(PauseIntervals.allowedWindows(pauses, 0L, hour).isEmpty())
    }

    @Test
    fun multiplePausesYieldOneWindowEachSide() {
        val pauses = listOf(
            PauseInterval(startMs = 5 * minute, endMs = 10 * minute),
            PauseInterval(startMs = 20 * minute, endMs = 25 * minute),
        )
        assertEquals(
            listOf(
                window(0L, 5 * minute),
                window(10 * minute, 20 * minute),
                window(25 * minute, hour),
            ),
            PauseIntervals.allowedWindows(pauses, 0L, hour),
        )
    }

    @Test
    fun replayAfterAResumeStartsAtTheIntervalEnd() {
        // The stored session is replayed from its start, which can sit inside a pause that
        // closed just before. The window must start at the resume, not at the replay point.
        val pauses = listOf(PauseInterval(startMs = 10 * minute, endMs = 40 * minute))
        assertEquals(
            listOf(window(40 * minute, hour)),
            PauseIntervals.allowedWindows(pauses, 20 * minute, hour),
        )
    }

    @Test
    fun normalizeDropsBackwardsAndUnionsOverlaps() {
        val normalized = PauseIntervals.normalize(
            listOf(
                PauseInterval(startMs = 30 * minute, endMs = 20 * minute), // backwards clock jump
                PauseInterval(startMs = 0L, endMs = 20 * minute),
                PauseInterval(startMs = 10 * minute, endMs = 25 * minute),
            )
        )
        assertEquals(listOf(PauseInterval(startMs = 0L, endMs = 25 * minute)), normalized)
    }

    @Test
    fun normalizeKeepsAtMostOneOpenInterval() {
        val normalized = PauseIntervals.normalize(
            listOf(
                PauseInterval(startMs = 10 * minute, endMs = null),
                PauseInterval(startMs = 20 * minute, endMs = null),
            )
        )
        assertEquals(listOf(PauseInterval(startMs = 10 * minute, endMs = null)), normalized)
    }

    @Test
    fun sameAppAcrossAPauseBecomesTwoSessions() {
        val pauses = listOf(PauseInterval(startMs = 2 * hour, endMs = 3 * hour))
        val parts = PauseIntervals.splitSession(session(start = hour, end = 4 * hour), pauses)

        assertEquals(
            listOf(
                Triple("chat", hour, 2 * hour),
                Triple("chat", 3 * hour, 4 * hour),
            ),
            parts.map { Triple(it.packageName, it.startTime, it.endTime) },
        )
        // Neither segment's end crosses the other's start.
        assertTrue(parts[0].endTime <= parts[1].startTime)
    }

    @Test
    fun sessionEntirelyInsideAPauseDisappears() {
        val pauses = listOf(PauseInterval(startMs = hour, endMs = 2 * hour))
        assertTrue(PauseIntervals.splitSession(session(hour + minute, 2 * hour - minute), pauses).isEmpty())
    }

    @Test
    fun excludedTimestampUsesHalfOpenBounds() {
        val pauses = listOf(PauseInterval(startMs = 10 * minute, endMs = 20 * minute))
        assertTrue(PauseIntervals.isExcluded(pauses, 10 * minute))
        assertTrue(PauseIntervals.isExcluded(pauses, 19 * minute))
        assertFalse(PauseIntervals.isExcluded(pauses, 20 * minute))
        assertFalse(PauseIntervals.isExcluded(pauses, 9 * minute))
    }

    @Test
    fun openIntervalExcludesEverythingAfterItsStart() {
        val pauses = listOf(PauseInterval(startMs = 10 * minute, endMs = null))
        assertTrue(PauseIntervals.isExcluded(pauses, 5 * hour))
    }

    @Test
    fun clipToWindowKeepsOnlyTheOverlap() {
        // A window parsed from further back than its own start must store only its part:
        // this is the app that resumed during a pause and is still open after resume.
        val clipped = PauseIntervals.clipToWindow(
            session(start = hour, end = 3 * hour),
            window(2 * hour, 4 * hour),
        )
        assertEquals(Triple("chat", 2 * hour, 3 * hour), clipped?.let { Triple(it.packageName, it.startTime, it.endTime) })
        assertEquals(1 * hour, clipped?.durationMs)
    }

    @Test
    fun clipToWindowDropsSessionsOutsideIt() {
        // The pre-pause part of a session that straddles the gap is not importable.
        assertEquals(
            null,
            PauseIntervals.clipToWindow(session(start = hour, end = 2 * hour), window(3 * hour, 4 * hour)),
        )
        assertEquals(
            null,
            PauseIntervals.clipToWindow(session(start = hour, end = 2 * hour), window(2 * hour, 4 * hour)),
        )
    }

    @Test
    fun crossesPauseDetectsAbridgingMerge() {
        val pauses = listOf(PauseInterval(startMs = 10 * minute, endMs = 20 * minute))
        assertTrue(PauseIntervals.crossesPause(pauses, 5 * minute, 25 * minute))
        // Both events on one side of the pause: no bridge.
        assertFalse(PauseIntervals.crossesPause(pauses, 0L, 10 * minute))
        assertFalse(PauseIntervals.crossesPause(pauses, 20 * minute, 30 * minute))
        // An open interval never needs the guard: nothing after it is imported.
        assertFalse(PauseIntervals.crossesPause(listOf(PauseInterval(10 * minute, null)), 0L, 30 * minute))
    }

    @Test
    fun emptyRangeYieldsNoWindow() {
        assertTrue(PauseIntervals.allowedWindows(emptyList(), hour, hour).isEmpty())
        assertTrue(PauseIntervals.allowedWindows(emptyList(), hour, hour - 1).isEmpty())
    }
}
