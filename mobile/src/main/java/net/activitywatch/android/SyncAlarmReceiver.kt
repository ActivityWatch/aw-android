package net.activitywatch.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "SyncAlarmReceiver"

class SyncAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "SyncAlarmReceiver called with action: ${intent.action}")

        when (intent.action) {
            "net.activitywatch.android.SYNC_ALARM" -> {
                if (!AWPreferences(context).isSyncEnabled()) {
                    Log.i(TAG, "Sync is disabled; cancelling stale alarm")
                    SyncScheduler.cancelAlarm(context)
                    return
                }
                Log.i(TAG, "Performing scheduled sync...")
                val pendingResult = goAsync()
                // Create SyncInterface and perform sync on IO dispatcher to avoid
                // main-thread file operations in SyncInterface.init{}.
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val syncInterface = SyncInterface(context)
                        syncInterface.syncBothAsync { success, message ->
                            if (success) {
                                Log.i(TAG, "Automatic sync completed successfully: $message")
                            } else {
                                Log.w(TAG, "Automatic sync failed: $message")
                            }
                            pendingResult.finish()
                        }
                    } catch (e: UnsatisfiedLinkError) {
                        Log.e(TAG, "aw-sync native library unavailable; skipping sync", e)
                        pendingResult.finish()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to perform sync", e)
                        pendingResult.finish()
                    }
                }
            }
            else -> {
                Log.w(TAG, "Unknown intent action: ${intent.action}")
            }
        }
    }
}
