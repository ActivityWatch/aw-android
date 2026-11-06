package net.activitywatch.android.watcher

import android.app.Application
import net.activitywatch.android.RustInterface

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.threeten.bp.Duration
import org.threeten.bp.Instant
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class,
    shadows = [LegacyPauseImportTest.Datastore::class],
    instrumentedPackages = ["net.activitywatch.android"])
@LooperMode(LooperMode.Mode.PAUSED)
class WebPauseHeartbeatTest {
    @Test
    fun matchingVisitsCannotMergeAcrossAShortPauseIncludingAfterRestart() {
        checkVisits(paused = true)
    }

    @Test
    fun matchingVisitsStillMergeWithoutAPause() {
        checkVisits(paused = false)
    }

    @Test
    fun pauseEndingNearTheNextVisitBlocksMergeEvenWhenItBeganEarlier() {
        checkVisits(paused = true, pauseStart = 10_000, nextStart = 11_100)
    }

    @Test
    fun delayedBrowserVisitKeepsItsPauseBoundaryAfterUsageCursorsAdvance() {
        checkVisits(paused = true, advanceUsageCursors = true)
    }

    private fun checkVisits(paused: Boolean, pauseStart: Long = 10_100, nextStart: Long = 10_600,
                            advanceUsageCursors: Boolean = false) {
        val context = RuntimeEnvironment.getApplication()
        LegacyPauseImportTest.Datastore.events.clear()
        android.os.SystemClock.setCurrentTimeMillis(20_000)
        fun visit(start: Long, duration: Long) {
            // New service for each completed visit models restart: the boundary must come
            // from durable history, not just a transient listener flag.
            val service = Robolectric.buildService(WebWatcher::class.java).get()
            fun field(name: String) = service.javaClass.getDeclaredField(name).apply { isAccessible = true }
            field("ri").set(service, RustInterface(context))
            val writer = field("writer").get(service) as ThreadPoolExecutor
            try {
                val method = service.javaClass.getDeclaredMethod("logBrowserEvent", CompletedBrowserSession::class.java)
                    .apply { isAccessible = true }
                method.invoke(service, CompletedBrowserSession("same.example", "org.mozilla.firefox", "Same",
                    Instant.ofEpochMilli(start), Duration.ofMillis(duration)))
                writer.submit {}.get(10, TimeUnit.SECONDS)
            } finally {
                writer.shutdownNow()
                (field("detectionExecutor").get(service) as java.util.concurrent.ExecutorService).shutdownNow()
            }
        }
        visit(9_000, 1_000)
        if (paused) {
            val store = PauseIntervalStore(context)
            assertTrue(store.recordPause(pauseStart))
            assertTrue(store.recordResume(nextStart - 100))
        }
        if (advanceUsageCursors) {
            val later = 3 * 24 * 60 * 60 * 1_000L
            android.os.SystemClock.setCurrentTimeMillis(later + 1_000L)
            for (bucket in listOf(SESSION_BUCKET_ID, UNLOCK_BUCKET_ID)) {
                LegacyPauseImportTest.Datastore.events[bucket] = mutableListOf(
                    org.json.JSONObject().put("timestamp", Instant.ofEpochMilli(later).toString())
                        .put("duration", 0.0).put("data", org.json.JSONObject())
                )
            }
            val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                worker.submit<Int> { SessionEventWatcher(context).processEventsSinceLastUpdate() }
                    .get(30, TimeUnit.SECONDS)
            } finally {
                worker.shutdownNow()
            }
        }
        // Completion can lag usage/unlock imports by days; its start still needs the boundary.
        visit(nextStart, if (advanceUsageCursors) 3 * 24 * 60 * 60 * 1_000L else 1_000L)
        val events = LegacyPauseImportTest.Datastore.events["aw-watcher-android-web"].orEmpty()
        assertEquals(if (paused) 2 else 1, events.size)
        assertEquals(if (paused) 1.0 else 2.6, events.first().getDouble("duration"), 0.0001)
    }
}
