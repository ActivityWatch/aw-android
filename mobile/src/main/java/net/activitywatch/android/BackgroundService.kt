package net.activitywatch.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject

private const val TAG = "BackgroundService"
private const val CHANNEL_ID = "aw_background_channel"
private const val NOTIFICATION_ID = 1

class BackgroundService : Service() {

    companion object {
        // Sent by SyncSettingsActivity when the user toggles sync on/off so the
        // running scheduler reflects the new setting immediately without a restart.
        const val ACTION_SYNC_ENABLED_CHANGED = "net.activitywatch.android.SYNC_ENABLED_CHANGED"
    }

    private lateinit var syncScheduler: SyncScheduler
    private lateinit var rustInterface: RustInterface

    // Becomes true after the first full onStartCommand() completes (server started,
    // workers scheduled, etc.). Guards against ACTION_SYNC_ENABLED_CHANGED skipping
    // full initialization when Android kills and recreates the service.
    private var isFullyStarted = false

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "BackgroundService created")
        // Promote to foreground BEFORE slow native init so the OS 5-second
        // startForegroundService() countdown can't expire.  RustInterface loads
        // libaw_server.so + calls initialize() which can take several seconds on
        // slow/no-KVM emulators (CI).
        createNotificationChannel()
        val notification = createNotification()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else
                0
        )
        rustInterface = RustInterface(this)
        syncScheduler = SyncScheduler(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Only short-circuit for the scheduler-toggle action when the service is already
        // fully running. If Android killed the service while SyncSettingsActivity was open,
        // the toggle re-creates the service with this action as its first command — in that
        // case isFullyStarted is false and we fall through to run the complete startup path
        // (server start, event parsing, notify scheduling) before honouring the toggle.
        if (intent?.action == ACTION_SYNC_ENABLED_CHANGED && isFullyStarted) {
            val enabled = AWPreferences(this).isSyncEnabled()
            Log.i(TAG, "Sync enabled changed to $enabled; ${if (enabled) "starting" else "stopping"} scheduler")
            if (enabled) syncScheduler.start() else syncScheduler.stop()
            return START_STICKY
        }

        Log.i(TAG, "BackgroundService started")

        // Ensure the API key is written to config.toml before the server reads it.
        // MainActivity does this when the user launches the app normally, but
        // BackgroundService is also started on BOOT_COMPLETED (via SyncAlarmReceiver)
        // without ever going through MainActivity, so we need to guarantee the key
        // exists here as well.
        ensureDashboardApiKey(this)

        // Start the server
        rustInterface.startServerTask()

        // Run hostname migration off the main thread — migrateHostname() is a blocking JNI call.
        // Only mark as migrated on success so a retry is possible if the server wasn't ready yet.
        val prefs = AWPreferences(this)
        if (!prefs.hasMigratedHostname()) {
            CoroutineScope(Dispatchers.IO).launch {
                val hostname = rustInterface.getDeviceName(this@BackgroundService)
                val result = rustInterface.migrateHostname(hostname)
                Log.i(TAG, "Hostname migration result: $result")
                // The native migrateHostname() returns a plain-text success string
                // ("Migrated hostname for N bucket(s)") or a JSON error object
                // ({"error": "..."}).  Treat the plain-text prefix as success; only
                // retry when the native lib explicitly reports an error.
                val migrationSucceeded = if (result.startsWith("Migrated hostname for")) {
                    true
                } else {
                    val errorMsg = try {
                        JSONObject(result).optString("error", result)
                    } catch (e: JSONException) {
                        result.ifEmpty { "empty response" }
                    }
                    Log.w(TAG, "Hostname migration failed ($errorMsg); will retry on next start")
                    false
                }
                if (migrationSucceeded) {
                    prefs.setHostnameMigrated()
                }
            }
        }

        // Start the sync scheduler only when the user has enabled sync.
        // Default is off — the sync directory is not accessible to other apps
        // (Android scoped storage), so auto-sync would silently no-op for most users.
        if (prefs.isSyncEnabled()) {
            syncScheduler.start()
        } else {
            Log.i(TAG, "Sync is disabled (default). Enable it in settings to start syncing.")
        }

        // Schedule event parsing
        scheduleEventParsing()

        // Schedule activity-time notifications (aw-notify)
        scheduleNotifyChecks()

        isFullyStarted = true
        return START_STICKY
    }

    private fun scheduleNotifyChecks() {
        val notifyRequest = androidx.work.PeriodicWorkRequest.Builder(
            net.activitywatch.android.workers.NotifyWorker::class.java,
            15, java.util.concurrent.TimeUnit.MINUTES
        )
            .addTag("NotifyWorker")
            .build()

        androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "NotifyWorker",
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            notifyRequest
        )
        Log.i(TAG, "Scheduled activity-time notification worker (every 15 minutes)")
    }

    private fun scheduleEventParsing() {
        val currentDate = java.util.Calendar.getInstance()
        val dueDate = java.util.Calendar.getInstance()
        // Set to midnight
        dueDate.set(java.util.Calendar.HOUR_OF_DAY, 0)
        dueDate.set(java.util.Calendar.MINUTE, 0)
        dueDate.set(java.util.Calendar.SECOND, 0)
        dueDate.set(java.util.Calendar.MILLISECOND, 0)
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
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            saveRequest
        )
        Log.i(TAG, "Scheduled event parsing worker with initial delay: ${timeDiff}ms")
    }

    override fun onDestroy() {
        Log.i(TAG, "BackgroundService destroyed")
        if (::syncScheduler.isInitialized) syncScheduler.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Background tracking"
            val descriptionText = "Keeps ActivityWatch running in the background"
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
            .setContentTitle("ActivityWatch")
            .setContentText("Running in the background")
            // Adaptive launcher mipmaps are not valid status-bar icons (alpha-only).
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
