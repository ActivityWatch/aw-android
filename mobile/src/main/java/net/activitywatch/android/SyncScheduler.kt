package net.activitywatch.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

private const val TAG = "SyncScheduler"

class SyncScheduler(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var syncInterface: SyncInterface
    private var isRunning = false
    
    private val syncRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                performSync()
                // Next sync is scheduled inside the performSync callback, after the current one completes.
            }
        }
    }
    
    fun start() {
        if (isRunning) {
            Log.w(TAG, "Sync scheduler already running")
            return
        }
        
        Log.i(TAG, "Starting sync scheduler - first sync in 1 minute, then every 15 minutes")
        isRunning = true
        
        try {
            syncInterface = SyncInterface(context)
            
            // First sync after 1 minute
            handler.postDelayed(syncRunnable, 60 * 1000L)
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "aw-sync native library unavailable; sync scheduler disabled", e)
            isRunning = false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start sync scheduler", e)
            isRunning = false
        }
    }
    
    fun stop() {
        Log.i(TAG, "Stopping sync scheduler")
        isRunning = false
        handler.removeCallbacks(syncRunnable)
    }
    
    private fun performSync() {
        Log.i(TAG, "Performing automatic sync...")

        syncInterface.syncBothAsync { success, message ->
            if (success) {
                Log.i(TAG, "Automatic sync completed successfully: $message")
            } else {
                Log.w(TAG, "Automatic sync failed: $message")
            }
            // Schedule next sync only after this one completes, preventing overlapping JNI calls.
            if (isRunning) {
                Log.i(TAG, "Scheduling next sync in 15 minutes")
                handler.postDelayed(syncRunnable, 15 * 60 * 1000L)
            }
        }
    }
}
