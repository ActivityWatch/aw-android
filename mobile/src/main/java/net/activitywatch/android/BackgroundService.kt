package net.activitywatch.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.ForegroundServiceStartNotAllowedException
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
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject

private const val TAG = "BackgroundService"
private const val CHANNEL_ID = "aw_background_channel"
private const val NOTIFICATION_ID = 1

internal const val BACKGROUND_SERVICE_RESTART_MODE = Service.START_NOT_STICKY

internal fun backgroundServiceStartOrigin(explicitOrigin: String?): String =
    explicitOrigin ?: BackgroundService.START_ORIGIN_SYSTEM_RESTART

class BackgroundService : Service() {

    companion object {
        // Sent by SyncSettingsActivity when the user toggles sync on/off so the
        // running scheduler reflects the new setting immediately without a restart.
        const val ACTION_SYNC_ENABLED_CHANGED = "net.activitywatch.android.SYNC_ENABLED_CHANGED"
        const val EXTRA_START_ORIGIN = "net.activitywatch.android.extra.START_ORIGIN"
        const val START_ORIGIN_ACTIVITY = "activity"
        const val START_ORIGIN_BOOT = "boot"
        const val START_ORIGIN_SETTINGS = "settings"
        const val START_ORIGIN_SYSTEM_RESTART = "system-restart"

        // How long the queued hostname rewrite waits for the server task to exit
        // before giving up (the next service start retries).
        private const val SERVER_EXIT_WAIT_MS = 60_000L
        private const val SERVER_EXIT_POLL_MS = 1_000L
    }

    private lateinit var syncScheduler: SyncScheduler
    private lateinit var rustInterface: RustInterface

    // Becomes true after the first full onStartCommand() completes (server started,
    // workers scheduled, etc.). Guards against ACTION_SYNC_ENABLED_CHANGED skipping
    // full initialization when Android kills and recreates the service.
    private var isFullyStarted = false

    // onStartCommand can run more than once per launch (activity start plus a
    // sticky/boot restart). The migrations below are blocking datastore-worker
    // commands; queueing them twice doubled the startup stall in aw-android#261.
    private var migrationsQueued = false

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "BackgroundService created")
        // Promote to foreground BEFORE slow native init so the OS 5-second
        // startForegroundService() countdown can't expire.  RustInterface loads
        // libaw_server.so + calls initialize() which can take several seconds on
        // slow/no-KVM emulators (CI).
        createNotificationChannel()
        val notification = createNotification()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                else
                    0
            )
        } catch (e: ForegroundServiceStartNotAllowedException) {
            Log.e(TAG, "Foreground promotion rejected; stopping before initialization", e)
            stopSelf()
            return
        }
        rustInterface = RustInterface(this)
        syncScheduler = SyncScheduler(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!::rustInterface.isInitialized || !::syncScheduler.isInitialized) {
            Log.w(TAG, "Ignoring start command because foreground promotion failed")
            stopSelf(startId)
            return BACKGROUND_SERVICE_RESTART_MODE
        }

        val startOrigin = backgroundServiceStartOrigin(intent?.getStringExtra(EXTRA_START_ORIGIN))
        Log.i(TAG, "BackgroundService start origin: $startOrigin")

        // Only short-circuit for the scheduler-toggle action when the service is already
        // fully running. If Android killed the service while SyncSettingsActivity was open,
        // the toggle re-creates the service with this action as its first command — in that
        // case isFullyStarted is false and we fall through to run the complete startup path
        // (server start, event parsing, notify scheduling) before honouring the toggle.
        if (intent?.action == ACTION_SYNC_ENABLED_CHANGED && isFullyStarted) {
            val enabled = AWPreferences(this).isSyncEnabled()
            Log.i(TAG, "Sync enabled changed to $enabled; ${if (enabled) "starting" else "stopping"} scheduler")
            if (enabled) syncScheduler.start() else syncScheduler.stop()
            return BACKGROUND_SERVICE_RESTART_MODE
        }

        Log.i(TAG, "BackgroundService started")

        // Ensure the API key is written to config.toml before the server reads it.
        // MainActivity does this when the user launches the app normally, but
        // BackgroundService is also started on BOOT_COMPLETED (via SyncAlarmReceiver)
        // without ever going through MainActivity, so we need to guarantee the key
        // exists here as well.
        ensureDashboardApiKey(this)

        val prefs = AWPreferences(this)
        migrateSanitizedHostnameIdentity(prefs)

        // Start the server
        rustInterface.startServerTask()

        // Run hostname + legacy-bucket migrations off the main thread — both are blocking JNI.
        // Only mark as migrated on success so a retry is possible if the server wasn't ready yet.
        // Watcher-bucket migration must follow hostname migration so IDs are stable first.
        val needsHostnameMigration = !prefs.hasMigratedHostname()
        val needsWatcherBucketMigration = !prefs.hasMigratedWatcherAndroidBucketNames()
        if ((needsHostnameMigration || needsWatcherBucketMigration) && !migrationsQueued) {
            migrationsQueued = true
            CoroutineScope(Dispatchers.IO).launch {
                if (needsHostnameMigration) {
                    val hostname = rustInterface.getDeviceName(this@BackgroundService)
                    val result = rustInterface.migrateHostname(hostname)
                    Log.i(TAG, "Hostname migration result: $result")
                    if (migrationSucceeded(result, "Migrated hostname for", "Hostname")) {
                        prefs.setHostnameMigrated()
                    }
                }
                if (needsWatcherBucketMigration) {
                    migrateWatcherAndroidTestBuckets(prefs)
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
        // A sticky recreation can occur while the app is backgrounded, where Android 12+
        // may reject foreground promotion. Only explicit, eligible callers restart us.
        return BACKGROUND_SERVICE_RESTART_MODE
    }

    /**
     * Rewrite unsanitized bucket hostnames and fold leftover sync folders before
     * the datastore worker opens sqlite.db. Must run before [startServerTask].
     */
    private fun migrateSanitizedHostnameIdentity(prefs: AWPreferences) {
        val current = deviceHostname(this)
        val legacy = legacyDeviceHostnames(this)

        val syncDir = existingAwSyncDirectory(this)
        if (syncDir != null) {
            val deviceId =
                File(filesDir, "device_id").takeIf { it.isFile }?.readText()?.trim()?.takeIf {
                    it.isNotEmpty()
                }
            val moved =
                SanitizedHostnameMigration.migrateSyncFolders(syncDir, current, legacy, deviceId)
            if (moved > 0) {
                Log.i(TAG, "Migrated $moved leftover sync-folder entries to '$current'")
            }
        }

        if (prefs.sanitizedHostnameMigratedTo() == current) return
        val dbFile = File(filesDir, "sqlite.db")
        if (!dbFile.isFile) {
            prefs.setSanitizedHostnameMigratedTo(current)
            return
        }
        if (RustInterface.serverStarted) {
            // The datastore worker owns sqlite.db while the server runs; a raw
            // rewrite under it is unsafe. Queue the rewrite for when the server
            // task exits instead of skipping silently — a silent skip here would
            // leave the preference unset and the rewrite would never converge
            // (every later start sees serverStarted true again).
            Log.i(TAG, "Datastore already open; queueing bucket hostname rewrite until the server task exits")
            queueHostnameRewriteAfterServerExit(prefs, dbFile, current, legacy)
            return
        }
        val updated =
            SanitizedHostnameMigration.rewriteBucketHostnamesInDatabase(dbFile, current, legacy)
        if (updated >= 0) {
            prefs.setSanitizedHostnameMigratedTo(current)
        }
    }

    private fun queueHostnameRewriteAfterServerExit(
        prefs: AWPreferences,
        dbFile: File,
        current: String,
        legacy: List<String>,
    ) {
        Thread {
            try {
                var waitedMs = 0L
                while (RustInterface.serverStarted && waitedMs < SERVER_EXIT_WAIT_MS) {
                    Thread.sleep(SERVER_EXIT_POLL_MS)
                    waitedMs += SERVER_EXIT_POLL_MS
                }
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (RustInterface.serverStarted) {
                Log.i(TAG, "Server task still running; hostname rewrite stays deferred to the next start")
                return@Thread
            }
            val updated =
                SanitizedHostnameMigration.rewriteBucketHostnamesInDatabase(dbFile, current, legacy)
            if (updated >= 0) {
                prefs.setSanitizedHostnameMigratedTo(current)
            }
        }.apply {
            name = "sanitized-hostname-rewrite"
            isDaemon = true
            start()
        }
    }

    private fun migrateWatcherAndroidTestBuckets(prefs: AWPreferences) {
        // Older production releases wrote activity into aw-watcher-android-test.
        // The JNI is the only caller of migrate_test_bucket_names(); shipping the
        // Rust function alone does nothing until this path runs.
        //
        // Rename-only is in the current submodule pin. Collision merge (both
        // buckets exist) needs ActivityWatch/aw-server-rust#661; until that SHA
        // is bumped, leftover test buckets stay and we retry on the next start.
        val result = rustInterface.migrateWatcherAndroidBucketNames()
        Log.i(TAG, "Watcher bucket migration result: $result")
        if (!WatcherAndroidBucketMigration.migrationSucceeded(result)) {
            migrationSucceeded(result, WatcherAndroidBucketMigration.SUCCESS_PREFIX, "Watcher bucket")
            return
        }
        val leftover = WatcherAndroidBucketMigration.legacyBucketIds(rustInterface.getBucketsJSON())
        if (WatcherAndroidBucketMigration.shouldMarkComplete(leftover)) {
            prefs.setWatcherAndroidBucketNamesMigrated()
        } else {
            Log.w(
                TAG,
                "Watcher bucket migration left legacy bucket(s) $leftover; will retry on next start"
            )
        }
    }

    private fun migrationSucceeded(result: String, successPrefix: String, name: String): Boolean {
        if (result.startsWith(successPrefix)) {
            return true
        }
        val errorMsg = try {
            JSONObject(result).optString("error", result)
        } catch (e: JSONException) {
            result.ifEmpty { "empty response" }
        }
        Log.w(TAG, "$name migration failed ($errorMsg); will retry on next start")
        return false
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
