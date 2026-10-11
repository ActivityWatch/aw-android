package net.activitywatch.android.watcher

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.activitywatch.android.widget.AsyncBroadcast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the AlarmReceiver goAsync + finish pattern after applying FinishOnce.
 *
 * Without FinishOnce, an OEM pre-finishing the broadcast before the coroutine's `finally` block
 * causes `PendingResult.finish()` → `IllegalStateException("Broadcast already finished")`, which
 * escapes the coroutine and kills the process. This is the same crash class fixed for the widget
 * in ActivityWatch/aw-android#360.
 */
class AlarmReceiverFinishOnceTest {

    private val uncaught = CopyOnWriteArrayList<Throwable>()
    private val logged = CopyOnWriteArrayList<String>()
    private val log: (String, Throwable?) -> Unit = { msg, _ -> logged.add(msg) }

    private fun scope() = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> uncaught.add(e) }
    )

    /**
     * OEM pre-finishes the broadcast before the coroutine's finally block; without FinishOnce
     * this would throw IllegalStateException and escape the coroutine.
     */
    @Test
    fun oemDoubleFinish_doesNotEscapeCoroutine() = runBlocking {
        val finishCalls = AtomicInteger()
        val finish = AsyncBroadcast.FinishOnce(
            finish = {
                finishCalls.incrementAndGet()
                throw IllegalStateException("Broadcast already finished")
            },
            log = log,
        )

        val job = scope().launch {
            try {
                // heartbeat work (no-op in test)
            } catch (e: Exception) {
                // logged in production
            } finally {
                finish.finish()
            }
        }
        job.join()

        assertEquals("finish() must be attempted exactly once", 1, finishCalls.get())
        assertTrue("IllegalStateException escaped the coroutine: $uncaught", uncaught.isEmpty())
        assertTrue("missing 'already finished' log entry", logged.any { it.contains("already finished") })
    }

    @Test
    fun normalHeartbeat_finishesExactlyOnce() = runBlocking {
        val finishCalls = AtomicInteger()
        val finish = AsyncBroadcast.FinishOnce({ finishCalls.incrementAndGet() }, log)

        val job = scope().launch {
            try {
                // heartbeat work succeeds
            } finally {
                finish.finish()
            }
        }
        job.join()

        assertEquals(1, finishCalls.get())
        assertTrue(uncaught.isEmpty())
    }

    @Test
    fun heartbeatException_stillFinishes() = runBlocking {
        val finishCalls = AtomicInteger()
        val finish = AsyncBroadcast.FinishOnce({ finishCalls.incrementAndGet() }, log)

        val job = scope().launch {
            try {
                throw RuntimeException("server unreachable")
            } catch (e: Exception) {
                // logged
            } finally {
                finish.finish()
            }
        }
        job.join()

        assertEquals(1, finishCalls.get())
        assertTrue(uncaught.isEmpty())
    }
}
