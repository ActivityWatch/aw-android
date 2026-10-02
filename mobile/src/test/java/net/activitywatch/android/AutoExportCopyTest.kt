package net.activitywatch.android

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoExportCopyTest {
    @Test
    fun copiesCompleteStream() = runBlocking {
        val bytes = ByteArray(20_000) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()
        copyExportCancellable(ByteArrayInputStream(bytes), output)
        assertArrayEquals(bytes, output.toByteArray())
    }

    @Test
    fun cancellationDuringReadDoesNotWriteThatChunk() {
        val job = Job()
        val output = ByteArrayOutputStream()
        val input = object : ByteArrayInputStream(ByteArray(20_000)) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(bytes, offset, length)
                job.cancel()
                return count
            }
        }
        var cancelled = false
        try {
            runBlocking(job) { copyExportCancellable(input, output) }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertEquals(0, output.size())
    }
}
