package net.activitywatch.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import net.activitywatch.android.workers.SyncWorker

private const val TAG = "SyncAlarmReceiver"
private const val SYNC_WORK_NAME = "SyncWorker"

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
                val lastHandlerPassAt = AWPreferences(context).getHandlerPassAt()
                if (alarmSyncIsRedundant(lastHandlerPassAt, System.currentTimeMillis())) {
                    Log.i(TAG, "Synced recently (scheduler is running); skipping fallback sync")
                    return
                }
                Log.i(TAG, "Enqueuing scheduled sync...")
                // KEEP: a worker still running from the previous alarm covers this one.
                WorkManager.getInstance(context).enqueueUniqueWork(
                    SYNC_WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<SyncWorker>().build()
                )
            }
            else -> {
                Log.w(TAG, "Unknown intent action: ${intent.action}")
            }
        }
    }
}
