package net.activitywatch.android

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
