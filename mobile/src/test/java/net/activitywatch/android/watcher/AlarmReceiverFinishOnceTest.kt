package net.activitywatch.android.watcher

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests the completion path called by AlarmReceiver, not a copy of its coroutine. */
class AlarmReceiverFinishOnceTest {
    private val uncaught = CopyOnWriteArrayList<Throwable>()
    private val logged = CopyOnWriteArrayList<Pair<String, Throwable?>>()
    private val log: (String, Throwable?) -> Unit = { msg, error -> logged.add(msg to error) }

    private fun scope() = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> uncaught.add(e) }
    )

    /** Simulates the framework finishing first, so the receiver's finish attempt throws. */
    @Test
    fun oemDoubleFinish_doesNotEscapeCoroutine() = runBlocking {
        val finishCalls = AtomicInteger()
        val alreadyFinished = java.util.concurrent.atomic.AtomicBoolean(false)
        val pendingFinish = {
            finishCalls.incrementAndGet()
            check(alreadyFinished.compareAndSet(false, true)) { "Broadcast already finished" }
        }
        pendingFinish() // Framework/OEM finishes the broadcast before our coroutine completes.

        launchAlarmHeartbeats(
            finish = pendingFinish,
            scope = scope(),
            log = log,
            work = {},
        ).join()

        assertEquals("one framework finish and one receiver attempt", 2, finishCalls.get())
        assertTrue("IllegalStateException escaped the coroutine: $uncaught", uncaught.isEmpty())
        assertTrue(logged.any { it.first.contains("already finished") && it.second is IllegalStateException })
    }

    @Test
    fun normalHeartbeat_finishesExactlyOnceAfterWork() = runBlocking {
        val events = CopyOnWriteArrayList<String>()
        launchAlarmHeartbeats(
            finish = { events.add("finish"); Unit },
            scope = scope(),
            log = log,
            work = { events.add("heartbeat") },
        ).join()

        assertEquals(listOf("heartbeat", "finish"), events.toList())
        assertTrue(uncaught.isEmpty())
        assertTrue(logged.isEmpty())
    }

    @Test
    fun heartbeatException_stillFinishesAndLogsFailure() = runBlocking {
        val finishCalls = AtomicInteger()
        val failure = RuntimeException("server unreachable")
        launchAlarmHeartbeats(
            finish = { finishCalls.incrementAndGet(); Unit },
            scope = scope(),
            log = log,
            work = { throw failure },
        ).join()

        assertEquals(1, finishCalls.get())
        assertTrue(uncaught.isEmpty())
        assertEquals(1, logged.size)
        assertSame(failure, logged.single().second)
    }
}
