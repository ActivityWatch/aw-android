package net.activitywatch.android.watcher

import android.app.Application
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import net.activitywatch.android.RustInterface
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
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
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class,
    shadows = [LegacyPauseImportTest.Datastore::class],
    instrumentedPackages = ["net.activitywatch.android"])
@LooperMode(LooperMode.Mode.PAUSED)
class LegacyPauseImportTest {
    private val minute = 60_000L
    private val context get() = RuntimeEnvironment.getApplication()

    // Replace only JNI. Model the Rust heartbeat's newest-event/data/time merge rules;
    // this is a watcher regression, not proof against the native datastore itself.
    @Implements(RustInterface::class)
    class Datastore {
        @Implementation fun __constructor__(context: Context?) {}
        @Implementation fun createBucketsHelper(buckets: Map<String, String>, client: String) {}
        @Implementation fun getEventsJSON(bucket: String, limit: Int): JSONArray =
            JSONArray().apply { events[bucket]?.lastOrNull()?.let { put(JSONObject(it.toString())) } }
        @Implementation fun heartbeatHelper(bucket: String, timestamp: Instant, duration: Double, data: JSONObject, pulsetime: Double) {
            val list = events.getOrPut(bucket) { mutableListOf() }
            val previous = list.lastOrNull()
            val at = timestamp.toEpochMilli()
            val start = previous?.getString("timestamp")?.let { Instant.parse(it).toEpochMilli() }
            val end = start?.plus((previous.getDouble("duration") * 1000).toLong())
            if (previous != null && start != null && end != null &&
                previous.getJSONObject("data").toString() == data.toString() &&
                at >= start && at <= end + (pulsetime * 1000).toLong()) {
                previous.put("duration", (maxOf(end, at + (duration * 1000).toLong()) - start) / 1000.0)
            } else {
                list += JSONObject().put("timestamp", timestamp.toString()).put("duration", duration).put("data", data)
            }
        }
        companion object {
            val events = mutableMapOf<String, MutableList<JSONObject>>()
        }
    }

    @Before
    fun setup() {
        Datastore.events.clear()
        android.os.SystemClock.setCurrentTimeMillis(60 * minute)
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        shadowOf(ops).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName, AppOpsManager.MODE_ALLOWED)
    }

    private fun event(type: Int, at: Long) {
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        shadowOf(manager).addEvent(EventBuilder.buildEvent().setEventType(type)
            .setTimeStamp(at * minute).setPackage(context.packageName).setClass("Main").build())
    }

    private fun importLegacy() {
        // Invoke the real legacy AsyncTask body off-main, synchronously, without adding a
        // production API solely for tests. A new watcher per run models process restart.
        val worker = Executors.newSingleThreadExecutor()
        try {
            worker.submit<Int> {
                val type = Class.forName("net.activitywatch.android.watcher.UsageStatsWatcher\$SendHeartbeatsTask")
                val constructor = type.getDeclaredConstructor(UsageStatsWatcher::class.java).apply { isAccessible = true }
                val task = constructor.newInstance(UsageStatsWatcher(context))
                val method = type.getDeclaredMethod("doInBackground", Array<URL>::class.java).apply { isAccessible = true }
                method.invoke(task, emptyArray<URL>()) as Int
            }.get(30, TimeUnit.SECONDS)
        } finally {
            worker.shutdownNow()
        }
    }

    private fun ranges(): List<Pair<Long, Long>> = Datastore.events[SESSION_BUCKET_ID].orEmpty().map {
        val start = Instant.parse(it.getString("timestamp")).toEpochMilli()
        start / minute to (start + (it.getDouble("duration") * 1000).toLong()) / minute
    }

    @Test
    fun quietPauseCannotBeBridgedByMatchingPausedEventOrReplay() {
        val store = PauseIntervalStore(context)
        assertTrue(store.recordPause(10 * minute))
        assertTrue(store.recordResume(20 * minute))
        event(UsageEvents.Event.ACTIVITY_RESUMED, 5)
        event(UsageEvents.Event.ACTIVITY_PAUSED, 25)
        importLegacy()
        assertEquals(listOf(5L to 5L, 25L to 25L), ranges())
        importLegacy()
        assertEquals("Restart/replay must not extend or duplicate either event", listOf(5L to 5L, 25L to 25L), ranges())
    }

    @Test
    fun pauseAfterAnEarlierImportDoesNotExtendTheStoredEventAcrossPrivateTime() {
        event(UsageEvents.Event.ACTIVITY_RESUMED, 5)
        event(UsageEvents.Event.ACTIVITY_PAUSED, 9)
        importLegacy()
        assertEquals(listOf(5L to 9L), ranges())
        val store = PauseIntervalStore(context)
        assertTrue(store.recordPause(10 * minute))
        assertTrue(store.recordResume(20 * minute))
        event(UsageEvents.Event.ACTIVITY_PAUSED, 25)
        importLegacy()
        assertEquals(listOf(5L to 9L, 25L to 25L), ranges())
        importLegacy()
        assertEquals(listOf(5L to 9L, 25L to 25L), ranges())
    }

    @Test
    fun importDoesNotReadBeyondThePauseSnapshotTime() {
        android.os.SystemClock.setCurrentTimeMillis(70 * minute)
        event(UsageEvents.Event.ACTIVITY_RESUMED, 5)
        // Models an event returned after the snapshot while a user can start a new pause.
        event(UsageEvents.Event.ACTIVITY_PAUSED, 75)
        importLegacy()
        assertEquals(listOf(5L to 5L), ranges())
    }

    @Test
    fun ordinaryLegacyPairStillMergesWithoutAPause() {
        event(UsageEvents.Event.ACTIVITY_RESUMED, 5)
        event(UsageEvents.Event.ACTIVITY_PAUSED, 25)
        importLegacy()
        assertEquals(listOf(5L to 25L), ranges())
    }
}
