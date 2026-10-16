package net.activitywatch.android.watcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.threeten.bp.Instant

class ScreenStateResumePointTest {

    private val unlocked = ScreenState(interactive = true, keyguardShown = false)
    private val start = Instant.parse("2026-01-15T10:00:00Z").toEpochMilli()

    private fun event(timestamp: String, duration: Double, data: JSONObject) =
        JSONObject().put("timestamp", timestamp).put("duration", duration).put("data", data)

    @Test
    fun `the last event is read from a list, which an empty bucket leaves empty`() {
        val last = event("2026-01-15T10:00:00Z", 90.5, unlocked.toEventData())

        assertEquals(last.toString(), parseLastEvent("[$last]").getOrThrow().toString())
        assertNull(parseLastEvent("[]").getOrThrow())
    }

    @Test
    fun `an error from the datastore is not taken for an empty bucket`() {
        assertTrue(parseLastEvent("""{"error": "datastore unavailable"}""").isFailure)
        assertTrue(parseLastEvent("").isFailure)
    }

    @Test
    fun `an empty bucket is read from the start of history`() {
        assertEquals(ResumePoint(stored = null, begin = 0L), resumePoint(null))
    }

    @Test
    fun `the last span is resumed from its start, with its end`() {
        val last = event("2026-01-15T10:00:00Z", 90.5, unlocked.toEventData())

        assertEquals(
            ResumePoint(ScreenStateSpan(start, start + 90_500, unlocked), begin = start),
            resumePoint(last),
        )
    }

    @Test
    fun `timestamps with any fractional precision are read`() {
        for (timestamp in listOf("2026-01-15T10:00:00.250+00:00", "2026-01-15T10:00:00.250000Z")) {
            val last = event(timestamp, 1.0, unlocked.toEventData())

            assertEquals(start + 250, resumePoint(last)?.stored?.start)
        }
    }

    @Test
    fun `data this code cannot read is left as it is and read after`() {
        val last = event("2026-01-15T10:00:00Z", 60.0, JSONObject().put("status", "afk"))

        assertEquals(ResumePoint(stored = null, begin = start + 60_000), resumePoint(last))
    }

    @Test
    fun `an event that cannot be placed in time stops the read`() {
        assertNull(resumePoint(event("yesterday", 60.0, unlocked.toEventData())))
    }
}
