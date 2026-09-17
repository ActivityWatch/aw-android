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

data class SyncStatus(
    val completedAt: Long,
    val success: Boolean,
    // JNI already returns {"success": false, "error": "..."}; keep a bounded
    // copy so the settings line can say why, not just that it failed.
    val error: String? = null,
    // Per-run facts from aw-sync's SyncReport. A boolean cannot tell "moved a
    // million events" from "did nothing"; these can. Every field defaults to
    // the pre-SyncReport payload (activitywatch/aw-server-rust#699), so an
    // older bundled native lib still parses.
    val summary: String? = null,
    // True only when the payload actually carried a SyncReport. The counts default
    // to 0, which is indistinguishable from a real no-op pass, so the renderer
    // keys off this instead of the values.
    val hasReport: Boolean = false,
    val eventsPulled: Int = 0,
    val eventsPushed: Int = 0,
    val peersImported: Int = 0,
    val peersSkipped: Int = 0,
    val peersFailed: Int = 0,
    val warnings: List<String> = emptyList(),
) {
    companion object {
        const val MAX_ERROR_CHARS = 500
        const val MAX_WARNINGS = 5
        private val WHITESPACE = Regex("\\s+")

        fun normalizeError(raw: String?): String? =
            raw?.trim()
                ?.replace(WHITESPACE, " ")
                ?.take(MAX_ERROR_CHARS)
                ?.ifBlank { null }

        /**
         * Parse a JNI sync response into a status.
         *
         * Never throws: an unreadable response becomes a failure carrying the
         * raw text, rather than propagating an exception out of the sync path.
         */
        fun fromJniResponse(response: String, completedAt: Long): SyncStatus {
            val json = try {
                JSONObject(response)
            } catch (e: Exception) {
                return SyncStatus(
                    completedAt = completedAt,
                    success = false,
                    error = normalizeError("Unreadable sync response: ${e.message}"),
                )
            }

            val success = json.optBoolean("success", false)
            val warnings = json.optJSONArray("warnings")?.let { arr ->
                (0 until minOf(arr.length(), MAX_WARNINGS))
                    .mapNotNull { i -> normalizeError(arr.optString(i, "")) }
            } ?: emptyList()

            return SyncStatus(
                completedAt = completedAt,
                success = success,
                error = if (success) {
                    null
                } else {
                    normalizeError(json.optString("error", "")) ?: "sync failed"
                },
                summary = normalizeError(json.optString("message", "")),
                hasReport = json.has("events_pulled"),
                eventsPulled = json.optInt("events_pulled", 0).coerceAtLeast(0),
                eventsPushed = json.optInt("events_pushed", 0).coerceAtLeast(0),
                peersImported = json.optInt("peers_imported", 0).coerceAtLeast(0),
                peersSkipped = json.optInt("peers_skipped", 0).coerceAtLeast(0),
                peersFailed = json.optInt("peers_failed", 0).coerceAtLeast(0),
                warnings = warnings,
            )
        }
    }
}

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
        // libaw_sync.so has its own ANDROID_DATA_DIR static; RustInterface.setDataDir
        // only updates libaw_server.so. Point this copy at filesDir so sync reads
        // the same config.toml as the embedded server (ActivityWatch/aw-server-rust#666).
        setDataDir(filesDir)
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
    private external fun setDataDir(path: String)
    private external fun syncPullAll(port: Int, hostname: String): String
    private external fun syncPull(port: Int, hostname: String): String
    private external fun syncPush(port: Int, hostname: String): String
    private external fun syncBoth(port: Int, hostname: String): String
    external fun getSyncDir(): String
    
    private fun getDeviceName(): String = deviceHostname(appContext)
    
    // Async wrapper for syncPullAll
    fun syncPullAllAsync(callback: (Boolean, String) -> Unit) {
        val hostname = getDeviceName()
        performSyncAsync("Pull All", callback) {
            syncPullAll(BuildConfig.SERVER_PORT, hostname)
        }
    }
    
    // Async wrapper for syncPush
    fun syncPushAsync(callback: (Boolean, String) -> Unit) {
        val hostname = getDeviceName()
        performSyncAsync("Push", callback) {
            syncPush(BuildConfig.SERVER_PORT, hostname)
        }
    }
    
    // Async wrapper for syncBoth
    fun syncBothAsync(callback: (Boolean, String) -> Unit) {
        syncBothAsync(mirrorBeforeCallback = true, callback)
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
                // Status persistence now happens in performSyncAsync, which has the
                // parsed SyncReport; building it here again would discard the counts.
                callback(success, message)
            },
            mirrorBeforeCallback
        ) {
            syncBoth(BuildConfig.SERVER_PORT, hostname)
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
            // Native-sync report kept for the catch path: when mirroring fails after
            // a successful sync, the failure status must still carry the report
            // (counts/warnings) instead of erasing what the pass actually did.
            var nativeStatus: SyncStatus? = null
            try {
                val response = syncFn()
                val status = SyncStatus.fromJniResponse(response, System.currentTimeMillis())
                nativeStatus = status
                val success = status.success
                val message = if (success) {
                    status.summary ?: "sync completed"
                } else {
                    status.error ?: "sync failed"
                }

                // Single choke point for status persistence: every sync operation runs
                // through here, and this is the only place that holds the SyncReport
                // returned across the JNI boundary. Persisting per-caller (as the full-sync
                // path used to) is what left pull/push runs unrecorded.
                AWPreferences(appContext).setLastSyncStatus(status)

                // Keep completion feedback honest: a configured SAF directory is part of a
                // successful Android sync, so mirror failures must reach the user instead of
                // being logged as a non-fatal success. Full-sync callers wait for mirroring.
                Log.i(TAG, "$operation completed: success=$success, message=$message")
                if (success && mirrorBeforeCallback) {
                    mirrorSyncFilesToSafDir()
                }
                handler.post { callback(success, message) }
            } catch (e: Exception) {
                val native = nativeStatus
                val status = if (native != null && native.success) {
                    // The native sync already completed (and was persisted); this
                    // failure came from the post-sync step, so keep its report.
                    val step = if (mirrorBeforeCallback) "SAF mirroring failed" else "post-sync step failed"
                    native.copy(
                        completedAt = System.currentTimeMillis(),
                        success = false,
                        error = SyncStatus.normalizeError("$step: ${e.message}"),
                    )
                } else {
                    SyncStatus(
                        completedAt = System.currentTimeMillis(),
                        success = false,
                        error = SyncStatus.normalizeError("Exception: ${e.message}"),
                    )
                }
                AWPreferences(appContext).setLastSyncStatus(status)
                handler.post {
                    Log.e(TAG, "$operation failed", e)
                    callback(false, status.error ?: "sync failed")
                }
            } finally {
                executor.shutdown()
            }
        }
    }

    private fun mirrorSyncFilesToSafDir() {
        copySyncFilesToSafDir()
    }

    /**
     * After each successful sync, mirror the contents of the internal sync directory to the
     * user-chosen SAF directory (if one has been configured via SyncSettingsActivity).
     *
     * aw-sync writes to the app-private [syncDir] which is invisible to Syncthing and other
     * file-sync tools on Android 11+. This method mirrors [syncDir] to the SAF-granted tree
     * URI so that external sync tools can reach the data.
     *
     * The mirror is recursive and structure-preserving, which is required for the copy to
     * contain anything at all and for the result to be usable. aw-sync never writes a regular
     * file at the root of the sync directory: `setup_local_remote` in aw-server-rust
     * (`aw-sync/src/sync.rs`) does `path.join(device_id)` and writes `test.db` inside it, and
     * on Android the observed tree is one level deeper still,
     * `<syncDir>/<hostname>/<device_id>/test.db`. The consuming side requires the same nesting:
     * `find_remotes` (`aw-sync/src/util.rs`) keeps only directories and looks for `*.db` one
     * level inside them, so a flattened copy would be ignored even if it were made.
     *
     * Errors propagate to the caller so a configured directory is never reported as successfully
     * synced when the files could not be mirrored there.
     */
    private fun copySyncFilesToSafDir() {
        val uriStr = AWPreferences(appContext).getSyncDirUri() ?: return
        val safUri = Uri.parse(uriStr)
        val safDir = DocumentFile.fromTreeUri(appContext, safUri)
        if (safDir == null || !safDir.isDirectory) {
            throw IOException("Configured SAF directory is not accessible")
        }

        val counts = intArrayOf(0, 0) // [copied, skipped]
        mirrorDirectory(File(syncDir), safDir, counts)
        Log.i(TAG, "SAF mirror: copied=${counts[0]} skipped=${counts[1]} → $uriStr")
        if (cancelRequested) {
            throw IOException("SAF mirror cancelled")
        }
        if (counts[1] > 0) {
            throw IOException("SAF mirror skipped ${counts[1]} item(s)")
        }
    }

    /**
     * Recursively mirror [sourceDir] into [destDir], creating subdirectories as needed so the
     * `<device_id>/` layout aw-sync produces is reproduced verbatim in the SAF tree.
     */
    private fun mirrorDirectory(sourceDir: File, destDir: DocumentFile, counts: IntArray) {
        val entries = sourceDir.listFiles() ?: return

        for (entry in entries) {
            if (cancelRequested) {
                Log.i(TAG, "SAF mirror cancelled; stopping before ${entry.name}")
                return
            }
            try {
                if (entry.isDirectory) {
                    // Reuse an existing subdirectory if present; otherwise create it. A
                    // non-directory of the same name cannot be mirrored into.
                    val existing = destDir.findFile(entry.name)
                    val subDir = when {
                        existing != null && existing.isDirectory -> existing
                        existing != null -> {
                            Log.w(TAG, "SAF entry ${entry.name} exists but is not a directory")
                            counts[1]++
                            continue
                        }
                        else -> destDir.createDirectory(entry.name)
                    }
                    if (subDir == null) {
                        Log.w(TAG, "Could not create SAF directory for ${entry.name}")
                        counts[1]++
                        continue
                    }
                    mirrorDirectory(entry, subDir, counts)
                } else {
                    // Reuse an existing file if present; otherwise create a new one. A
                    // same-named DIRECTORY must be rejected rather than written into: an
                    // already-populated tree can contain one, and openOutputStream() on a
                    // directory URI fails, which would silently leave the database
                    // uncopied. The directory branch above rejects the mirror case, so
                    // this keeps the two symmetric.
                    val existingFile = destDir.findFile(entry.name)
                    if (existingFile != null && existingFile.isDirectory) {
                        Log.w(TAG, "SAF entry ${entry.name} is a directory; cannot write a file there")
                        counts[1]++
                        continue
                    }
                    val dest = existingFile
                        ?: destDir.createFile("application/octet-stream", entry.name)
                    if (dest == null) {
                        Log.w(TAG, "Could not create SAF file for ${entry.name}")
                        counts[1]++
                        continue
                    }
                    val out = appContext.contentResolver.openOutputStream(dest.uri, "wt")
                    if (out == null) {
                        Log.w(TAG, "Null output stream for ${entry.name} in SAF dir")
                        counts[1]++
                        continue
                    }
                    out.use { FileInputStream(entry).use { inp -> inp.copyTo(it) } }
                    counts[0]++
                }
            } catch (e: IOException) {
                Log.w(TAG, "Failed to copy ${entry.name} to SAF dir: ${e.message}")
                counts[1]++
            } catch (e: SecurityException) {
                Log.w(TAG, "Permission denied copying ${entry.name} to SAF dir: ${e.message}")
                counts[1]++
            }
        }
    }

    fun getSyncDirectory(): String = syncDir
}
