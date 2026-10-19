package net.activitywatch.android.watcher

import android.app.ForegroundServiceStartNotAllowedException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.activitywatch.android.widget.AsyncBroadcast

private const val TAG = "AlarmReceiver"

/** The receiver's heartbeat/completion path, with its existing unbounded IO lifetime. */
internal fun launchAlarmHeartbeats(
    finish: () -> Unit,
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    log: (String, Throwable?) -> Unit = { message, error -> Log.e(TAG, message, error); Unit },
    work: suspend () -> Unit,
): Job {
    val once = AsyncBroadcast.FinishOnce(finish, log)
    return scope.launch {
        try {
            work()
        } catch (e: Exception) {
            log("Failed to send heartbeats from alarm", e)
        } finally {
            once.finish()
        }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.w(TAG, "AlarmReceiver called")
        val usw = UsageStatsWatcher(context)
        if (intent.action == "android.intent.action.BOOT_COMPLETED") {
            Log.w(TAG, "Received BOOT_COMPLETED, setting up alarm and starting background service")
            usw.setupAlarm()
            val serviceIntent = Intent(
                context,
                net.activitywatch.android.BackgroundService::class.java,
            ).apply {
                putExtra(
                    net.activitywatch.android.BackgroundService.EXTRA_START_ORIGIN,
                    net.activitywatch.android.BackgroundService.START_ORIGIN_BOOT,
                )
            }
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: ForegroundServiceStartNotAllowedException) {
                Log.e(TAG, "Foreground-service start rejected after boot; service remains stopped", e)
            }
        } else if (intent.action == "net.activitywatch.android.watcher.LOG_DATA") {
            Log.w(TAG, "Action ${intent.action}, running sendHeartbeats")
            if (UsageStatsWatcher.isUsageAllowed(context)) {
                val pendingResult = goAsync()
                launchAlarmHeartbeats(finish = pendingResult::finish) {
                    usw.sendHeartbeatsSuspend()
                }
            }
        } else {
            Log.w(TAG, "Unknown intent $intent with action ${intent.action}, doing nothing")
        }
    }
}
