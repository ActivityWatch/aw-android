package net.activitywatch.android

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ParseBucketsJsonTest {
    @Test
    fun jniErrorObjectThrows() {
        // EventParsingWorker and NotifyWorker treat JSONException as "server not reachable".
        assertThrows(JSONException::class.java) {
            parseBucketsJSON("""{"error":"Failed to get buckets: RecvError"}""")
        }
    }

    @Test
    fun bucketNamedErrorIsKept() {
        val buckets = parseBucketsJSON("""{"error":{"id":"error","type":"currentwindow"}}""")
        assertEquals(listOf("error"), buckets.keys().asSequence().toList())
    }

    @Test
    fun emptyBucketMapIsValid() {
        assertEquals(0, parseBucketsJSON("{}").length())
    }
}
