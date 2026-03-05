package net.activitywatch.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

private const val TAG = "SyncAlarmReceiver"

class SyncAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "SyncAlarmReceiver called with action: ${intent.action}")
        
        when (intent.action) {
            "net.activitywatch.android.SYNC_ALARM" -> {
                Log.i(TAG, "Performing scheduled sync...")
                performSync(context)
            }
            "android.intent.action.BOOT_COMPLETED" -> {
                Log.i(TAG, "Device booted, rescheduling sync alarm")
                SyncScheduler(context).start()
            }
            else -> {
                Log.w(TAG, "Unknown intent action: ${intent.action}")
            }
        }
    }
    
    private fun performSync(context: Context) {
        try {
            val syncInterface = SyncInterface(context)
            syncInterface.syncBothAsync { success, message ->
                if (success) {
                    Log.i(TAG, "Automatic sync completed successfully: $message")
                } else {
                    Log.w(TAG, "Automatic sync failed: $message")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to perform sync", e)
        }
    }
}
