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
        if (!syncInFlight.compareAndSet(false, true)) {
            Log.i(TAG, "Sync already in flight; skipping concurrent call")
            callback(false, "skipped: sync already in flight")
            return
        }
        val hostname = getDeviceName()
        performSyncAsync("Full Sync", { success, message ->
            syncInFlight.set(false)
            callback(success, message)
        }) {
            syncBoth(5600, hostname)
        }
    }
    
    private fun performSyncAsync(
        operation: String,
        callback: (Boolean, String) -> Unit,
        syncFn: () -> String
    ) {
        val executor = Executors.newSingleThreadExecutor()
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

                // Copy sync files to SAF directory on the background thread — IO must not run on main.
                if (success) copySyncFilesToSafDir()

                handler.post {
                    Log.i(TAG, "$operation completed: success=$success, message=$message")
                    callback(success, message)
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
            try {
                // Reuse an existing file if present; otherwise create a new one.
                val dest = safDir.findFile(file.name)
                    ?: safDir.createFile("application/octet-stream", file.name)
                if (dest == null) {
                    Log.w(TAG, "Could not create SAF file for ${file.name}")
                    skipped++
                    continue
                }
                appContext.contentResolver.openOutputStream(dest.uri, "wt")?.use { out ->
                    FileInputStream(file).use { inp -> inp.copyTo(out) }
                }
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
