package net.activitywatch.android

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class SyncServerReadinessTest {
    @Test fun missingNativeLibraryBecomesReportableStartupFailure() {
        val nativeError = UnsatisfiedLinkError("aw_server unavailable")
        try {
            awaitSyncServer(start = { throw nativeError }, ready = { false })
            fail("Native loading failure must not proceed to sync")
        } catch (e: IOException) {
            assertSame(nativeError, e.cause)
            assertTrue(e.message!!.contains("native library unavailable"))
        }
    }

    @Test fun existingServerNeedsNoRestart() {
        awaitSyncServer(start = { fail("Healthy server must not restart") }, ready = { true })
    }

    @Test fun coldStartWaitsForServerBeforeReturning() {
        var time = 0L
        var starts = 0
        awaitSyncServer(
            start = { starts++ }, ready = { starts > 0 && time >= 300 },
            now = { time }, pause = { time += it },
        )
        assertEquals(1, starts)
        assertEquals(300, time)
    }

    @Test fun failedStartupCannotProceedToSync() {
        var time = 0L
        try {
            awaitSyncServer(start = {}, ready = { false }, now = { time },
                pause = { time += it }, timeoutMs = 250)
            fail("Dead server must fail before native sync")
        } catch (_: IOException) {
            assertEquals(250, time)
        }
    }

    @Test fun cancellationStopsReadinessWait() {
        try {
            awaitSyncServer(start = {}, ready = { false }, pause = { throw InterruptedException() })
            fail("Cancellation must propagate")
        } catch (_: InterruptedException) { }
    }
}
