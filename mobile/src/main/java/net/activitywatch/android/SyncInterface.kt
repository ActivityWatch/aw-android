package net.activitywatch.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

private const val TAG = "SyncInterface"

class SyncInterface(context: Context) {
    private val appContext: Context = context.applicationContext
    private val syncDir: String
    
    init {
        // Use Downloads folder for easy user access: /sdcard/Download/ActivityWatch/
        val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        )
        syncDir = "$downloadsDir/ActivityWatch"
        Os.setenv("AW_SYNC_DIR", syncDir, true)
        
        // Create sync directory if it doesn't exist
        File(syncDir).mkdirs()
        
        System.loadLibrary("aw_sync")
        Log.i(TAG, "aw-sync initialized with sync dir: $syncDir")
    }
    
    // Native JNI functions
    private external fun syncPullAll(port: Int, hostname: String): String
    private external fun syncPull(port: Int, hostname: String): String
    private external fun syncPush(port: Int, hostname: String): String
    private external fun syncBoth(port: Int, hostname: String): String
    external fun getSyncDir(): String
    
    private fun getDeviceName(): String {
        return android.provider.Settings.Global.getString(
            appContext.contentResolver, 
            android.provider.Settings.Global.DEVICE_NAME
        ) ?: android.os.Build.MODEL ?: "Unknown"
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
        val hostname = getDeviceName()
        performSyncAsync("Full Sync", callback) {
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
            try {
                val response = syncFn()
                val json = JSONObject(response)
                val success = json.getBoolean("success")
                val message = if (success) {
                    json.getString("message")
                } else {
                    json.getString("error")
                }
                
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
            }
        }
    }
    
    fun getSyncDirectory(): String = syncDir
}
