package net.activitywatch.android.watcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

private const val TAG = "AlarmReceiver"

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.w(TAG, "AlarmReceiver called")
        val usw = UsageStatsWatcher(context)
        if (intent.action == "android.intent.action.BOOT_COMPLETED") {
            // FIXME: Doesn't seem to be triggered as it should
            Log.w(TAG, "Received BOOT_COMPLETED, setting up alarm")
            usw.setupAlarm()
        } else if(intent.action == "net.activitywatch.android.watcher.LOG_DATA") {
            Log.w(TAG, "Action ${intent.action}, running sendHeartbeats")
            if(UsageStatsWatcher.isUsageAllowed(context)) {
                usw.sendHeartbeats()
            }
        } else {
            Log.w(TAG, "Unknown intent $intent with action ${intent.action}, doing nothing")
        }
    }
}