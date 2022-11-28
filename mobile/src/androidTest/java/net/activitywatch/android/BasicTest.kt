package net.activitywatch.android

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*
import java.lang.Thread.sleep
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
        // TODO: Clear test buckets before test
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val ri = RustInterface(appContext)
        val bucketId = "test-${Math.random()}"
        val oldLen = ri.getBucketsJSON().length()
        ri.createBucket("""{"id": "$bucketId", "type": "test", "hostname": "test", "client": "test"}""")
        assertEquals(oldLen + 1, ri.getBucketsJSON().length())
    }

    @Test
    fun createHeartbeat() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val ri = RustInterface(appContext)
        val bucketId = "test-${Math.random()}"
        ri.createBucket("""{"id": "$bucketId", "type": "test", "hostname": "test", "client": "test"}""")
        val oldLen = ri.getEventsJSON(bucketId, 3).length()
        ri.heartbeat(bucketId, """{"timestamp": "${Instant.now()}", "duration": 0, "data": {"key": "value"}}""", 1.0)
        assertEquals(oldLen + 1, ri.getEventsJSON(bucketId, 3).length())
    }
}
