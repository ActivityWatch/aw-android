package net.activitywatch.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StagingResetTest {
    private class Prefs(var done: Boolean = false) {
        var markCalls = 0
        fun mark() {
            markCalls++
            done = true
        }
    }

    private fun attempt(prefs: Prefs, reset: () -> String) =
        StagingReset.runOnce(isDone = { prefs.done }, markDone = prefs::mark, reset = reset)

    @Test
    fun runsResetOnceThenSkips() {
        val prefs = Prefs()
        var resetCalls = 0
        val reset = { resetCalls++; """{"success": true}""" }

        assertEquals(StagingReset.Result.Done, attempt(prefs, reset))
        assertEquals(StagingReset.Result.AlreadyDone, attempt(prefs, reset))

        assertEquals(1, resetCalls)
        assertEquals(1, prefs.markCalls)
    }

    @Test
    fun alreadyDoneNeverCallsNative() {
        val prefs = Prefs(done = true)
        val result = attempt(prefs) { throw AssertionError("reset must not run") }
        assertEquals(StagingReset.Result.AlreadyDone, result)
        assertEquals(0, prefs.markCalls)
    }

    @Test
    fun nativeFailureLeavesFlagUnsetForRetry() {
        val prefs = Prefs()
        val result = attempt(prefs) { """{"success": false, "error": "path outside sync dir"}""" }
        assertEquals(StagingReset.Result.Failed("path outside sync dir"), result)
        assertEquals(0, prefs.markCalls)
    }

    @Test
    fun malformedResponseIsFailure() {
        val prefs = Prefs()
        assertTrue(attempt(prefs) { "not json" } is StagingReset.Result.Failed)
        assertTrue(attempt(prefs) { "{}" } is StagingReset.Result.Failed)
        assertEquals(0, prefs.markCalls)
    }

    @Test
    fun missingNativeSymbolDoesNotEscape() {
        // A native lib built before resetStaging existed throws UnsatisfiedLinkError,
        // which is not an Exception; it must not escape into the sync executor.
        val prefs = Prefs()
        val result = attempt(prefs) { throw UnsatisfiedLinkError("resetStaging") }
        assertTrue(result is StagingReset.Result.Failed)
        assertEquals(0, prefs.markCalls)
    }

    @Test
    fun prefKeyIsPerHostname() {
        assertEquals("stagingResetDone_poco_f8_ultra", StagingReset.prefKey("poco_f8_ultra"))
        assertNotEquals(StagingReset.prefKey("a"), StagingReset.prefKey("b"))
    }
}
