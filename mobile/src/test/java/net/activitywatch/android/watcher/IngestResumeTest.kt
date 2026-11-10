package net.activitywatch.android.watcher

import android.app.usage.UsageEvents
import net.activitywatch.android.data.UsageEvent
import net.activitywatch.android.parser.MAX_REASONABLE_SESSION_DURATION
import net.activitywatch.android.parser.MIN_SESSION_DURATION
import net.activitywatch.android.parser.parseForegroundSessions
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

private const val SECOND = 1000L
private const val MINUTE = 60 * SECOND
private const val HOUR = 60 * MINUTE

// Event times are offsets from T0, so that reading history from before them never goes below 0.
private const val T0 = 1000 * HOUR

private fun resumed(at: Long, app: String) = UsageEvent(UsageEvents.Event.ACTIVITY_RESUMED, T0 + at, app, "$app.Main")
private fun paused(at: Long, app: String) = UsageEvent(UsageEvents.Event.ACTIVITY_PAUSED, T0 + at, app, "$app.Main")
private fun unlocked(at: Long) = UsageEvent(UsageEvents.Event.KEYGUARD_HIDDEN, T0 + at, "android", "")
private fun screenOff(at: Long) = UsageEvent(UsageEvents.Event.SCREEN_NON_INTERACTIVE, T0 + at, "android", "")
private fun shutdown(at: Long) = UsageEvent(UsageEvents.Event.DEVICE_SHUTDOWN, T0 + at, "android", "")
private fun switched(at: Long, from: String, to: String) = listOf(paused(at, from), resumed(at, to))

class FindHistoryShiftTest {
    private fun sessionMark(at: Long, app: String) = StoredSession(
        T0 + at, T0 + at + SECOND, JSONObject().put("package", app).put("classname", "$app.Main"),
    ).mark()

    @Test
    fun isZeroWhileTheNewestEventIsWhereItWasStored() {
        val events = listOf(resumed(0, "chat"), resumed(MINUTE, "video"))
        assertEquals(0L, findHistoryShift(events, listOf(sessionMark(MINUTE, "video"))))
    }

    @Test
    fun findsAMoveBackConfirmedByAnOlderEvent() {
        val events = listOf(resumed(-2192, "chat"), resumed(MINUTE - 2192, "video"))
        val marks = listOf(sessionMark(MINUTE, "video"), sessionMark(0, "chat"))
        assertEquals(-2192L, findHistoryShift(events, marks))
    }

    @Test
    fun findsAMoveForwardConfirmedByAnOlderEvent() {
        val events = listOf(resumed(654, "chat"), resumed(MINUTE + 654, "video"))
        val marks = listOf(sessionMark(MINUTE, "video"), sessionMark(0, "chat"))
        assertEquals(654L, findHistoryShift(events, marks))
    }

    @Test
    fun picksTheMoveTheOlderEventsConfirmOverACloserReopening() {
        // Stored: other at 90 s, video at 100 s. Moved back 3 s, video opens again at 101.5 s:
        // closer to the stored time than the moved video at 97 s, but nothing else lines up.
        val events = listOf(
            resumed(87 * SECOND, "other"), resumed(97 * SECOND, "video"),
            resumed(99 * SECOND, "chat"), resumed(101_500, "video"),
        )
        val marks = listOf(sessionMark(100 * SECOND, "video"), sessionMark(90 * SECOND, "other"))
        assertEquals(-3 * SECOND, findHistoryShift(events, marks))
    }

    @Test
    fun trustsNoMatchThatNoOtherEventConfirms() {
        val events = listOf(resumed(MINUTE - 2192, "video"))
        assertNull(findHistoryShift(events, listOf(sessionMark(MINUTE, "video"), sessionMark(0, "chat"))))
    }

    @Test
    fun takesNoConfirmationFromASecondCopyOfTheNewestEvent() {
        // Two stored copies of the video at 100 s: each would line up with any video nearby.
        val events = listOf(resumed(97 * SECOND, "video"), resumed(101_500, "video"))
        val marks = listOf(sessionMark(100 * SECOND, "video"), sessionMark(100 * SECOND, "video"))
        assertNull(findHistoryShift(events, marks))
    }

    @Test
    fun takesNoMatchFromTheSameAppsOpenedMinutesBefore() {
        // Moved back 2 s, the video has nothing to confirm it. The chat then video opened four
        // minutes before line up with both stored ones, but lie beyond the largest move.
        val events = listOf(
            resumed(5 * MINUTE + 50 * SECOND, "chat"), resumed(6 * MINUTE, "video"),
            resumed(10 * MINUTE - 2 * SECOND, "video"),
        )
        val marks = listOf(sessionMark(10 * MINUTE, "video"), sessionMark(9 * MINUTE + 50 * SECOND, "chat"))
        assertNull(findHistoryShift(events, marks))
    }

    @Test
    fun looksNoFurtherThanTheLargestMove() {
        val far = MAX_HISTORY_SHIFT_MS + 1
        val events = listOf(resumed(-far, "chat"), resumed(MINUTE - far, "video"))
        assertNull(findHistoryShift(events, listOf(sessionMark(MINUTE, "video"), sessionMark(0, "chat"))))
    }
}

class HistoryReadStartTest {
    @Test
    fun readsTheWholeHistoryWhileABucketIsEmpty() {
        assertEquals(0L, historyReadStart(emptyList(), listOf(T0)))
        assertEquals(0L, historyReadStart(listOf(T0), emptyList()))
    }

    @Test
    fun startsTheLargestMoveBeforeTheOldestStoredEvent() {
        assertEquals(
            T0 + 2 * HOUR - MAX_HISTORY_SHIFT_MS,
            historyReadStart(listOf(T0 + 3 * HOUR, T0 + 2 * HOUR), listOf(T0 + 150 * MINUTE)),
        )
    }

    @Test
    fun readsNoStoredEventFurtherBackThanTheSpan() {
        val newest = T0 + 240 * HOUR
        assertEquals(
            newest - STORED_EVENTS_SPAN_MS - MAX_HISTORY_SHIFT_MS,
            historyReadStart(listOf(newest, T0), listOf(newest)),
        )
    }
}

class ReadAndPlanIngestTest {
    private fun session(at: Long, app: String) =
        StoredSession(T0 + at, T0 + at + MINUTE, JSONObject().put("package", app).put("classname", "$app.Main"))

    // Newest first, as read from the buckets.
    private val storedSessions = listOf(session(2 * HOUR, "video"), session(HOUR, "chat"))
    private val storedUnlocks = listOf(T0 + 90 * MINUTE, T0 + HOUR)
    private val history = listOf(
        unlocked(HOUR), resumed(HOUR, "chat"), unlocked(90 * MINUTE), resumed(2 * HOUR, "video"),
    )

    /** Where each read of the history starts. */
    private fun reads(history: List<UsageEvent>, unlocks: List<Long> = storedUnlocks): List<Long> {
        val reads = mutableListOf<Long>()
        readAndPlanIngest(storedSessions, unlocks, T0 + 3 * HOUR, read = { since ->
            reads += since
            history.filter { it.timeStamp >= since }
        }) { it }
        return reads
    }

    @Test
    fun readsFromJustBeforeTheNewestEventsWhileTheyAreInPlace() {
        assertEquals(listOf(T0 + 90 * MINUTE - MAX_HISTORY_SHIFT_MS), reads(history))
    }

    @Test
    fun readsAgainFromTheOlderStoredEventsAfterAMove() {
        val moved = history.map { it.copy(timeStamp = it.timeStamp - 2 * SECOND) }
        assertEquals(
            listOf(T0 + 90 * MINUTE - MAX_HISTORY_SHIFT_MS, T0 + HOUR - MAX_HISTORY_SHIFT_MS),
            reads(moved),
        )
    }

    @Test
    fun readsTheWholeHistoryOnceWhileABucketIsEmpty() {
        assertEquals(listOf(0L), reads(history, unlocks = emptyList()))
    }
}

/**
 * Ingest runs over a usage history that Android moves between them, as it does when it corrects
 * the clock. [run] does what SessionEventWatcher does with the functions it uses: read both
 * buckets' newest events, plan with [readAndPlanIngest], reading the history as queryEvents does
 * (lower bound inclusive, upper bound exclusive), and send what the plan holds.
 */
class IngestResumeTest {
    private data class Stored(val start: Long, val end: Long, val data: JSONObject)

    /**
     * The datastore's heartbeat with pulsetime 0: merged into the event with the latest start,
     * keeping the later end, when the data is the same and the heartbeat starts within that
     * event; inserted otherwise.
     */
    private class Bucket {
        val events = mutableListOf<Stored>()

        fun newest(): List<Stored> = events.sortedByDescending { it.start }.take(STORED_EVENTS_TO_MATCH)

        fun heartbeat(start: Long, end: Long, data: JSONObject) {
            val last = events.maxByOrNull { it.start }
            if (last != null && last.data.similar(data) && start in last.start..last.end) {
                events[events.indexOf(last)] = last.copy(end = maxOf(last.end, end))
            } else {
                events.add(Stored(start, end, data))
            }
        }
    }

    private val history = mutableListOf<UsageEvent>()
    private val sessions = Bucket()
    private val unlocks = Bucket()

    private fun happen(vararg events: UsageEvent) = history.addAll(events)
    private fun happen(events: List<UsageEvent>) = history.addAll(events)

    private fun moveHistory(by: Long) = history.replaceAll { it.copy(timeStamp = it.timeStamp + by) }

    private fun run(at: Long, appName: (String) -> String = { it }) {
        val now = T0 + at
        val storedSessions = sessions.newest().map { StoredSession(it.start, it.end, it.data) }
        val storedUnlocks = unlocks.newest().map { it.start }
        val read = { since: Long -> history.filter { it.timeStamp in since until now }.sortedBy { it.timeStamp } }
        val plan = readAndPlanIngest(storedSessions, storedUnlocks, now, read, appName)
        // A read that starts later than historyReadStart must not change what is sent.
        val fullRead = read(historyReadStart(storedSessions.map { it.start }, storedUnlocks))
        assertEquals(planned(planIngest(fullRead, storedSessions, storedUnlocks, now, appName)), planned(plan))
        for (toSend in plan.sessions) {
            val session = toSend.session
            // The watcher sends the duration, durationMs.
            sessions.heartbeat(session.startTime, session.startTime + session.durationMs,
                toSend.storedData ?: session.toEventData())
        }
        for (time in plan.unlocks) unlocks.heartbeat(time, time, JSONObject())
    }

    private fun planned(plan: IngestPlan) = listOf(
        plan.sessions.map { it.session to it.storedData?.toString() },
        plan.unlocks, plan.sessionShift, plan.unlockShift,
    )

    private fun storedSessions() =
        sessions.events.sortedBy { it.start }.map { Triple(it.data.getString("package"), it.start - T0, it.end - T0) }

    private fun storedUnlocks() = unlocks.events.map { it.start - T0 }.sorted()

    /** Another run, with nothing new in the history, must leave both buckets as they are. */
    private fun assertRunAgainChangesNothing(at: Long) {
        val before = storedSessions() to storedUnlocks()
        run(at)
        assertEquals(before, storedSessions() to storedUnlocks())
    }

    /** A chat, then a video from minute 10 still playing at the first run, at minute 20. */
    private fun videoOpenAtFirstRun() {
        happen(resumed(9 * MINUTE, "chat"))
        happen(switched(10 * MINUTE, "chat", "video"))
        run(at = 20 * MINUTE)
        assertEquals(Triple("video", 10 * MINUTE, 20 * MINUTE), storedSessions().last())
        // The video ends at minute 35; the chat it switches to lasts a minute.
        happen(switched(35 * MINUTE, "video", "chat"))
        happen(paused(36 * MINUTE, "chat"))
    }

    @Test
    fun extendsTheOpenSessionWhenTheHistoryStaysPut() {
        videoOpenAtFirstRun()
        run(at = 60 * MINUTE)
        assertEquals(
            listOf(
                Triple("chat", 9 * MINUTE, 10 * MINUTE),
                Triple("video", 10 * MINUTE, 35 * MINUTE),
                Triple("chat", 35 * MINUTE, 36 * MINUTE),
            ),
            storedSessions(),
        )
        assertRunAgainChangesNothing(at = 70 * MINUTE)
    }

    @Test
    fun extendsTheOpenSessionAfterAMoveForward() {
        videoOpenAtFirstRun()
        moveHistory(by = 500)
        run(at = 60 * MINUTE)
        assertEquals(
            listOf(
                Triple("chat", 9 * MINUTE, 10 * MINUTE),
                Triple("video", 10 * MINUTE, 35 * MINUTE + 500),
                Triple("chat", 35 * MINUTE + 500, 36 * MINUTE + 500),
            ),
            storedSessions(),
        )
        assertRunAgainChangesNothing(at = 70 * MINUTE)
    }

    @Test
    fun extendsTheOpenSessionAfterAMoveBack() {
        videoOpenAtFirstRun()
        moveHistory(by = -2 * SECOND)
        run(at = 60 * MINUTE)
        assertEquals(
            listOf(
                Triple("chat", 9 * MINUTE, 10 * MINUTE),
                Triple("video", 10 * MINUTE, 35 * MINUTE - 2 * SECOND - 1),
                Triple("chat", 35 * MINUTE - 2 * SECOND, 36 * MINUTE - 2 * SECOND),
            ),
            storedSessions(),
        )
        assertRunAgainChangesNothing(at = 70 * MINUTE)
    }

    @Test
    fun keepsTheAppInBetweenWhenTheSameActivityOpensAgain() {
        // The video is stored while open at 102 s; then a chat, and the video again at 104.5 s.
        happen(resumed(90 * SECOND, "other"))
        happen(switched(100 * SECOND, "other", "video"))
        run(at = 102 * SECOND)
        happen(switched(102 * SECOND, "video", "chat"))
        happen(switched(104_500, "chat", "video"))
        happen(paused(203 * SECOND, "video"))
        moveHistory(by = -3 * SECOND)
        run(at = 300 * SECOND)
        // The chat and the reopened video now start before the stored video ends, so they are
        // stored in its times, from before the move.
        assertEquals(
            listOf(
                Triple("other", 90 * SECOND, 100 * SECOND),
                Triple("video", 100 * SECOND, 102 * SECOND),
                Triple("chat", 102 * SECOND, 104_500L),
                Triple("video", 104_500L, 203 * SECOND),
            ),
            storedSessions(),
        )
        assertRunAgainChangesNothing(at = 400 * SECOND)
    }

    @Test
    fun cutsSessionsMovedBackWhereTheFirstOneAtItsCurrentTimeStarts() {
        // The video is stored while open at 102 s and ends at 103 s; a chat, a mail and the maps
        // follow. Moved back 10 s, the chat and the mail start before the stored video ends and
        // the maps at 103 s, after it: back in the stored times the chat and the mail would
        // overlap the maps, so they are cut at the maps' start, which leaves them nothing.
        happen(resumed(80 * SECOND, "other"))
        happen(switched(100 * SECOND, "other", "video"))
        run(at = 102 * SECOND)
        happen(switched(103 * SECOND, "video", "chat"))
        happen(switched(108 * SECOND, "chat", "mail"))
        happen(switched(113 * SECOND, "mail", "maps"))
        happen(paused(130 * SECOND, "maps"))
        moveHistory(by = -10 * SECOND)
        run(at = 200 * SECOND)
        assertEquals(
            listOf(
                Triple("other", 80 * SECOND, 100 * SECOND),
                Triple("video", 100 * SECOND, 102 * SECOND),
                Triple("maps", 103 * SECOND, 120 * SECOND),
            ),
            storedSessions(),
        )
        assertRunAgainChangesNothing(at = 300 * SECOND)
    }

    @Test
    fun keepsTheSameAppApartAcrossTheMove() {
        // The video is stored while open at 102 s and ends at 102.5 s; then a chat, a mail and
        // the chat again. Moved back 10 s, the first chat is stored in the stored times up to
        // the second, which is at its current time, and the mail between them has no time left.
        // The two chats must stay apart: merged, the next run would move the second one again.
        happen(resumed(80 * SECOND, "other"))
        happen(switched(100 * SECOND, "other", "video"))
        run(at = 102 * SECOND)
        happen(switched(102_500, "video", "chat"))
        happen(switched(109 * SECOND, "chat", "mail"))
        happen(switched(114 * SECOND, "mail", "chat"))
        happen(paused(130 * SECOND, "chat"))
        moveHistory(by = -10 * SECOND)
        run(at = 200 * SECOND)
        assertEquals(
            listOf(
                Triple("other", 80 * SECOND, 100 * SECOND),
                Triple("video", 100 * SECOND, 102 * SECOND),
                Triple("chat", 102_500L, 104 * SECOND - 1),
                Triple("chat", 104 * SECOND, 120 * SECOND),
            ),
            storedSessions(),
        )
        assertRunAgainChangesNothing(at = 300 * SECOND)
        assertRunAgainChangesNothing(at = 400 * SECOND)
    }

    /**
     * Generated histories: apps opened one after another, the screen turned off and on with an
     * unlock, the phone restarted with its clock off by up to 2.5 s either way and corrected
     * later, which moves the history saved until then, and runs in between. Times are on a
     * 500 ms grid, so events often fall exactly on the boundaries the resume draws, and from
     * 0.5 s apart, so some sessions are too short to keep and some runs come right after an app
     * is opened. A run waits until two apps opened since the last correction have stayed open
     * long enough to be stored, over a second: the newest stored session then has another stored
     * in the same times to confirm a move, as most runs on a phone do. A newest event with none
     * is the case the resume does not cover.
     *
     * After every run the session events do not overlap, the unlock times are distinct and no
     * more than the unlocks that happened, and another run changes nothing. At the end the time
     * stored matches the history's, within what each correction can cost: three times its size.
     */
    @Test
    fun keepsTheBucketsConsistentOverGeneratedHistories() {
        for (seed in 0 until 10000) checkGeneratedHistory(seed)
    }

    private fun checkGeneratedHistory(seed: Int) {
        history.clear()
        sessions.events.clear()
        unlocks.events.clear()
        val random = Random(seed)
        val grid = 500L
        val apps = listOf("chat", "video", "mail")
        var time = 0L
        var clockError = 0L
        var open: String? = null
        var openedAt = 0L
        var correctedAt = 0L
        // Apps opened since the last correction, and closed after long enough to be stored.
        var keptSinceCorrection = 0
        var corrected = 0L

        fun record(event: (Long) -> UsageEvent) {
            history += event(time + clockError)
        }
        // An app opens and closes under the same clock error: a shutdown closes it first.
        fun openLongEnough() = open != null && openedAt >= correctedAt && time - openedAt > MIN_SESSION_DURATION
        fun close() {
            if (openLongEnough()) keptSinceCorrection++
            open = null
        }
        fun open(app: String) {
            open?.let { previous -> record { paused(it, previous) } }
            record { resumed(it, app) }
            // The same app opened again right away is merged into the same stored event.
            if (app != open) {
                close()
                openedAt = time
            }
            open = app
        }
        fun checkedRun() {
            val now = time + clockError
            run(now)
            val stored = sessions.events.sortedBy { it.start }
            for ((a, b) in stored.zipWithNext()) {
                assertTrue("seed $seed: ${a.start - T0}..${a.end - T0} overlaps ${b.start - T0}", b.start >= a.end)
            }
            val unlockTimes = storedUnlocks()
            assertEquals("seed $seed: unlocks stored twice", unlockTimes.distinct(), unlockTimes)
            val happened = history.filter { it.eventType == UsageEvents.Event.KEYGUARD_HIDDEN }.map { it.timeStamp - T0 }
            assertTrue("seed $seed: unlocks $unlockTimes stored, $happened happened", unlockTimes.size <= happened.size)
            val sessionsBefore = storedSessions()
            val unlocksBefore = storedUnlocks()
            run(now)
            assertTrue(
                "seed $seed: another run at ${now} removed ${sessionsBefore - storedSessions().toSet()} " +
                    "${unlocksBefore - storedUnlocks().toSet()} and added ${storedSessions() - sessionsBefore.toSet()} " +
                    "${storedUnlocks() - unlocksBefore.toSet()}",
                sessionsBefore == storedSessions() && unlocksBefore == storedUnlocks(),
            )
        }

        repeat(80) {
            when (random.nextInt(12)) {
                in 0..6 -> open(apps.random(random))
                7 -> {
                    record { screenOff(it) }
                    close()
                    time += grid * random.nextInt(20, 600)
                    record { unlocked(it) }
                    open(apps.random(random))
                }
                8, 9 -> if (keptSinceCorrection + (if (openLongEnough()) 1 else 0) >= 2) checkedRun()
                10 -> {
                    record { shutdown(it) }
                    close()
                    time += grid * random.nextInt(20, 120)
                    clockError = grid * random.nextInt(-5, 6)
                    record { unlocked(it) }
                    open(apps.random(random))
                }
                11 -> if (clockError != 0L) {
                    moveHistory(by = -clockError)
                    corrected += 3 * abs(clockError) + 2
                    clockError = 0L
                    correctedAt = time
                    keptSinceCorrection = 0
                }
            }
            time += grid * random.nextInt(1, 600)
        }
        open(apps.random(random))
        time += grid * random.nextInt(3, 600)
        open(apps.random(random))
        time += grid * random.nextInt(3, 600)
        record { screenOff(it) }
        time += grid
        checkedRun()

        val reference = parseForegroundSessions(history.sortedBy { it.timeStamp }) { it }.sumOf { it.durationMs }
        val stored = sessions.events.sumOf { it.end - it.start }
        assertTrue(
            "seed $seed: stored ${stored} ms, the history has $reference ms, corrections allow $corrected ms",
            abs(stored - reference) <= corrected,
        )
    }

    @Test
    fun keepsANewSessionAtItsTimeAfterAMoveForward() {
        // The video is stored while open; the clock is then corrected 2 s forward. A run half a
        // second after the switch to the chat extends the video to 130 s, where the chat starts,
        // and leaves the chat, too short yet, to the next run. The chat was recorded after the
        // correction: moved back by the move it would overlap the video.
        happen(resumed(80 * SECOND, "other"))
        happen(switched(100 * SECOND, "other", "video"))
        run(at = 110 * SECOND)
        moveHistory(by = 2 * SECOND)
        happen(switched(130 * SECOND, "video", "chat"))
        run(at = 130_500)
        happen(paused(140 * SECOND, "chat"))
        run(at = 200 * SECOND)
        assertEquals(
            listOf(
                Triple("other", 80 * SECOND, 100 * SECOND),
                Triple("video", 100 * SECOND, 130 * SECOND),
                Triple("chat", 130 * SECOND, 140 * SECOND),
            ),
            storedSessions(),
        )
        assertRunAgainChangesNothing(at = 300 * SECOND)
    }

    @Test
    fun takesTheMoveOfALoneUnlockFromTheSessions() {
        // One stored unlock, nothing else in its bucket to confirm a move: the sessions stored
        // by the same run do.
        happen(resumed(5 * SECOND, "chat"), unlocked(10 * SECOND))
        happen(switched(20 * SECOND, "chat", "video"))
        run(at = 60 * SECOND)
        moveHistory(by = 2 * SECOND)
        run(at = 120 * SECOND)
        assertEquals(listOf(10 * SECOND), storedUnlocks())
        assertRunAgainChangesNothing(at = 120 * SECOND)
    }

    @Test
    fun takesTheMoveFromAnUnlockAtTheSameTimeAsTheSession() {
        // The only stored session and the only stored unlock start at the same millisecond, as
        // when an app opens as the phone is unlocked: each confirms the other's move.
        happen(unlocked(10 * SECOND), resumed(10 * SECOND, "chat"))
        run(at = 60 * SECOND)
        moveHistory(by = -2 * SECOND)
        happen(screenOff(100 * SECOND))
        run(at = 300 * SECOND)
        assertEquals(listOf(Triple("chat", 10 * SECOND, 100 * SECOND)), storedSessions())
        assertEquals(listOf(10 * SECOND), storedUnlocks())
        assertRunAgainChangesNothing(at = 400 * SECOND)
    }

    @Test
    fun keepsApartAReopeningThatAMoveBackPutsWhereTheStoredOneEnds() {
        // The video is stored while open at 102 s, the screen goes off at 102.5 s and the video
        // is opened again at 112 s. Moved back 10 s, the reopening starts at 102 s, where the
        // stored video ends: sent there, the datastore would merge it into the stored video.
        happen(resumed(80 * SECOND, "other"))
        happen(switched(100 * SECOND, "other", "video"))
        run(at = 102 * SECOND)
        happen(screenOff(102_500))
        happen(resumed(112 * SECOND, "video"))
        happen(paused(130 * SECOND, "video"))
        moveHistory(by = -10 * SECOND)
        run(at = 200 * SECOND)
        assertEquals(
            listOf(
                Triple("other", 80 * SECOND, 100 * SECOND),
                Triple("video", 100 * SECOND, 102 * SECOND),
                Triple("video", 112 * SECOND, 130 * SECOND),
            ),
            storedSessions(),
        )
        assertRunAgainChangesNothing(at = 300 * SECOND)
        assertRunAgainChangesNothing(at = 400 * SECOND)
    }

    @Test
    fun keepsExtendingASessionThatAMoveBackPutsBeforeTheStoredOne() {
        happen(resumed(90 * SECOND, "other"))
        happen(switched(100 * SECOND, "other", "video"))
        run(at = 101_500)
        happen(switched(102 * SECOND, "video", "chat"))
        moveHistory(by = -3 * SECOND)
        run(at = 200 * SECOND)
        assertEquals(Triple("chat", 102 * SECOND, 200 * SECOND), storedSessions().last())
        run(at = 300 * SECOND)
        happen(paused(350 * SECOND, "chat"))
        run(at = 400 * SECOND)
        assertEquals(
            listOf(
                Triple("other", 90 * SECOND, 100 * SECOND),
                Triple("video", 100 * SECOND, 101_500L),
                Triple("chat", 102 * SECOND, 350 * SECOND),
            ),
            storedSessions(),
        )
        assertRunAgainChangesNothing(at = 500 * SECOND)
    }

    @Test
    fun sendsNoSecondCopyOfAShortSessionAfterAMoveForward() {
        happen(resumed(90 * SECOND, "other"))
        happen(switched(100 * SECOND, "other", "video"))
        happen(switched(101_200, "video", "home"))
        run(at = 101_500)
        assertEquals(Triple("video", 100 * SECOND, 101_200L), storedSessions().last())
        happen(switched(110 * SECOND, "home", "chat"))
        happen(paused(150 * SECOND, "chat"))
        moveHistory(by = 1500)
        run(at = 200 * SECOND)
        assertEquals(
            listOf(
                Triple("other", 90 * SECOND, 100 * SECOND),
                Triple("video", 100 * SECOND, 102_700L),
                Triple("home", 102_700L, 111_500L),
                Triple("chat", 111_500L, 151_500L),
            ),
            storedSessions(),
        )
        assertRunAgainChangesNothing(at = 250 * SECOND)
    }

    @Test
    fun capsTheStoredSessionAfterAMoveBack() {
        happen(resumed(-2 * MINUTE, "chat"))
        happen(switched(0, "chat", "maps"))
        run(at = 23 * HOUR)
        moveHistory(by = -2 * SECOND)
        run(at = 25 * HOUR)
        assertEquals(
            Triple("maps", 0L, MAX_REASONABLE_SESSION_DURATION - 1 - 2 * SECOND),
            storedSessions().last(),
        )
        assertRunAgainChangesNothing(at = 26 * HOUR)
    }

    @Test
    fun capsTheStoredSessionAfterAMoveForward() {
        happen(resumed(-2 * MINUTE, "chat"))
        happen(switched(0, "chat", "maps"))
        run(at = 23 * HOUR)
        moveHistory(by = 2 * SECOND)
        run(at = 25 * HOUR)
        assertEquals(Triple("maps", 0L, MAX_REASONABLE_SESSION_DURATION - 1), storedSessions().last())
        assertRunAgainChangesNothing(at = 26 * HOUR)
    }

    @Test
    fun keepsExtendingTheStoredEventWhenTheAppLabelChanges() {
        videoOpenAtFirstRun()
        // A language change or app update: labels now read differently.
        run(at = 60 * MINUTE) { "renamed $it" }
        assertEquals(
            listOf(
                Triple("chat", 9 * MINUTE, 10 * MINUTE),
                Triple("video", 10 * MINUTE, 35 * MINUTE),
                Triple("chat", 35 * MINUTE, 36 * MINUTE),
            ),
            storedSessions(),
        )
    }

    @Test
    fun resumesFromTheStoredStartWhenNoOtherEventConfirmsTheMove() {
        // The video is the only stored session, so nothing confirms where it went: it keeps the
        // end of the first run, and only what starts after that is sent.
        happen(resumed(10 * MINUTE, "video"))
        run(at = 20 * MINUTE)
        happen(switched(35 * MINUTE, "video", "chat"))
        happen(paused(36 * MINUTE, "chat"))
        moveHistory(by = -2 * SECOND)
        run(at = 60 * MINUTE)
        assertEquals(
            listOf(
                Triple("video", 10 * MINUTE, 20 * MINUTE),
                Triple("chat", 35 * MINUTE - 2 * SECOND, 36 * MINUTE - 2 * SECOND),
            ),
            storedSessions(),
        )
        assertRunAgainChangesNothing(at = 70 * MINUTE)
    }

    @Test
    fun sendsNoSecondCopyOfAnUnlockAfterAMoveForward() {
        happen(unlocked(5 * MINUTE), unlocked(7 * MINUTE))
        run(at = 8 * MINUTE)
        moveHistory(by = 656)
        run(at = 10 * MINUTE)
        assertEquals(listOf(5 * MINUTE, 7 * MINUTE), storedUnlocks())
        assertRunAgainChangesNothing(at = 12 * MINUTE)
    }

    @Test
    fun keepsAnUnlockThatAMoveBackPutsBeforeTheStoredOne() {
        happen(unlocked(5 * MINUTE), unlocked(7 * MINUTE))
        run(at = 7 * MINUTE + SECOND)
        happen(unlocked(7 * MINUTE + 1500))
        moveHistory(by = -2 * SECOND)
        run(at = 10 * MINUTE)
        // Stored in the stored unlocks' times, from before the move.
        assertEquals(listOf(5 * MINUTE, 7 * MINUTE, 7 * MINUTE + 1500), storedUnlocks())
        assertRunAgainChangesNothing(at = 12 * MINUTE)
    }

    @Test
    fun leavesForALaterRunAnUnlockThatWouldComeAfterNow() {
        happen(unlocked(5 * MINUTE), unlocked(7 * MINUTE))
        run(at = 7 * MINUTE + SECOND)
        happen(unlocked(7 * MINUTE + 1500))
        moveHistory(by = -2 * SECOND)
        // Back in the stored unlocks' times the new unlock is at 7 min 1.5 s, still to come.
        run(at = 7 * MINUTE + 1200)
        assertEquals(listOf(5 * MINUTE, 7 * MINUTE), storedUnlocks())
        run(at = 10 * MINUTE)
        assertEquals(listOf(5 * MINUTE, 7 * MINUTE, 7 * MINUTE + 1500), storedUnlocks())
        assertRunAgainChangesNothing(at = 12 * MINUTE)
    }

    @Test
    fun keepsUnlocksInOrderAfterAMoveBack() {
        happen(unlocked(5 * MINUTE), unlocked(7 * MINUTE))
        run(at = 7 * MINUTE + SECOND)
        // After the move, the first comes before the stored one and the second after it: the
        // first, back in the stored unlocks' times, would come after the second.
        happen(unlocked(7 * MINUTE + 1500), unlocked(7 * MINUTE + 2200))
        moveHistory(by = -2 * SECOND)
        run(at = 10 * MINUTE)
        assertEquals(listOf(5 * MINUTE, 7 * MINUTE, 7 * MINUTE + 200), storedUnlocks())
        assertRunAgainChangesNothing(at = 12 * MINUTE)
    }
}
