package net.activitywatch.android.watcher

import android.app.usage.UsageEvents
import net.activitywatch.android.data.UsageEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenStateSpansTest {

    private val t = 1_700_000_000_000L
    private val boot = t + 600_000L

    private val unlocked = ScreenState(interactive = true, keyguardShown = false)
    private val lockScreen = ScreenState(interactive = true, keyguardShown = true)
    private val offLocked = ScreenState(interactive = false, keyguardShown = true)
    private val offNotYetLocked = ScreenState(interactive = false, keyguardShown = false)

    private fun event(type: Int, at: Long, packageName: String = "android") =
        UsageEvent(type, at, packageName, "")

    private fun on(at: Long) = event(UsageEvents.Event.SCREEN_INTERACTIVE, at)
    private fun off(at: Long) = event(UsageEvents.Event.SCREEN_NON_INTERACTIVE, at)
    private fun shown(at: Long) = event(UsageEvents.Event.KEYGUARD_SHOWN, at)
    private fun hidden(at: Long) = event(UsageEvents.Event.KEYGUARD_HIDDEN, at)
    private fun shutdown(at: Long) = event(UsageEvents.Event.DEVICE_SHUTDOWN, at)
    private fun startup(at: Long) = event(UsageEvents.Event.DEVICE_STARTUP, at)

    // The last span as stored by a read half a second after it began.
    private fun storedSince(state: ScreenState, start: Long = t) =
        ScreenStateSpan(start, start + 500, state)

    // The events Android records together when the phone is first unlocked after a boot.
    private fun bootEvents(at: Long) = listOf(on(at), shown(at), hidden(at), startup(at))

    private fun spans(
        events: List<UsageEvent>,
        stored: ScreenStateSpan?,
        now: Long,
    ): List<ScreenStateSpan> = parseScreenStateSpans(events, stored, now).spans

    // Reads the events at each of [readTimes] as the watcher does, from the start of the last
    // stored span, and stores what each read emits as the server's heartbeat merge does with
    // no pulse time: a span with the same data that starts within the last one extends it.
    private fun readInSteps(
        events: List<UsageEvent>,
        readTimes: List<Long>,
    ): List<ScreenStateSpan> {
        val stored = mutableListOf<ScreenStateSpan>()
        for (readAt in readTimes) {
            val last = stored.lastOrNull()
            val begin = last?.start ?: 0L
            val read = events.filter { it.timeStamp >= begin && it.timeStamp < readAt }
            for (span in spans(read, last, readAt)) {
                val top = stored.lastOrNull()
                if (top != null && top.state == span.state && span.start in top.start..top.end) {
                    stored[stored.lastIndex] = top.copy(end = maxOf(top.end, span.end))
                } else {
                    stored.add(span)
                }
            }
        }
        return stored
    }

    @Test
    fun `an unlock goes from screen off through the lock screen to unlocked`() {
        val result = spans(
            listOf(off(t), shown(t), on(t + 10_000), hidden(t + 10_300)),
            stored = null,
            now = t + 60_000,
        )

        assertEquals(
            listOf(
                ScreenStateSpan(t, t + 10_000, offLocked),
                ScreenStateSpan(t + 10_000, t + 10_300, lockScreen),
                ScreenStateSpan(t + 10_300, t + 60_000, unlocked),
            ),
            result,
        )
    }

    @Test
    fun `a fingerprint unlock can hide the keyguard before the screen turns on`() {
        val result = spans(
            listOf(hidden(t + 5_000), on(t + 5_040)),
            stored = storedSince(offLocked),
            now = t + 20_000,
        )

        assertEquals(
            listOf(
                ScreenStateSpan(t, t + 5_000, offLocked),
                ScreenStateSpan(t + 5_000, t + 5_040, offNotYetLocked),
                ScreenStateSpan(t + 5_040, t + 20_000, unlocked),
            ),
            result,
        )
    }

    @Test
    fun `a glance turns the screen on and off without unlocking`() {
        val result = spans(
            listOf(on(t + 1_000), off(t + 4_000)),
            stored = storedSince(offLocked),
            now = t + 10_000,
        )

        assertEquals(
            listOf(
                ScreenStateSpan(t, t + 1_000, offLocked),
                ScreenStateSpan(t + 1_000, t + 4_000, lockScreen),
                ScreenStateSpan(t + 4_000, t + 10_000, offLocked),
            ),
            result,
        )
    }

    @Test
    fun `the keyguard can be shown some seconds after the screen goes off`() {
        val result = spans(
            listOf(off(t + 1_000), shown(t + 6_000)),
            stored = storedSince(unlocked),
            now = t + 20_000,
        )

        assertEquals(
            listOf(
                ScreenStateSpan(t, t + 1_000, unlocked),
                ScreenStateSpan(t + 1_000, t + 6_000, offNotYetLocked),
                ScreenStateSpan(t + 6_000, t + 20_000, offLocked),
            ),
            result,
        )
    }

    @Test
    fun `the screen back on before the keyguard is shown goes straight to unlocked`() {
        val result = spans(
            listOf(off(t + 1_000), on(t + 3_000)),
            stored = storedSince(unlocked),
            now = t + 10_000,
        )

        assertEquals(
            listOf(
                ScreenStateSpan(t, t + 1_000, unlocked),
                ScreenStateSpan(t + 1_000, t + 3_000, offNotYetLocked),
                ScreenStateSpan(t + 3_000, t + 10_000, unlocked),
            ),
            result,
        )
    }

    @Test
    fun `activity events change nothing, a screensaver included`() {
        val dream = "com.android.dreams.basic"
        val result = spans(
            listOf(
                event(UsageEvents.Event.ACTIVITY_RESUMED, t + 100, dream),
                event(UsageEvents.Event.ACTIVITY_PAUSED, t + 5_000, dream),
            ),
            stored = storedSince(offLocked),
            now = t + 10_000,
        )

        assertEquals(listOf(ScreenStateSpan(t, t + 10_000, offLocked)), result)
    }

    @Test
    fun `nothing is emitted until both states are known`() {
        val result = spans(
            listOf(hidden(t), on(t + 5_000), off(t + 9_000)),
            stored = null,
            now = t + 10_000,
        )

        assertEquals(
            listOf(
                ScreenStateSpan(t + 5_000, t + 9_000, unlocked),
                ScreenStateSpan(t + 9_000, t + 10_000, offNotYetLocked),
            ),
            result,
        )
    }

    @Test
    fun `changes within one millisecond leave no zero-length span`() {
        val result = spans(
            listOf(on(t + 1_000), hidden(t + 1_000)),
            stored = storedSince(offLocked),
            now = t + 2_000,
        )

        assertEquals(
            listOf(
                ScreenStateSpan(t, t + 1_000, offLocked),
                ScreenStateSpan(t + 1_000, t + 2_000, unlocked),
            ),
            result,
        )
    }

    @Test
    fun `events of the same millisecond apply in the order given`() {
        val stored = storedSince(lockScreen)

        assertEquals(
            listOf(ScreenStateSpan(t, t + 2_000, lockScreen)),
            spans(listOf(hidden(t + 1_000), shown(t + 1_000)), stored, now = t + 2_000),
        )
        assertEquals(
            listOf(
                ScreenStateSpan(t, t + 1_000, lockScreen),
                ScreenStateSpan(t + 1_000, t + 2_000, unlocked),
            ),
            spans(listOf(shown(t + 1_000), hidden(t + 1_000)), stored, now = t + 2_000),
        )
    }

    @Test
    fun `events are put in time order before they are applied`() {
        val result = spans(
            listOf(off(t + 4_000), on(t + 1_000)),
            stored = storedSince(offLocked),
            now = t + 10_000,
        )

        assertEquals(
            listOf(
                ScreenStateSpan(t, t + 1_000, offLocked),
                ScreenStateSpan(t + 1_000, t + 4_000, lockScreen),
                ScreenStateSpan(t + 4_000, t + 10_000, offLocked),
            ),
            result,
        )
    }

    @Test
    fun `reading again from the stored span extends it with the same start and state`() {
        val first = spans(listOf(off(t), shown(t)), stored = null, now = t + 5_000)
        assertEquals(listOf(ScreenStateSpan(t, t + 5_000, offLocked)), first)

        // Read again from the stored start; a clock correction has meanwhile moved the
        // saved events that started the span by a second.
        val second = spans(
            listOf(off(t + 1_000), shown(t + 1_000), on(t + 8_000)),
            stored = first.last(),
            now = t + 10_000,
        )

        assertEquals(
            listOf(
                ScreenStateSpan(t, t + 8_000, offLocked),
                ScreenStateSpan(t + 8_000, t + 10_000, lockScreen),
            ),
            second,
        )
    }

    @Test
    fun `a clock correction moving an earlier change into the stored span adds no copy`() {
        // The span began when the keyguard was hidden, 2 ms after it was shown. A correction
        // of one second moves both into the read from the span's start.
        val result = spans(
            listOf(shown(t + 998), hidden(t + 1_000)),
            stored = ScreenStateSpan(t, t + 60_000, unlocked),
            now = t + 90_000,
        )

        assertEquals(listOf(ScreenStateSpan(t, t + 90_000, unlocked)), result)
    }

    @Test
    fun `a change added after the stored span was read ends it at its end`() {
        // As above, then a screen-off that Android added only after the read at t + 60 s,
        // dated just before it: placed at the stored end, it does not overlap the stored span.
        val result = spans(
            listOf(shown(t + 998), hidden(t + 1_000), off(t + 59_500)),
            stored = ScreenStateSpan(t, t + 60_000, unlocked),
            now = t + 90_000,
        )

        assertEquals(
            listOf(
                ScreenStateSpan(t, t + 60_000, unlocked),
                ScreenStateSpan(t + 60_000, t + 90_000, offNotYetLocked),
            ),
            result,
        )
    }

    @Test
    fun `several late changes keep only the state they lead to`() {
        val result = spans(
            listOf(off(t + 59_400), shown(t + 59_700)),
            stored = ScreenStateSpan(t, t + 60_000, unlocked),
            now = t + 90_000,
        )

        assertEquals(
            listOf(
                ScreenStateSpan(t, t + 60_000, unlocked),
                ScreenStateSpan(t + 60_000, t + 90_000, offLocked),
            ),
            result,
        )
    }

    @Test
    fun `a stored span with no events since is extended to now`() {
        val result = spans(emptyList(), stored = storedSince(unlocked), now = t + 30_000)

        assertEquals(listOf(ScreenStateSpan(t, t + 30_000, unlocked)), result)
    }

    @Test
    fun `a span closed by a shutdown comes out the same when read again`() {
        val result = spans(
            listOf(shutdown(t + 1_000)),
            stored = ScreenStateSpan(t, t + 1_000, unlocked),
            now = t + 5_000,
        )

        assertEquals(listOf(ScreenStateSpan(t, t + 1_000, unlocked)), result)
    }

    @Test
    fun `an orderly restart leaves no span between the shutdown and the startup`() {
        val result = parseScreenStateSpans(
            // Android records the shutdown twice, at times up to a second apart.
            listOf(shutdown(t + 1_000), shutdown(t + 2_000)) + bootEvents(boot),
            stored = storedSince(unlocked),
            now = boot + 30_000,
        )

        assertEquals(
            listOf(
                ScreenStateSpan(t, t + 1_000, unlocked),
                ScreenStateSpan(boot, boot + 30_000, unlocked),
            ),
            result.spans,
        )
        assertEquals(emptyList<Long>(), result.startupsWithoutShutdown)
    }

    @Test
    fun `a startup after the stored span with no shutdown before it ends the stored state`() {
        // After a crash Android dates its shutdown at its last save, which can be before the
        // stored span: the read from that span's start then reaches the startup first.
        val result = parseScreenStateSpans(
            bootEvents(boot) + listOf(off(boot + 20_000), shown(boot + 20_000)),
            stored = storedSince(unlocked),
            now = boot + 30_000,
        )

        assertEquals(
            listOf(
                ScreenStateSpan(boot, boot + 20_000, unlocked),
                ScreenStateSpan(boot + 20_000, boot + 30_000, offLocked),
            ),
            result.spans,
        )
        assertEquals(emptyList<Long>(), result.startupsWithoutShutdown)
    }

    @Test
    fun `the events that set a stored state no longer holding do not bring it back`() {
        // As above, but the events that began the stored span are still in the history, with
        // no shutdown after them in the read.
        val result = parseScreenStateSpans(
            listOf(on(t), hidden(t)) + bootEvents(boot) +
                listOf(off(boot + 20_000), shown(boot + 20_000)),
            stored = storedSince(unlocked),
            now = boot + 30_000,
        )

        assertEquals(
            listOf(
                ScreenStateSpan(boot, boot + 20_000, unlocked),
                ScreenStateSpan(boot + 20_000, boot + 30_000, offLocked),
            ),
            result.spans,
        )
    }

    @Test
    fun `the read after a boot resumes the span that began with it`() {
        // The boot events can fall on different milliseconds. The span began with the last
        // of them, so reading from its start finds the startup again but not the screen event.
        val result = parseScreenStateSpans(
            listOf(hidden(boot), startup(boot + 3), off(boot + 120_000), shown(boot + 125_000)),
            stored = ScreenStateSpan(boot, boot + 60_000, unlocked),
            now = boot + 200_000,
        )

        assertEquals(
            listOf(
                ScreenStateSpan(boot, boot + 120_000, unlocked),
                ScreenStateSpan(boot + 120_000, boot + 125_000, offNotYetLocked),
                ScreenStateSpan(boot + 125_000, boot + 200_000, offLocked),
            ),
            result.spans,
        )
    }

    @Test
    fun `reads in steps store the same spans as a single read`() {
        val bootEventsApart = listOf(on(boot - 4), shown(boot - 2), hidden(boot), startup(boot + 3))
        val events = listOf(
            off(t), shown(t), on(t + 10_000), hidden(t + 10_400), off(t + 70_000), on(t + 72_000),
            shutdown(t + 90_000), shutdown(t + 91_000),
        ) + bootEventsApart + listOf(
            off(boot + 50_000), shown(boot + 55_000), on(boot + 80_000), off(boot + 81_000),
        )
        val end = boot + 100_000
        val singleRead = spans(events, stored = null, now = end)

        for (step in listOf(1_000L, 7_000L, 30_000L)) {
            // No read runs between the boot events and the startup: apps start after it.
            val readTimes = (t + step until end step step)
                .filterNot { it in boot - 4..boot + 3 } + end
            val stored = readInSteps(events, readTimes)
            assertEquals("reads every $step ms", singleRead, stored)
            assertTrue("no overlap", stored.zipWithNext().all { (a, b) -> a.end <= b.start })
        }
    }

    @Test
    fun `startups before the end of the stored span are not reported again`() {
        // The stored span began with a boot; after a crash Android dated its shutdown before
        // that span, so the read from its start finds the first startup, then the second.
        val secondBoot = boot + 600_000
        val result = parseScreenStateSpans(
            listOf(hidden(boot), startup(boot + 3)) + bootEvents(secondBoot),
            stored = ScreenStateSpan(boot, boot + 60_000, unlocked),
            now = secondBoot + 1_000,
        )

        assertEquals(
            listOf(ScreenStateSpan(secondBoot, secondBoot + 1_000, unlocked)),
            result.spans,
        )
        assertEquals(emptyList<Long>(), result.startupsWithoutShutdown)
    }

    @Test
    fun `a single startup with no shutdown before it is not reported`() {
        // Android records a shutdown before every startup, so this one's lies before the
        // read: after a crash, or where the history begins.
        val result = parseScreenStateSpans(
            listOf(on(t), hidden(t)) + bootEvents(boot),
            stored = null,
            now = boot + 1_000,
        )

        assertEquals(emptyList<Long>(), result.startupsWithoutShutdown)
    }

    @Test
    fun `a startup after another with no shutdown between is reported`() {
        val secondBoot = boot + 60_000
        val result = parseScreenStateSpans(
            listOf(shutdown(t)) + bootEvents(boot) + bootEvents(secondBoot),
            stored = null,
            now = secondBoot + 1_000,
        )

        // With no shutdown to close it, the span open before the second startup runs on
        // through it.
        assertEquals(listOf(ScreenStateSpan(boot, secondBoot + 1_000, unlocked)), result.spans)
        assertEquals(listOf(secondBoot), result.startupsWithoutShutdown)
    }
}
