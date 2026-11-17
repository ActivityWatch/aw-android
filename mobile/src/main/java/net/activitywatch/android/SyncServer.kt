package net.activitywatch.android

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Run on the sync executor, whose lifetime is owned by WorkManager on cold starts. */
internal fun ensureSyncServer(context: Context) {
    // Match the foreground path: configure auth before Rust reads config.toml.
    val apiKey = synchronized(BackgroundService.sanitizedMigrationLock) {
        ensureDashboardApiKey(context)
    }
    val url = URL(buildDashboardUrl("http://127.0.0.1:${BuildConfig.SERVER_PORT}/api/0/info", apiKey))
    awaitSyncServer(
        start = {
            val rust = RustInterface(context)
            synchronized(BackgroundService.sanitizedMigrationLock) {
                migrateSyncFoldersBeforeServer(context)
                rust.startServerTask()
            }
        },
        ready = {
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 500
                connection.readTimeout = 500
                connection.responseCode == HttpURLConnection.HTTP_OK
            } catch (_: java.io.IOException) {
                false
            } finally {
                connection.disconnect()
            }
        },
    )
    Log.i("SyncInterface", "Local server ready for sync on port ${BuildConfig.SERVER_PORT}")
}

/** Called under the same lock on both foreground and worker startup paths. */
internal fun migrateSyncFoldersBeforeServer(context: Context) {
    val syncDir = existingAwSyncDirectory(context) ?: return
    val deviceId = File(context.filesDir, "device_id")
        .takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    val result = SanitizedHostnameMigration.migrateSyncFolders(
        syncDir, deviceHostname(context), legacyDeviceHostnames(context), deviceId,
    )
    if (result.failed > 0) {
        Log.w("SyncInterface", "${result.failed} sync-folder migration action(s) failed; retry on next start")
    }
}
