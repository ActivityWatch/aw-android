package net.activitywatch.android

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class DelayedIndicatorTest {
    private val calls = mutableListOf<String>()
    private val show = { calls += "show" }
    private val hide = { calls += "hide" }

    @Test
    fun fastWorkNeverShowsIndicator() = runBlocking {
        val result = withDelayedIndicator(200, show, hide) { 42 }
        assertEquals(42, result)
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun slowWorkShowsThenHidesIndicator() = runBlocking {
        withDelayedIndicator(10, show, hide) {
            delay(100)
            calls += "work-done"
        }
        assertEquals(listOf("show", "work-done", "hide"), calls)
    }

    @Test
    fun indicatorIsHiddenWhenSlowWorkThrows() = runBlocking {
        try {
            withDelayedIndicator(10, show, hide) {
                delay(100)
                throw IllegalStateException("boom")
            }
            fail("expected exception")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
        assertEquals(listOf("show", "hide"), calls)
    }

    @Test
    fun indicatorIsHiddenWhenSlowWorkIsCancelled() = runBlocking {
        val workStarted = CompletableDeferred<Unit>()
        val job =
            launch {
                withDelayedIndicator(10, show, hide) {
                    workStarted.complete(Unit)
                    delay(60_000)
                }
            }
        workStarted.await()
        // Wait for the delayed banner to appear before cancelling the caller.
        // Bounded so a missing banner fails the test instead of hanging the job.
        withTimeout(5_000) { while (calls.isEmpty()) delay(1) }
        job.cancelAndJoin()
        assertEquals(listOf("show", "hide"), calls)
    }

    @Test
    fun cancellationBeforeIndicatorShowsNeversShowsIt() = runBlocking {
        val workStarted = CompletableDeferred<Unit>()
        val job =
            launch {
                withDelayedIndicator(10_000, show, hide) {
                    workStarted.complete(Unit)
                    delay(60_000)
                }
            }
        workStarted.await()
        job.cancelAndJoin()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun fastFailureNeverShowsIndicator() = runBlocking {
        try {
            withDelayedIndicator(200, show, hide) { throw IllegalStateException("boom") }
            fail("expected exception")
        } catch (e: IllegalStateException) {
            // expected
        }
        assertEquals(emptyList<String>(), calls)
    }
}
