package net.activitywatch.android.watcher

import android.app.AppOpsManager
import android.app.Application
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.jakewharton.threetenabp.AndroidThreeTen
import net.activitywatch.android.OffThreadInit
import net.activitywatch.android.RustInterface
import net.activitywatch.android.parseEventsJSON
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
import org.robolectric.shadows.ShadowUsageStatsManager
import org.threeten.bp.Instant
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28], application = Application::class,
    shadows = [FailedEventReadTest.Datastore::class, FailedEventReadTest.UsageSource::class],
    instrumentedPackages = ["net.activitywatch.android.RustInterface"],
)
class FailedEventReadTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val previous = Instant.parse("2026-11-05T12:00:00Z")

    @Before
    fun setup() {
        AndroidThreeTen.init(context)
        Datastore.failedBucket = null
        Datastore.reads.clear()
        UsageSource.queries = 0
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        shadowOf(appOps).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(),
            context.packageName, AppOpsManager.MODE_ALLOWED)
    }

    private fun injectDatastore(watcher: Any) {
        val init = OffThreadInit("test-datastore", "test", isMainThread = { false }) {
            RustInterface(context)
        }
        watcher.javaClass.getDeclaredField("rust\$delegate").apply { isAccessible = true }
            .set(watcher, lazyOf(init))
    }

    @Test
    fun failedSessionReadAbortsWithoutResettingCursor() {
        val watcher = SessionEventWatcher(context)
        injectDatastore(watcher)
        watcher.lastUpdated = previous
        Datastore.failedBucket = SESSION_BUCKET_ID

        assertEquals(0, watcher.processEventsSinceLastUpdate())
        assertEquals(previous, watcher.lastUpdated)
        assertEquals(listOf(SESSION_BUCKET_ID), Datastore.reads)
        assertEquals(0, UsageSource.queries)
    }

    @Test
    fun failedUnlockReadAbortsWithoutResettingSessionCursor() {
        val watcher = SessionEventWatcher(context)
        injectDatastore(watcher)
        watcher.lastUpdated = previous
        Datastore.failedBucket = UNLOCK_BUCKET_ID

        assertEquals(0, watcher.processEventsSinceLastUpdate())
        assertEquals(previous, watcher.lastUpdated)
        assertEquals(listOf(SESSION_BUCKET_ID, UNLOCK_BUCKET_ID), Datastore.reads)
        assertEquals(0, UsageSource.queries)
    }

    @Test
    fun emptyBucketsStillAllowFirstRun() {
        val watcher = SessionEventWatcher(context)
        injectDatastore(watcher)
        watcher.lastUpdated = previous

        assertEquals(0, watcher.processEventsSinceLastUpdate())
        assertEquals(null, watcher.lastUpdated)
        assertEquals(listOf(SESSION_BUCKET_ID, UNLOCK_BUCKET_ID), Datastore.reads)
        // Session and unlock history replay independently so a partial import cannot
        // advance one bucket's cursor past records still pending in the other.
        assertEquals(2, UsageSource.queries)
    }

    private fun runLegacyBatch(watcher: UsageStatsWatcher): Int? {
        val taskClass = Class.forName("net.activitywatch.android.watcher.UsageStatsWatcher\$SendHeartbeatsTask")
        val task = taskClass.getDeclaredConstructor(UsageStatsWatcher::class.java)
            .apply { isAccessible = true }.newInstance(watcher)
        return taskClass.getDeclaredMethod("doInBackground", Array<URL>::class.java)
            .apply { isAccessible = true }.invoke(task, emptyArray<URL>()) as Int?
    }

    @Test
    fun failedLegacyReadAbortsWithoutResettingCursor() {
        val watcher = UsageStatsWatcher(context)
        injectDatastore(watcher)
        watcher.lastUpdated = previous
        Datastore.failedBucket = SESSION_BUCKET_ID

        assertEquals(0, runLegacyBatch(watcher))
        assertEquals(previous, watcher.lastUpdated)
        assertEquals(0, UsageSource.queries)
    }

    @Test
    fun emptyLegacyBucketStillAllowsFirstRun() {
        val watcher = UsageStatsWatcher(context)
        injectDatastore(watcher)
        watcher.lastUpdated = previous

        assertEquals(0, runLegacyBatch(watcher))
        assertEquals(null, watcher.lastUpdated)
        assertEquals(1, UsageSource.queries)
    }

    @Implements(UsageStatsManager::class)
    class UsageSource : ShadowUsageStatsManager() {
        companion object {
            var queries = 0
        }

        @Implementation
        public override fun queryEvents(beginTime: Long, endTime: Long): UsageEvents {
            queries++
            return super.queryEvents(beginTime, endTime)
        }
    }

    // Replace JNI only; exercise the real watcher batches and error-object parser.
    @Implements(RustInterface::class, isInAndroidSdk = false)
    class Datastore {
        companion object {
            var failedBucket: String? = null
            val reads = mutableListOf<String>()
        }

        @Implementation
        fun __constructor__(context: Context?) {}

        @Implementation
        fun createBucketsHelper(buckets: Map<String, String>, client: String) {}

        @Implementation
        fun createBucketHelper(bucketId: String, type: String, client: String) {}

        @Implementation
        fun getEventsJSON(bucketId: String, limit: Int): JSONArray {
            reads.add(bucketId)
            return parseEventsJSON(if (bucketId == failedBucket) {
                """{"error":"read failed"}"""
            } else "[]")
        }
    }
}
