package net.activitywatch.android

import android.content.Context
import android.app.ActivityManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import net.activitywatch.android.workers.SyncWorker
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.Socket
import java.util.concurrent.TimeUnit

/** Run in a fresh process; instrumentation does not launch BackgroundService or MainActivity. */
@RunWith(AndroidJUnit4::class)
class ColdStartSyncTest {
    @Test fun workerRecoversLocalServerWithoutOpeningApp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        if (args.getString("configure") == "true") {
            // Instrumentation-only setup, not a production settings/import backdoor.
            context.getSharedPreferences(AWPreferences.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean("isFirstTime", false).putBoolean("syncEnabled", true)
                .putBoolean("hasRequestedNotificationPermission", true).commit()
        }
        val prefs = AWPreferences(context)
        prefs.setHandlerPassAt(0)
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        fun foregroundServerRunning() = activityManager.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == BackgroundService::class.java.name
        }
        assertFalse("Foreground service must not assist cold sync", foregroundServerRunning())
        val wasListening = try { Socket("127.0.0.1", BuildConfig.SERVER_PORT).use { true } }
            catch (_: java.io.IOException) { false }
        assertFalse("Test must start with local server absent", wasListening)
        val work = OneTimeWorkRequestBuilder<SyncWorker>().build()
        val manager = WorkManager.getInstance(context)
        manager.enqueue(work).result.get(10, TimeUnit.SECONDS)
        val deadline = SystemClock.elapsedRealtime() + 90_000
        var state: WorkInfo.State
        do {
            state = manager.getWorkInfoById(work.id).get(5, TimeUnit.SECONDS).state
            if (state.isFinished) break
            SystemClock.sleep(200)
        } while (SystemClock.elapsedRealtime() < deadline)
        assertEquals("Cold sync must finish successfully", WorkInfo.State.SUCCEEDED, state)
        assertTrue(prefs.getLastSyncStatus()?.success == true)
        assertFalse("Worker must recover without a foreground service", foregroundServerRunning())
        val syncDir = File(context.getExternalFilesDir(null), "sync")
        assertTrue("Sync must produce a database", syncDir.walkTopDown().any { it.extension == "db" && it.length() > 0 })
    }
}
