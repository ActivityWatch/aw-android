package net.activitywatch.android

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.CountDownLatch
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
    private val ready = CountDownLatch(1)
    @Volatile private var captureFailure: Exception? = null

    fun start(context: Context) {
        val appContext = context.applicationContext
        thread(name = "hostname-rewrite") {
            try {
                val prefs = AWPreferences(appContext)
                try {
                    captureExistingData(appContext, prefs)
                } catch (e: Exception) {
                    // Do not open/create a DB if the write-once signal isn't durable.
                    // Wake waiters with an error rather than leaving them blocked forever.
                    captureFailure = e
                    Log.e(TAG, "Could not persist the notify restore signal", e)
                    return@thread
                }
                val current = deviceHostname(appContext)
                if (prefs.sanitizedHostnameMigratedTo() != current) {
                    rewriteBucketHostnames(appContext, prefs, current)
                }
            } catch (e: Exception) {
                // The preference stays unset, so the next process start retries.
                Log.e(TAG, "Bucket hostname rewrite failed", e)
            } finally {
                ready.countDown()
            }
        }
    }

    /**
     * Blocks until the rewrite has finished. There is deliberately no timeout: giving up
     * would let the datastore open while Android SQLite still has sqlite.db, which is the
     * crash this exists to prevent. The rewrite is one small UPDATE that only runs after a
     * hostname change, and RustInterface should not be constructed on the main thread.
     * Throws if the notify restore signal could not be saved: opening a new datastore in
     * that case would corrupt the signal on the next process start.
     */
    fun awaitReady() {
        if (ready.count > 0 && OffThreadInit.isAndroidMainThread()) {
            Log.w(TAG, "Main thread waiting for the bucket hostname rewrite")
        }
        ready.await()
        captureFailure?.let {
            throw IllegalStateException("Datastore startup could not persist the notify restore signal", it)
        }
    }

    /**
     * Records once whether this install started with a datastore it did not create.
     *
     * Auto Backup restores files before the app's first launch, so a restored datastore is
     * already on disk here, while a fresh install has none until the server creates it. The
     * aw-notify opt-in migration uses this to tell a restore onto a new phone (install times
     * look fresh) from a real fresh install (ActivityWatch/aw-android#405). It runs before
     * anything opens the datastore and is never re-derived, so a retried migration cannot
     * mistake the data this install wrote for restored data.
     */
    internal fun captureExistingData(context: Context, prefs: AWPreferences) {
        if (prefs.hasMigratedNotifyOptIn() || prefs.notifyOptInExistingData() != null) return
        check(prefs.setNotifyOptInExistingData(existingDataSignal(File(context.filesDir, "sqlite.db")))) {
            "Failed to save notifyOptInExistingData"
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

/**
 * Whether the datastore at [dbFile] holds data: the database or its WAL is a non-empty file.
 * An empty or missing file is what a fresh install has before the server first writes.
 */
internal fun existingDataSignal(dbFile: File): Boolean =
    dbFile.isNonEmptyFile() || File(dbFile.path + "-wal").isNonEmptyFile()

private fun File.isNonEmptyFile(): Boolean = isFile && length() > 0
