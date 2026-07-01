package net.activitywatch.android

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

private const val TAG = "SyncScheduler"
private const val SYNC_INTERVAL_MS = 15 * 60 * 1000L
private const val ACTION_SYNC_ALARM = "net.activitywatch.android.SYNC_ALARM"

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

            // Primary path: Handler-based chain while BackgroundService is alive.
            handler.postDelayed(syncRunnable, 60 * 1000L)

            // Fallback path: AlarmManager fires SyncAlarmReceiver if BackgroundService is killed.
            scheduleAlarm()
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "aw-sync native library unavailable; sync scheduler disabled", e)
            isRunning = false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start sync scheduler", e)
            isRunning = false
        }
    }

    fun stop() {
        Log.i(TAG, "Stopping sync scheduler (Handler chain stopped; AlarmManager fallback kept alive)")
        isRunning = false
        handler.removeCallbacks(syncRunnable)
        // Intentionally do NOT cancel the AlarmManager alarm here: if the service is killed by
        // the OS (including on OEM devices that suppress START_STICKY restarts), the alarm must
        // survive to fire SyncAlarmReceiver. The alarm is only ever registered once (in start())
        // and is idempotent across service restarts.
    }

    private fun getSyncPendingIntent(): PendingIntent {
        val intent = Intent(ACTION_SYNC_ALARM).setPackage(context.packageName)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun scheduleAlarm() {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            android.os.SystemClock.elapsedRealtime() + SYNC_INTERVAL_MS,
            SYNC_INTERVAL_MS,
            getSyncPendingIntent()
        )
        Log.i(TAG, "Scheduled AlarmManager fallback sync every 15 minutes")
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
                handler.postDelayed(syncRunnable, SYNC_INTERVAL_MS)
            }
        }
    }
}
