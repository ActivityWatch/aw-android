package net.activitywatch.android.watcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit

private const val TAG = "AlarmReceiver"

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.w(TAG, "AlarmReceiver called")
        if (intent.action == "android.intent.action.BOOT_COMPLETED") {
            Log.w(TAG, "Received BOOT_COMPLETED, scheduling periodic work")
            schedulePeriodicWork(context)
        } else if(intent.action == "net.activitywatch.android.watcher.LOG_DATA") {
            Log.w(TAG, "Legacy alarm triggered, enqueueing heartbeat work")
            val workRequest = OneTimeWorkRequestBuilder<HeartbeatWorker>()
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                HeartbeatWorker.WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
        } else {
            Log.w(TAG, "Unknown intent $intent with action ${intent.action}, doing nothing")
        }
    }

    private fun schedulePeriodicWork(context: Context) {
        val workRequest = PeriodicWorkRequestBuilder<HeartbeatWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "heartbeat_periodic",
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
    }
}
