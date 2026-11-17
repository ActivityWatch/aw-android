package net.activitywatch.android

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*
import java.util.UUID
import java.time.Instant

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class BasicTest {
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("net.activitywatch.android.debug", appContext.packageName)
    }

    @Test
    fun getBuckets() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val ri = RustInterface(appContext)
        val bucketId = "test-${UUID.randomUUID()}"
        ri.createBucket("""{"id": "$bucketId", "type": "test", "hostname": "test", "client": "test"}""")
        // Other tests and background watchers may create buckets concurrently.
        val bucket = ri.getBucketsJSON().getJSONObject(bucketId)
        assertEquals(bucketId, bucket.getString("id"))
        assertEquals("test", bucket.getString("type"))
        assertEquals("test", bucket.getString("hostname"))
        assertEquals("test", bucket.getString("client"))
    }

    @Test
    fun createHeartbeat() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val ri = RustInterface(appContext)
        val bucketId = "test-${UUID.randomUUID()}"
        ri.createBucket("""{"id": "$bucketId", "type": "test", "hostname": "test", "client": "test"}""")
        ri.heartbeat(bucketId, """{"timestamp": "${Instant.now()}", "duration": 0, "data": {"key": "value"}}""", 1.0)
        val events = ri.getEventsJSON(bucketId, 3)
        assertEquals(1, events.length())
        assertEquals("value", events.getJSONObject(0).getJSONObject("data").getString("key"))
    }
}
