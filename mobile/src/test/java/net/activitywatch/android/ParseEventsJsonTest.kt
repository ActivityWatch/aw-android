package net.activitywatch.android

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ParseEventsJsonTest {
    @Test
    fun jniErrorObjectThrows() {
        // aw-server-rust's create_error_object() reports a failed getEvents as an object.
        // It must not be read as an empty event list.
        val e = assertThrows(JSONException::class.java) {
            parseEventsJSON("""{"error":"Failed to get events: RecvError"}""")
        }
        assertEquals(true, e.message!!.contains("Failed to get events"))
    }

    @Test
    fun emptyEventListIsValid() {
        assertEquals(0, parseEventsJSON("[]").length())
    }

    @Test
    fun eventListIsParsed() {
        val events = parseEventsJSON(
            """[{"id":1,"timestamp":"2026-11-06T03:00:00Z","duration":1.0,"data":{"app":"x"}}]"""
        )
        assertEquals(1, events.length())
        assertEquals(1, events.getJSONObject(0).getInt("id"))
    }

    @Test
    fun malformedResponseThrows() {
        assertThrows(JSONException::class.java) { parseEventsJSON("<html>Server not running") }
    }

    @Test
    fun nonErrorObjectThrows() {
        // Any object is "not an event list", even one without an `error` key.
        assertThrows(JSONException::class.java) { parseEventsJSON("""{"buckets":[]}""") }
    }
}
