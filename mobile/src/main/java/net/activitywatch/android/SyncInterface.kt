package net.activitywatch.android

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "SyncInterface"

class SyncInterface(context: Context) {

    companion object {
        // Shared across all SyncInterface instances (both Handler chain and AlarmManager path)
        // to prevent concurrent syncBoth() calls from different entry points.
        private val syncInFlight = AtomicBoolean(false)
    }
    private val appContext: Context = context.applicationContext
    private val syncDir: String

    /**
     * Holds the active executor so that [cancel] can interrupt it from any thread.
     * Written once per sync operation (guarded by [syncInFlight]) and read by [cancel].
     */
    @Volatile private var activeExecutor: ExecutorService? = null

    /**
     * Set by [cancel] to stop the SAF copy loop between file iterations without relying solely
     * on thread interruption.  Checked in [copySyncFilesToSafDir] before each file write so that
     * files whose truncate-and-write has not yet started are skipped, limiting the corruption
     * window to at most the file currently being written when cancellation is requested.
     */
    @Volatile private var cancelRequested = false
    
    init {
        syncDir = resolveSyncDirectory(context).absolutePath
        Os.setenv("AW_SYNC_DIR", syncDir, true)
        
        // Set XDG environment variables to app-writable paths
        // This is required for aw-client-rust (used by aw-sync) to create lock files
        val cacheDir = context.cacheDir.absolutePath
        val filesDir = context.filesDir.absolutePath
        
        Os.setenv("XDG_CACHE_HOME", cacheDir, true)
        Os.setenv("XDG_CONFIG_HOME", "$filesDir/config", true)
        Os.setenv("XDG_DATA_HOME", "$filesDir/data", true)
        
        System.loadLibrary("aw_sync")
        Log.i(TAG, "aw-sync initialized with sync dir: $syncDir")
    }

    private fun resolveSyncDirectory(context: Context): File {
        val preferredDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "sync")
        if (preferredDir.exists() || preferredDir.mkdirs()) {
            return preferredDir
        }

        val fallbackDir = File(context.filesDir, "sync")
        if (!fallbackDir.exists() && !fallbackDir.mkdirs()) {
            Log.e(TAG, "Failed to create sync directory: ${fallbackDir.absolutePath}")
        }
        return fallbackDir
    }
    
    // Native JNI functions
    private external fun syncPullAll(port: Int, hostname: String): String
    private external fun syncPull(port: Int, hostname: String): String
    private external fun syncPush(port: Int, hostname: String): String
    private external fun syncBoth(port: Int, hostname: String): String
    external fun getSyncDir(): String
    
    private fun getDeviceName(): String {
        val raw = android.provider.Settings.Global.getString(
            appContext.contentResolver,
            android.provider.Settings.Global.DEVICE_NAME
        )?.trim()?.takeIf { it.isNotEmpty() }
            ?: android.os.Build.DEVICE ?: "unknown"
        return raw.trim()
            .lowercase(java.util.Locale.ROOT)
            .replace(Regex("[^a-z0-9_-]+"), "_")
            .trim('_')
            .ifEmpty { "unknown" }
    }
    
    // Async wrapper for syncPullAll
    fun syncPullAllAsync(callback: (Boolean, String) -> Unit) {
        val hostname = getDeviceName()
        performSyncAsync("Pull All", callback) {
            syncPullAll(5600, hostname)
        }
    }
    
    // Async wrapper for syncPush
    fun syncPushAsync(callback: (Boolean, String) -> Unit) {
        val hostname = getDeviceName()
        performSyncAsync("Push", callback) {
            syncPush(5600, hostname)
        }
    }
    
    // Async wrapper for syncBoth
    fun syncBothAsync(callback: (Boolean, String) -> Unit) {
        syncBothAsync(mirrorBeforeCallback = false, callback)
    }

    // Background workers must remain active until the SAF mirror completes.
    fun syncBothAndMirrorAsync(callback: (Boolean, String) -> Unit) {
        syncBothAsync(mirrorBeforeCallback = true, callback)
    }

    private fun syncBothAsync(
        mirrorBeforeCallback: Boolean,
        callback: (Boolean, String) -> Unit
    ) {
        if (!syncInFlight.compareAndSet(false, true)) {
            Log.i(TAG, "Sync already in flight; skipping concurrent call")
            callback(false, "skipped: sync already in flight")
            return
        }
        val hostname = getDeviceName()
        performSyncAsync(
            "Full Sync",
            { success, message ->
                syncInFlight.set(false)
                callback(success, message)
            },
            mirrorBeforeCallback
        ) {
            syncBoth(5600, hostname)
        }
    }
    
    /**
     * Interrupts any in-progress sync and SAF mirror operation.
     *
     * Called by [SyncWorker] via `invokeOnCancellation` when WorkManager stops or cancels
     * the worker.  Three things happen in order:
     *
     * 1. [cancelRequested] is set so that [copySyncFilesToSafDir]'s copy loop stops before
     *    starting the next file's truncate-and-write, bounding the partial-write window to
     *    at most the file currently being copied.
     * 2. [ExecutorService.shutdownNow] sends an interrupt to the executor thread, causing
     *    any blocking I/O to throw [java.io.InterruptedIOException] promptly.
     * 3. [syncInFlight] is cleared so that a future sync is not permanently blocked.
     *    This is necessary because [shutdownNow] can remove a queued-but-not-started
     *    executor task before its completion callback has a chance to clear the guard.
     */
    fun cancel() {
        cancelRequested = true
        activeExecutor?.shutdownNow()
        syncInFlight.set(false)
    }

    private fun performSyncAsync(
        operation: String,
        callback: (Boolean, String) -> Unit,
        mirrorBeforeCallback: Boolean = false,
        syncFn: () -> String
    ) {
        val executor = Executors.newSingleThreadExecutor()
        activeExecutor = executor
        val handler = Handler(Looper.getMainLooper())

        executor.execute {
            Log.i(TAG, "Starting sync operation: $operation")
            try {
                val response = syncFn()
                val json = JSONObject(response)
                val success = json.getBoolean("success")
                val message = if (success) {
                    json.getString("message")
                } else {
                    json.getString("error")
                }

                // Worker-triggered syncs keep their WorkManager job active until mirroring
                // finishes. Other callers get the native result immediately.
                Log.i(TAG, "$operation completed: success=$success, message=$message")
                if (success && mirrorBeforeCallback) {
                    mirrorSyncFilesToSafDir()
                }
                handler.post { callback(success, message) }
                if (success && !mirrorBeforeCallback) {
                    mirrorSyncFilesToSafDir()
                }
            } catch (e: Exception) {
                val errorMsg = "Exception: ${e.message}"
                handler.post {
                    Log.e(TAG, "$operation failed", e)
                    callback(false, errorMsg)
                }
            } finally {
                executor.shutdown()
            }
        }
    }

    private fun mirrorSyncFilesToSafDir() {
        try {
            copySyncFilesToSafDir()
        } catch (e: Exception) {
            Log.w(TAG, "SAF mirror failed (non-fatal): ${e.message}")
        }
    }

    /**
     * After each successful sync, mirror the contents of the internal sync directory to the
     * user-chosen SAF directory (if one has been configured via SyncSettingsActivity).
     *
     * aw-sync writes to the app-private [syncDir] which is invisible to Syncthing and other
     * file-sync tools on Android 11+. This method copies every regular file from [syncDir]
     * to the SAF-granted tree URI so that external sync tools can reach the data.
     *
     * Errors are logged but do not propagate — a copy failure must never fail the sync itself.
     */
    private fun copySyncFilesToSafDir() {
        val uriStr = AWPreferences(appContext).getSyncDirUri() ?: return
        val safUri = Uri.parse(uriStr)
        val safDir = DocumentFile.fromTreeUri(appContext, safUri)
        if (safDir == null || !safDir.isDirectory) {
            Log.w(TAG, "SAF directory not accessible or not a directory: $uriStr")
            return
        }

        val sourceFiles = File(syncDir).listFiles()?.filter { it.isFile } ?: return
        if (sourceFiles.isEmpty()) return

        var copied = 0
        var skipped = 0
        for (file in sourceFiles) {
            if (cancelRequested) {
                Log.i(TAG, "SAF mirror cancelled; stopping before ${file.name}")
                break
            }
            try {
                // Reuse an existing file if present; otherwise create a new one.
                val dest = safDir.findFile(file.name)
                    ?: safDir.createFile("application/octet-stream", file.name)
                if (dest == null) {
                    Log.w(TAG, "Could not create SAF file for ${file.name}")
                    skipped++
                    continue
                }
                val out = appContext.contentResolver.openOutputStream(dest.uri, "wt")
                if (out == null) {
                    Log.w(TAG, "Null output stream for ${file.name} in SAF dir")
                    skipped++
                    continue
                }
                out.use { FileInputStream(file).use { inp -> inp.copyTo(it) } }
                copied++
            } catch (e: IOException) {
                Log.w(TAG, "Failed to copy ${file.name} to SAF dir: ${e.message}")
                skipped++
            } catch (e: SecurityException) {
                Log.w(TAG, "Permission denied copying ${file.name} to SAF dir: ${e.message}")
                skipped++
            }
        }
        Log.i(TAG, "SAF mirror: copied=$copied skipped=$skipped → $uriStr")
    }

    fun getSyncDirectory(): String = syncDir
}
