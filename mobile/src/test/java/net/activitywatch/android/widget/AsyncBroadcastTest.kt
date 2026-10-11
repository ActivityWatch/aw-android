package net.activitywatch.android.widget

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the widget's top Play crash
 * (`CategoryTimeWidgetProvider$onUpdate$1.invokeSuspend` → `IllegalStateException` from
 * `PendingResult.finish()` → `sendFinished()`, "Broadcast already finished").
 */
class AsyncBroadcastTest {

    private val uncaught = CopyOnWriteArrayList<Throwable>()
    private val logged = CopyOnWriteArrayList<String>()
    private val log: (String, Throwable?) -> Unit = { msg, _ -> logged.add(msg) }

    /** Like the receiver's IO scope, but records what would have killed the process. */
    private fun scope() = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> uncaught.add(e) }
    )

    @Test
    fun finishThatThrowsAlreadyFinished_doesNotEscapeTheCoroutine() = runBlocking {
        val finishCalls = AtomicInteger()
        val job = AsyncBroadcast.launch(
            finish = {
                finishCalls.incrementAndGet()
                throw IllegalStateException("Broadcast already finished")
            },
            scope = scope(),
            log = log,
        ) {}
        job.join()

        assertEquals(1, finishCalls.get())
        assertTrue("finish() exception escaped: $uncaught", uncaught.isEmpty())
        assertTrue(logged.any { it.contains("already finished") })
    }

    @Test
    fun successfulWork_finishesExactlyOnce_withoutFallback() = runBlocking {
        val finishCalls = AtomicInteger()
        val ran = AtomicInteger()
        val failure = AtomicReference<Throwable?>(null)
        AsyncBroadcast.launch(
            finish = { finishCalls.incrementAndGet() },
            scope = scope(),
            log = log,
            onFailure = { failure.set(it) },
        ) { ran.incrementAndGet() }.join()

        assertEquals(1, ran.get())
        assertEquals(1, finishCalls.get())
        assertNull(failure.get())
        assertTrue(uncaught.isEmpty())
    }

    @Test
    fun failingWork_rendersFallback_andStillFinishes() = runBlocking {
        val finishCalls = AtomicInteger()
        val failure = AtomicReference<Throwable?>(null)
        AsyncBroadcast.launch(
            finish = { finishCalls.incrementAndGet() },
            scope = scope(),
            log = log,
            onFailure = { failure.set(it) },
        ) {
            // e.g. System.loadLibrary failing, which is an Error, not an Exception
            throw UnsatisfiedLinkError("no aw_server")
        }.join()

        assertTrue(failure.get() is UnsatisfiedLinkError)
        assertEquals(1, finishCalls.get())
        assertTrue(uncaught.isEmpty())
    }

    @Test
    fun throwingFallback_isLogged_andStillFinishes() = runBlocking {
        val finishCalls = AtomicInteger()
        AsyncBroadcast.launch(
            finish = { finishCalls.incrementAndGet() },
            scope = scope(),
            log = log,
            onFailure = { throw IllegalArgumentException("widget removed") },
        ) { error("datastore not ready") }.join()

        assertEquals(1, finishCalls.get())
        assertTrue(uncaught.isEmpty())
    }

    /**
     * The datastore work blocks in calls that ignore cancellation (JNI, a latch wait). The
     * broadcast must still be finished within the budget, not when the blocked call returns.
     */
    @Test
    fun blockingWorkPastBudget_finishesBroadcastOnTime_andRendersFallback() = runBlocking {
        val finished = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val start = System.nanoTime()
        val job = AsyncBroadcast.launch(
            finish = { finished.countDown() },
            budgetMs = 200,
            scope = scope(),
            log = log,
            onFailure = { failure.set(it) },
        ) {
            release.await() // not cancellable, like a JNI call
        }

        assertTrue("broadcast not finished within budget", finished.await(2, TimeUnit.SECONDS))
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        assertTrue("finished after ${elapsedMs} ms", elapsedMs < 1500)
        job.join()
        assertTrue(failure.get() is TimeoutException)
        release.countDown()
        assertTrue(uncaught.isEmpty())
    }

    @Test
    fun finishOnce_ignoresSecondCall() {
        val calls = AtomicInteger()
        val once = AsyncBroadcast.FinishOnce({ calls.incrementAndGet() }, log)
        once.finish()
        once.finish()
        assertEquals(1, calls.get())
    }
}
