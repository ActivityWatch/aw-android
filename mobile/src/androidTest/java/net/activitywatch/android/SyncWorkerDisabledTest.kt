package net.activitywatch.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import net.activitywatch.android.workers.SyncWorker
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncWorkerDisabledTest {
    @Test fun queuedAttemptSkipsSyncAfterDisable() = checkDisabledAttempt(0)

    @Test fun pendingRetrySkipsSyncAfterDisable() = checkDisabledAttempt(1)

    private fun checkDisabledAttempt(attempt: Int) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = AWPreferences(context)
        val wasEnabled = prefs.isSyncEnabled()
        val lastHandlerPass = prefs.getHandlerPassAt()
        val lastStatus = prefs.getLastSyncStatus()
        try {
            prefs.setSyncEnabled(false)
            // Avoid masking the missing opt-in check with the recent-handler guard.
            prefs.setHandlerPassAt(0)
            val worker = TestListenableWorkerBuilder<SyncWorker>(context)
                .setRunAttemptCount(attempt)
                .build()
            assertEquals(ListenableWorker.Result.success(), runBlocking { worker.doWork() })
            assertEquals("Disabled attempts must not perform a sync", lastStatus, prefs.getLastSyncStatus())
        } finally {
            prefs.setSyncEnabled(wasEnabled)
            prefs.setHandlerPassAt(lastHandlerPass ?: 0)
        }
    }
}
