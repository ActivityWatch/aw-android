package net.activitywatch.android

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val TAG = "DatastoreStartup"

/**
 * Orders the bucket-hostname rewrite before any use of the Rust datastore.
 *
 * The rewrite edits sqlite.db through Android's SQLite
 * ([SanitizedHostnameMigration.rewriteBucketHostnamesInDatabase]), while the Rust datastore
 * bundles its own SQLite. Two SQLite libraries must never have the same WAL database open at
 * once: each memory-maps the shared `-shm` file and can resize it under the other. When
 * BackgroundService rewrote hostnames while SessionEventWatcher had already opened the
 * datastore over JNI, the process died with SIGBUS (BUS_ADRERR in `walIndexAppend`).
 *
 * So the rewrite starts from [AWApplication.onCreate], before any component exists, and
 * [RustInterface]'s constructor waits for it ([awaitReady]). Nothing reaches the datastore
 * without a RustInterface instance, so it is never open while the rewrite runs.
 */
internal object DatastoreStartup {
    // Only main-thread callers are bounded, so a stuck rewrite can't ANR them. The rewrite
    // is one small UPDATE on the buckets table, so this is never expected to elapse.
    private const val MAIN_THREAD_WAIT_MS = 5_000L

    private val ready = CountDownLatch(1)

    fun start(context: Context) {
        val appContext = context.applicationContext
        val prefs = AWPreferences(appContext)
        val current = deviceHostname(appContext)
        if (prefs.sanitizedHostnameMigratedTo() == current) {
            ready.countDown()
            return
        }
        thread(name = "hostname-rewrite") {
            try {
                rewriteBucketHostnames(appContext, prefs, current)
            } catch (e: Exception) {
                // The preference stays unset, so the next process start retries.
                Log.e(TAG, "Bucket hostname rewrite failed", e)
            } finally {
                ready.countDown()
            }
        }
    }

    fun awaitReady() {
        if (!OffThreadInit.isAndroidMainThread()) {
            ready.await()
        } else if (!ready.await(MAIN_THREAD_WAIT_MS, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "Hostname rewrite still running after ${MAIN_THREAD_WAIT_MS}ms; not waiting longer")
        }
    }

    private fun rewriteBucketHostnames(context: Context, prefs: AWPreferences, current: String) {
        val dbFile = File(context.filesDir, "sqlite.db")
        if (!dbFile.isFile) {
            prefs.setSanitizedHostnameMigratedTo(current)
            return
        }
        val updated = SanitizedHostnameMigration.rewriteBucketHostnamesInDatabase(
            dbFile, current, legacyDeviceHostnames(context)
        )
        if (updated >= 0) {
            prefs.setSanitizedHostnameMigratedTo(current)
        }
    }
}
