package net.activitywatch.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

private const val TAG = "SyncAlarmReceiver"

class SyncAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "SyncAlarmReceiver called with action: ${intent.action}")

        when (intent.action) {
            "net.activitywatch.android.SYNC_ALARM" -> {
                Log.i(TAG, "Performing scheduled sync...")
                val pendingResult = goAsync()
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
            "android.intent.action.BOOT_COMPLETED" -> {
                Log.i(TAG, "Device booted, starting BackgroundService")
                val serviceIntent = Intent(context, BackgroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
            else -> {
                Log.w(TAG, "Unknown intent action: ${intent.action}")
            }
        }
    }
}
