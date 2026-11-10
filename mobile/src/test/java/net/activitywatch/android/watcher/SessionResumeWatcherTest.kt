package net.activitywatch.android.watcher

import android.app.Application
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.jakewharton.threetenabp.AndroidThreeTen
import net.activitywatch.android.OffThreadInit
import net.activitywatch.android.RustInterface
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowUsageStatsManager.EventBuilder
import org.threeten.bp.Instant

/**
 * Two runs of the real session watcher, with Android's usage history moved back between them as
 * Android does when it corrects the clock (ShadowUsageStatsManager.simulateTimeChange). Only the
 * datastore is replaced, by [Datastore].
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28], application = Application::class,
    shadows = [SessionResumeWatcherTest.Datastore::class],
    instrumentedPackages = ["net.activitywatch.android.RustInterface"],
)
class SessionResumeWatcherTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val usage get() = shadowOf(context.getSystemService(UsageStatsManager::class.java))

    private val minute = 60_000L
    private val t0 = 1_793_000_000_000L

    @Before
    fun setup() {
        AndroidThreeTen.init(context)
        Datastore.buckets.clear()
        Datastore.unreadableNewest.clear()
    }

    private fun watcher(): SessionEventWatcher {
        val watcher = SessionEventWatcher(context)
        val init = OffThreadInit("test-datastore", "test", isMainThread = { false }) {
            RustInterface(context)
        }
        watcher.javaClass.getDeclaredField("rust\$delegate").apply { isAccessible = true }
            .set(watcher, lazyOf(init))
        return watcher
    }

    private fun happen(type: Int, at: Long, app: String = "android") = usage.addEvent(
        EventBuilder.buildEvent().setEventType(type).setTimeStamp(t0 + at)
            .setPackage(app).setClass("$app.Main").build()
    )

    private fun runAt(watcher: SessionEventWatcher, at: Long) {
        watcher.clock = { t0 + at }
        watcher.processEventsSinceLastUpdate()
    }

    private fun stored(bucketId: String) =
        Datastore.buckets[bucketId].orEmpty().sortedBy { it.start }.map {
            Triple(it.data.optString("package"), it.start - t0, it.end - t0)
        }

    @Test
    fun extendsTheOpenSessionAfterAndroidMovesItsHistoryBack() {
        happen(UsageEvents.Event.KEYGUARD_HIDDEN, 8 * minute)
        happen(UsageEvents.Event.ACTIVITY_RESUMED, 9 * minute, "com.example.chat")
        happen(UsageEvents.Event.ACTIVITY_PAUSED, 10 * minute, "com.example.chat")
        happen(UsageEvents.Event.ACTIVITY_RESUMED, 10 * minute, "com.example.video")
        val watcher = watcher()
        runAt(watcher, 20 * minute)

        happen(UsageEvents.Event.ACTIVITY_PAUSED, 35 * minute, "com.example.video")
        happen(UsageEvents.Event.ACTIVITY_RESUMED, 35 * minute, "com.example.chat")
        happen(UsageEvents.Event.ACTIVITY_PAUSED, 36 * minute, "com.example.chat")
        usage.simulateTimeChange(-2_000)
        runAt(watcher, 60 * minute)

        assertEquals(
            listOf(
                Triple("com.example.chat", 9 * minute, 10 * minute),
                Triple("com.example.video", 10 * minute, 35 * minute - 2_000 - 1),
                Triple("com.example.chat", 35 * minute - 2_000, 36 * minute - 2_000),
            ),
            stored(SESSION_BUCKET_ID),
        )
        assertEquals(listOf(Triple("", 8 * minute, 8 * minute)), stored(UNLOCK_BUCKET_ID))

        val before = stored(SESSION_BUCKET_ID) to stored(UNLOCK_BUCKET_ID)
        runAt(watcher, 70 * minute)
        assertEquals(before, stored(SESSION_BUCKET_ID) to stored(UNLOCK_BUCKET_ID))
    }

    @Test
    fun keepsGoingWhenTheNewestStoredSessionHasNoReadableTime() {
        happen(UsageEvents.Event.ACTIVITY_RESUMED, 9 * minute, "com.example.chat")
        happen(UsageEvents.Event.ACTIVITY_PAUSED, 10 * minute, "com.example.chat")
        happen(UsageEvents.Event.ACTIVITY_RESUMED, 10 * minute, "com.example.video")
        val watcher = watcher()
        runAt(watcher, 20 * minute)
        happen(UsageEvents.Event.ACTIVITY_PAUSED, 35 * minute, "com.example.video")
        happen(UsageEvents.Event.ACTIVITY_RESUMED, 35 * minute, "com.example.chat")

        // From now on the newest stored session, whichever it is, comes back without a time.
        Datastore.unreadableNewest += SESSION_BUCKET_ID
        runAt(watcher, 60 * minute)
        runAt(watcher, 70 * minute)
        assertEquals(
            listOf(
                Triple("com.example.chat", 9 * minute, 10 * minute),
                Triple("com.example.video", 10 * minute, 35 * minute),
                Triple("com.example.chat", 35 * minute, 70 * minute),
            ),
            stored(SESSION_BUCKET_ID),
        )
    }

    @Test
    fun keepsGoingWhenTheNewestStoredUnlockHasNoReadableTime() {
        happen(UsageEvents.Event.ACTIVITY_RESUMED, minute, "com.example.chat")
        happen(UsageEvents.Event.KEYGUARD_HIDDEN, 5 * minute)
        happen(UsageEvents.Event.KEYGUARD_HIDDEN, 8 * minute)
        val watcher = watcher()
        runAt(watcher, 10 * minute)
        happen(UsageEvents.Event.KEYGUARD_HIDDEN, 12 * minute)

        Datastore.unreadableNewest += UNLOCK_BUCKET_ID
        runAt(watcher, 20 * minute)
        assertEquals(
            listOf(Triple("", 5 * minute, 5 * minute), Triple("", 8 * minute, 8 * minute), Triple("", 12 * minute, 12 * minute)),
            stored(UNLOCK_BUCKET_ID),
        )
        assertEquals(listOf(Triple("com.example.chat", minute, 20 * minute)), stored(SESSION_BUCKET_ID))
    }

    class Stored(val start: Long, val end: Long, val data: JSONObject)

    // Replace JNI only. Writes behave like the datastore's heartbeat: merged into the event with
    // the latest start, keeping the later end, when the data is the same and the heartbeat
    // starts within that event (or within pulsetime after it); inserted otherwise.
    @Implements(RustInterface::class, isInAndroidSdk = false)
    class Datastore {
        companion object {
            internal val buckets = HashMap<String, MutableList<Stored>>()
            // Buckets whose newest event comes back without a timestamp.
            internal val unreadableNewest = mutableSetOf<String>()
        }

        // Android's org.json, used under Robolectric, has no JSONObject.similar().
        private fun sameData(a: JSONObject, b: JSONObject): Boolean {
            val keys = a.keys().asSequence().toSet()
            return keys == b.keys().asSequence().toSet() && keys.all { a.get(it).toString() == b.get(it).toString() }
        }

        @Implementation
        fun __constructor__(context: Context?) {}

        @Implementation
        fun createBucketsHelper(buckets: Map<String, String>, client: String) {}

        @Implementation
        fun createBucketHelper(bucketId: String, type: String, client: String) {}

        @Implementation
        fun getEventsJSON(bucketId: String, limit: Int): JSONArray {
            val newest = buckets[bucketId].orEmpty().sortedByDescending { it.start }
            return JSONArray((if (limit > 0) newest.take(limit) else newest).mapIndexed { index, it ->
                val event = JSONObject()
                    .put("duration", (it.end - it.start) / 1000.0)
                    .put("data", it.data)
                if (index > 0 || bucketId !in unreadableNewest) {
                    event.put("timestamp", Instant.ofEpochMilli(it.start).toString())
                }
                event
            })
        }

        @Implementation
        fun insertEvent(bucketId: String, timestamp: Instant, duration: Double, data: JSONObject) =
            heartbeatHelper(bucketId, timestamp, duration, data, 0.0)

        @Implementation
        fun heartbeatHelper(bucketId: String, timestamp: Instant, duration: Double, data: JSONObject, pulsetime: Double) {
            val events = buckets.getOrPut(bucketId) { mutableListOf() }
            val start = timestamp.toEpochMilli()
            val end = start + Math.round(duration * 1000)
            val last = events.maxByOrNull { it.start }
            if (last != null && sameData(last.data, data) &&
                start >= last.start && start <= last.end + Math.round(pulsetime * 1000)
            ) {
                events[events.indexOf(last)] = Stored(last.start, maxOf(last.end, end), last.data)
            } else {
                events.add(Stored(start, end, data))
            }
        }
    }
}
