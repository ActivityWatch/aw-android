package net.activitywatch.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import net.activitywatch.android.workers.SyncWorker

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
                Log.i(TAG, "Enqueuing scheduled sync...")
                WorkManager.getInstance(context).enqueue(
                    OneTimeWorkRequestBuilder<SyncWorker>().build()
                )
            }
            else -> {
                Log.w(TAG, "Unknown intent action: ${intent.action}")
            }
        }
    }
}
