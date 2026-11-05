package net.activitywatch.android.watcher

import android.app.Application
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.SystemClock
import net.activitywatch.android.RustInterface
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowUsageStatsManager.EventBuilder
import org.threeten.bp.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class,
    shadows = [SessionReplayCursorTest.Datastore::class],
    instrumentedPackages = ["net.activitywatch.android"])
@LooperMode(LooperMode.Mode.PAUSED)
class SessionReplayCursorTest {
    private val minute = 60_000L
    private val context get() = RuntimeEnvironment.getApplication()

    // Only the JNI datastore is replaced. Exercise the actual watcher, UsageStats query,
    // foreground parser, persisted pause history and replay clipping on each import.
    @Implements(RustInterface::class)
    class Datastore {
        @Implementation fun __constructor__(context: Context?) {}
        @Implementation fun createBucketsHelper(buckets: Map<String, String>, client: String) {}
        @Implementation fun getEventsJSON(bucket: String, limit: Int): JSONArray =
            JSONArray().apply { events[bucket]?.lastOrNull()?.let { put(JSONObject(it.toString())) } }
        @Implementation fun insertEvent(bucket: String, timestamp: Instant, duration: Double, data: JSONObject) {
            val list = events.getOrPut(bucket) { mutableListOf() }
            val previous = list.lastOrNull()
            // Match heartbeat(pulsetime=0): only the newest matching event can extend.
            if (previous != null && previous.getString("timestamp") == timestamp.toString() &&
                previous.getJSONObject("data").toString() == data.toString()) {
                previous.put("duration", maxOf(previous.getDouble("duration"), duration))
            } else {
                list += JSONObject().put("timestamp", timestamp.toString()).put("duration", duration).put("data", data)
            }
        }
        @Implementation fun heartbeatHelper(bucket: String, timestamp: Instant, duration: Double, data: JSONObject, pulsetime: Double) {
            insertEvent(bucket, timestamp, duration, data)
        }
        companion object {
            val events = mutableMapOf<String, MutableList<JSONObject>>()
        }
    }

    private fun event(type: Int, at: Long, pkg: String = "chat") {
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        shadowOf(manager).addEvent(EventBuilder.buildEvent().setEventType(type)
            .setTimeStamp(at * minute).setPackage(pkg).setClass("Main").build())
    }

    private fun importAt(at: Long) {
        SystemClock.setCurrentTimeMillis(at * minute)
        // A new watcher on every run models process restart (no in-memory replay state).
        val worker = Executors.newSingleThreadExecutor()
        try {
            worker.submit<Int> { SessionEventWatcher(context).processEventsSinceLastUpdate() }.get(30, TimeUnit.SECONDS)
        } finally {
            worker.shutdownNow()
        }
    }

    private fun sessions(): List<Pair<Long, Long>> = Datastore.events[SESSION_BUCKET_ID].orEmpty().map {
        val start = Instant.parse(it.getString("timestamp")).toEpochMilli()
        start / minute to (start + (it.getDouble("duration") * 1000).toLong()) / minute
    }

    private fun unlocks(): List<Long> = Datastore.events[UNLOCK_BUCKET_ID].orEmpty().map {
        Instant.parse(it.getString("timestamp")).toEpochMilli() / minute
    }

    @Test
    fun interruptedImportRecoversUnlocksBeforeSessionCursorWithoutReplayingSessions() {
        Datastore.events.clear()
        val store = PauseIntervalStore(context)
        assertTrue(store.recordPause(10 * minute))
        assertTrue(store.recordResume(20 * minute))
        event(UsageEvents.Event.ACTIVITY_RESUMED, 1, "before")
        event(UsageEvents.Event.ACTIVITY_PAUSED, 5, "before")
        event(UsageEvents.Event.ACTIVITY_RESUMED, 21, "after")
        event(UsageEvents.Event.KEYGUARD_HIDDEN, 6)
        event(UsageEvents.Event.KEYGUARD_HIDDEN, 9)
        event(UsageEvents.Event.KEYGUARD_HIDDEN, 15) // private
        event(UsageEvents.Event.KEYGUARD_HIDDEN, 20) // resume boundary is allowed
        importAt(25)
        assertEquals(listOf(1L to 5L, 21L to 25L), sessions())
        // Model a killed import: sessions persisted, but only the first unlock saved.
        Datastore.events[UNLOCK_BUCKET_ID] = mutableListOf(JSONObject()
            .put("timestamp", Instant.ofEpochMilli(6 * minute).toString())
            .put("duration", 0.0).put("data", JSONObject()))
        importAt(30)
        assertEquals(listOf(6L, 9L, 20L), unlocks())
        assertEquals(listOf(1L to 5L, 21L to 30L), sessions())
        importAt(35)
        assertEquals(listOf(6L, 9L, 20L), unlocks())
        assertEquals(listOf(1L to 5L, 21L to 35L), sessions())
    }

    @Test
    fun unlocksImportWithoutAnyAppSessions() {
        Datastore.events.clear()
        val store = PauseIntervalStore(context)
        assertTrue(store.recordPause(10 * minute))
        assertTrue(store.recordResume(20 * minute))
        event(UsageEvents.Event.KEYGUARD_HIDDEN, 9)
        event(UsageEvents.Event.KEYGUARD_HIDDEN, 10)
        event(UsageEvents.Event.KEYGUARD_HIDDEN, 15)
        event(UsageEvents.Event.KEYGUARD_HIDDEN, 20)
        importAt(25)
        importAt(30)
        assertEquals(listOf(9L, 20L), unlocks())
        assertTrue(sessions().isEmpty())
    }

    @Test
    fun firstImportCursorInsidePauseRecoversItsOnlyAllowedWindow() {
        Datastore.events.clear()
        event(UsageEvents.Event.ACTIVITY_RESUMED, 12)
        importAt(15)
        assertEquals(listOf(12L to 15L), sessions())
        val store = PauseIntervalStore(context)
        assertTrue(store.recordPause(10 * minute))
        assertTrue(store.recordResume(20 * minute))
        // The persisted cursor (12) is inside the pause. There is no pre-gap window,
        // even on this first import with the pause history present.
        importAt(25)
        assertEquals(listOf(12L to 15L, 20L to 25L), sessions())
        importAt(30)
        assertEquals(listOf(12L to 15L, 20L to 30L), sessions())
        // Existing data is not deleted; no NEW event may include the private range.
    }

    @Test
    fun foregroundResumedBeforePauseExtendsOnlyItsPostPauseSegment() {
        Datastore.events.clear()
        val store = PauseIntervalStore(context)
        assertTrue(store.recordPause(10 * minute))
        assertTrue(store.recordResume(20 * minute))
        event(UsageEvents.Event.ACTIVITY_RESUMED, 5)
        importAt(25)
        assertEquals(listOf(5L to 10L, 20L to 25L), sessions())
        importAt(30)
        assertEquals(listOf(5L to 10L, 20L to 30L), sessions())
        event(UsageEvents.Event.ACTIVITY_PAUSED, 35)
        importAt(40)
        importAt(45)
        assertEquals(listOf(5L to 10L, 20L to 35L), sessions())
    }

    @Test
    fun clippedSessionReplayPast24HoursExtendsToOriginalSessionCap() {
        Datastore.events.clear()
        val store = PauseIntervalStore(context)
        assertTrue(store.recordPause(60 * minute))
        assertTrue(store.recordResume(120 * minute))
        event(UsageEvents.Event.ACTIVITY_RESUMED, 0)
        importAt(23 * 60L)
        assertEquals(listOf(0L to 60L, 120L to 23 * 60L), sessions())

        importAt(25 * 60L)
        val cappedEnd = 24 * 60 * minute - 1
        fun assertCapped() {
            val events = Datastore.events[SESSION_BUCKET_ID].orEmpty()
            assertEquals("Replay must extend the clipped event, not duplicate it", 2, events.size)
            val clipped = events.last()
            assertEquals(120 * minute, Instant.parse(clipped.getString("timestamp")).toEpochMilli())
            assertEquals("Cap is relative to the original RESUMED, not the clipped start",
                (cappedEnd - 120 * minute) / 1000.0, clipped.getDouble("duration"), 0.000001)
        }
        assertCapped()
        importAt(26 * 60L)
        assertCapped()
        event(UsageEvents.Event.ACTIVITY_PAUSED, 27 * 60L)
        importAt(28 * 60L)
        assertCapped()
    }

    @Test
    fun multiplePausesDoNotLeakPrivateSessionsOrReplayEarlierSegments() {
        Datastore.events.clear()
        val store = PauseIntervalStore(context)
        assertTrue(store.recordPause(10 * minute))
        assertTrue(store.recordResume(20 * minute))
        event(UsageEvents.Event.ACTIVITY_RESUMED, 5)
        importAt(25)
        assertTrue(store.recordPause(28 * minute))
        assertTrue(store.recordResume(38 * minute))
        event(UsageEvents.Event.ACTIVITY_PAUSED, 29)
        event(UsageEvents.Event.ACTIVITY_RESUMED, 30, "private")
        event(UsageEvents.Event.ACTIVITY_PAUSED, 32, "private")
        event(UsageEvents.Event.ACTIVITY_RESUMED, 35, "after")
        importAt(40)
        assertEquals(listOf(5L to 10L, 20L to 28L, 38L to 40L), sessions())
        importAt(45)
        assertEquals(listOf(5L to 10L, 20L to 28L, 38L to 45L), sessions())
        event(UsageEvents.Event.ACTIVITY_PAUSED, 50, "after")
        importAt(55)
        importAt(60)
        assertEquals(listOf(5L to 10L, 20L to 28L, 38L to 50L), sessions())
    }

    @Test
    fun unconditionalClippingPreservesParserMinimumDuration() {
        Datastore.events.clear()
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        for ((type, timestamp) in listOf(
            UsageEvents.Event.ACTIVITY_RESUMED to 60_000L,
            UsageEvents.Event.ACTIVITY_PAUSED to 61_000L,
            UsageEvents.Event.ACTIVITY_RESUMED to 120_000L,
            UsageEvents.Event.ACTIVITY_PAUSED to 121_001L,
        )) {
            shadowOf(manager).addEvent(EventBuilder.buildEvent().setEventType(type)
                .setTimeStamp(timestamp).setPackage("chat").setClass("Main").build())
        }
        importAt(5)
        val events = Datastore.events[SESSION_BUCKET_ID].orEmpty()
        assertEquals(1, events.size)
        assertEquals(1.001, events.single().getDouble("duration"), 0.000001)
    }

    @Test
    fun clippedOpenSessionExtendsAfterRestartWithoutReplayingEarlierEvents() {
        Datastore.events.clear()
        val store = PauseIntervalStore(context)
        assertTrue(store.recordPause(10 * minute))
        assertTrue(store.recordResume(20 * minute))
        event(UsageEvents.Event.ACTIVITY_RESUMED, 1, "before")
        event(UsageEvents.Event.ACTIVITY_PAUSED, 5, "before")
        event(UsageEvents.Event.ACTIVITY_RESUMED, 15)

        importAt(25)
        assertEquals(listOf(1L to 5L, 20L to 25L), sessions())
        importAt(30)
        assertEquals("Clipped cursor must retain the RESUMED at 15 across restart", listOf(1L to 5L, 20L to 30L), sessions())

        event(UsageEvents.Event.ACTIVITY_PAUSED, 35)
        importAt(40)
        assertEquals(listOf(1L to 5L, 20L to 35L), sessions())
        importAt(45)
        assertEquals("Repeated closed replay must not duplicate earlier events", listOf(1L to 5L, 20L to 35L), sessions())
    }
}
