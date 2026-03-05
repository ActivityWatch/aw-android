package net.activitywatch.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

private const val TAG = "BackgroundService"
private const val CHANNEL_ID = "aw_background_channel"
private const val NOTIFICATION_ID = 1

class BackgroundService : Service() {

    private lateinit var syncScheduler: SyncScheduler
    private lateinit var rustInterface: RustInterface

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "BackgroundService created")
        rustInterface = RustInterface(this)
        syncScheduler = SyncScheduler(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "BackgroundService started")

        createNotificationChannel()
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        // Start the server
        rustInterface.startServerTask()

        // Start the sync scheduler
        syncScheduler.start()

        // Schedule event parsing
        scheduleEventParsing()

        return START_STICKY
    }

    private fun scheduleEventParsing() {
        val currentDate = java.util.Calendar.getInstance()
        val dueDate = java.util.Calendar.getInstance()
        // Set to midnight
        dueDate.set(java.util.Calendar.HOUR_OF_DAY, 0)
        dueDate.set(java.util.Calendar.MINUTE, 0)
        dueDate.set(java.util.Calendar.SECOND, 0)
        if (dueDate.before(currentDate)) {
            dueDate.add(java.util.Calendar.HOUR_OF_DAY, 24)
        }
        val timeDiff = dueDate.timeInMillis - currentDate.timeInMillis

        val saveRequest = androidx.work.PeriodicWorkRequest.Builder(
            net.activitywatch.android.workers.EventParsingWorker::class.java,
            24, java.util.concurrent.TimeUnit.HOURS
        )
            .setInitialDelay(timeDiff, java.util.concurrent.TimeUnit.MILLISECONDS)
            .addTag("EventParsing")
            .build()

        androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "EventParsingWorker",
            androidx.work.ExistingPeriodicWorkPolicy.UPDATE,
            saveRequest
        )
        Log.i(TAG, "Scheduled event parsing worker with initial delay: ${timeDiff}ms")
    }

    override fun onDestroy() {
        Log.i(TAG, "BackgroundService destroyed")
        syncScheduler.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "ActivityWatch Background Service"
            val descriptionText = "Keeps ActivityWatch server and sync running in the background"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent: PendingIntent =
            Intent(this, MainActivity::class.java).let { notificationIntent ->
                PendingIntent.getActivity(
                    this, 0, notificationIntent,
                    PendingIntent.FLAG_IMMUTABLE
                )
            }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ActivityWatch Server")
            .setContentText("Server and sync running in background")
            .setSmallIcon(R.mipmap.aw_launcher_round) // Make sure this icon exists or use a fallback
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
