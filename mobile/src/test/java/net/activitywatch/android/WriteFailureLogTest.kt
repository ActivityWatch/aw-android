package net.activitywatch.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WriteFailureLogTest {
    private val ok = "Heartbeat successfully received"
    private val noSuchBucket = """{"error":"Something went wrong when trying to send heartbeat: NoSuchBucket(\"b\")"}"""
    private val workerGone = """{"error":"Failed to send request, datastore worker is gone"}"""

    private val lines = mutableListOf<String>()
    private val log = WriteFailureLog { lines.add(it) }

    @Test
    fun successIsNotLogged() {
        repeat(3) { log.record("b", ok) }
        assertEquals(emptyList<String>(), lines)
    }

    @Test
    fun onlyTheFirstFailureOfARunIsLogged() {
        log.record("b", workerGone)
        log.record("b", workerGone)
        log.record("b", noSuchBucket)
        assertEquals(1, lines.size)
        assertTrue(lines[0], lines[0].contains("'b'"))
        assertTrue(lines[0], lines[0].contains("datastore worker is gone"))
    }

    @Test
    fun recoveryReportsHowManyWritesFailed() {
        log.record("b", workerGone)
        log.record("b", workerGone)
        log.record("b", ok)
        log.record("b", ok)
        assertEquals(2, lines.size)
        assertTrue(lines[1], lines[1].contains("after 2 failed writes"))
    }

    @Test
    fun aFailureAfterRecoveryStartsANewRun() {
        log.record("b", workerGone)
        log.record("b", ok)
        log.record("b", workerGone)
        log.record("b", ok)
        assertEquals(4, lines.size)
        assertTrue(lines[3], lines[3].endsWith("after 1 failed write"))
    }

    @Test
    fun bucketsAreCountedSeparately() {
        log.record("a", workerGone)
        // A success in another bucket does not end the run in "a".
        log.record("b", ok)
        log.record("b", workerGone)
        log.record("a", workerGone)
        log.record("a", ok)
        assertEquals(3, lines.size)
        assertTrue(lines[0], lines[0].contains("'a'"))
        assertTrue(lines[1], lines[1].contains("'b'"))
        assertTrue(lines[2], lines[2].contains("'a'") && lines[2].contains("after 2 failed writes"))
    }

    @Test
    fun aMissingResponseIsAFailure() {
        log.record("b", null)
        assertEquals(1, lines.size)
    }

    @Test
    fun writeErrorOfReadsOnlyErrorObjects() {
        assertNull(writeErrorOf(ok))
        assertEquals("datastore worker is gone", writeErrorOf("""{"error":"datastore worker is gone"}"""))
        assertNull(writeErrorOf("""{"id":"b"}"""))
        assertNull(writeErrorOf("{not json"))
    }
}
