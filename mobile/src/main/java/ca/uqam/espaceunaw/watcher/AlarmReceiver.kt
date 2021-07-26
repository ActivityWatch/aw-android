package ca.uqam.espaceunaw.watcher

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
            val resources = context.packageManager.getResourcesForApplication("net.activitywatch.android")
            val sharedPrefsKey: Int = resources.getIdentifier("shared_preferences_key", "string", "net.activitywatch.android")
            val collectEnabledKey = resources.getIdentifier("collect_enabled_key", "string", "net.activitywatch.android")
            var sharedPref = context.getSharedPreferences(context.getString(sharedPrefsKey), Context.MODE_PRIVATE)
            val collectEnabled = sharedPref.getBoolean(context.getString(collectEnabledKey), true)

            if(collectEnabled && usw.isUsageAllowed()) {
                usw.sendHeartbeats()
            }
        } else {
            Log.w(TAG, "Unknown intent $intent with action ${intent.action}, doing nothing")
        }
    }
}