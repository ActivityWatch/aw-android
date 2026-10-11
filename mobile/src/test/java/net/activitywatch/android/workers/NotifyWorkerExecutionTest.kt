package net.activitywatch.android.workers

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestWorkerBuilder
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class NotifyWorkerExecutionTest {
    private class FakeBackend(private val setting: String) : NotifyBackend {
        val calls = mutableListOf<String>()

        override fun checkServer() {
            calls += "server"
        }

        override fun getSetting(key: String): String {
            calls += key
            return if (key == "aw-notify") setting else "4"
        }

        override fun androidQuery(timeperiod: String): String {
            calls += "query"
            return """[{"cat_events":[{"duration":120,"data":{"${'$'}category":["Work"]}}]}]"""
        }
    }

    private fun runWorker(backend: FakeBackend): ListenableWorker.Result {
        val context = RuntimeEnvironment.getApplication()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val worker = TestWorkerBuilder.from(context, NotifyWorker::class.java, executor)
                .setWorkerFactory(object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker = NotifyWorker(appContext, workerParameters, backend)
                })
                .build()
            return worker.doWork()
        } finally {
            executor.shutdownNow()
        }
    }

    private fun notificationCount(): Int {
        val manager = RuntimeEnvironment.getApplication()
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return shadowOf(manager).allNotifications.size
    }

    @Test
    fun disabledReturnsSuccessWithoutActivityQueryOrNotifications() {
        val backend = FakeBackend(
            """{"enabled":false,"alerts":[{"category":"Work","thresholds_minutes":[1]}]}"""
        )
        assertEquals(ListenableWorker.Result.success(), runWorker(backend))
        assertEquals(listOf("server", "aw-notify"), backend.calls)
        assertEquals(0, notificationCount())
    }

    @Test
    fun malformedReturnsSuccessWithoutActivityQueryOrNotifications() {
        for (setting in listOf(
            "not-json",
            """{"enabled":"true","alerts":[{"category":"Work","thresholds_minutes":[1]}]}""",
            """{"enabled":true,"alerts":null}""",
        )) {
            val backend = FakeBackend(setting)
            assertEquals(setting, ListenableWorker.Result.success(), runWorker(backend))
            assertEquals(setting, listOf("server", "aw-notify"), backend.calls)
            assertEquals(setting, 0, notificationCount())
        }
    }

    @Test
    fun enabledQueriesActivityAndPostsNotification() {
        val backend = FakeBackend(
            """{"enabled":true,"alerts":[{"category":"Work","thresholds_minutes":[1]}]}"""
        )
        assertEquals(ListenableWorker.Result.success(), runWorker(backend))
        assertEquals(listOf("server", "aw-notify", "startOfDay", "query"), backend.calls)
        assertEquals(1, notificationCount())
    }
}
