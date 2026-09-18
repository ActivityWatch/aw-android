package net.activitywatch.android

import android.content.Context
import android.content.SharedPreferences

class AWPreferences(context: Context) {
    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    companion object {
        const val PREFERENCES_NAME = "AWPreferences"
        const val LAST_SYNC_STATUS_CHANGED_ACTION =
            "net.activitywatch.android.LAST_SYNC_STATUS_CHANGED"
    }

    private val appContext = context.applicationContext

    // To check if it is the first time the app is being run
    // Set to false when user finishes onboarding
    fun isFirstTime(): Boolean {
        return sharedPreferences.getBoolean("isFirstTime", true)
    }

    // To set the first time flag to false after the first run
    fun setFirstTimeRunFlag() {
        val editor = sharedPreferences.edit()
        editor.putBoolean("isFirstTime", false)
        editor.apply()
    }

    // Optional: To reset the first time flag to true (for debugging, perhaps)
    fun resetFirstTimeRunFlag() {
        val editor = sharedPreferences.edit()
        editor.putBoolean("isFirstTime", true)
        editor.apply()
    }

    // To check if the hostname migration has already been run
    fun hasMigratedHostname(): Boolean {
        return sharedPreferences.getBoolean("hasMigratedHostname", false)
    }

    // To mark the hostname migration as done so it won't run again
    fun setHostnameMigrated() {
        sharedPreferences.edit().putBoolean("hasMigratedHostname", true).apply()
    }

    // Sanitized-hostname follow-up (ActivityWatch/aw-android#272). Distinct from
    // hasMigratedHostname, which only rewrites unknown/Unknown and is already true
    // on devices that still store the pre-#183 marketing name.
    fun sanitizedHostnameMigratedTo(): String? {
        return sharedPreferences.getString("sanitizedHostnameMigratedTo", null)
    }

    fun setSanitizedHostnameMigratedTo(hostname: String) {
        sharedPreferences.edit().putString("sanitizedHostnameMigratedTo", hostname).apply()
    }

    fun hasMigratedWatcherAndroidBucketNames(): Boolean {
        return sharedPreferences.getBoolean("hasMigratedWatcherAndroidBucketNames", false)
    }

    fun setWatcherAndroidBucketNamesMigrated() {
        sharedPreferences
            .edit()
            .putBoolean("hasMigratedWatcherAndroidBucketNames", true)
            .apply()
    }

    fun hasRequestedNotificationPermission(): Boolean {
        return sharedPreferences.getBoolean("hasRequestedNotificationPermission", false)
    }

    fun setNotificationPermissionRequested() {
        sharedPreferences.edit().putBoolean("hasRequestedNotificationPermission", true).apply()
    }

    // Sync is off by default; user must explicitly enable it once a sync directory
    // is configured (e.g. via Storage Access Framework).
    fun isSyncEnabled(): Boolean {
        return sharedPreferences.getBoolean("syncEnabled", false)
    }

    fun setSyncEnabled(enabled: Boolean) {
        sharedPreferences.edit().putBoolean("syncEnabled", enabled).apply()
    }

    // SAF URI for the user-chosen sync directory (content:// URI string, or null if not configured).
    // The URI has persisted permission granted via contentResolver.takePersistableUriPermission().
    fun getSyncDirUri(): String? {
        return sharedPreferences.getString("syncDirUri", null)
    }

    fun setSyncDirUri(uri: String?) {
        sharedPreferences.edit().putString("syncDirUri", uri).apply()
    }

    fun getLastSyncStatus(): SyncStatus? {
        val completedAt = sharedPreferences.getLong("lastSyncCompletedAt", 0L)
        if (completedAt == 0L) return null

        return SyncStatus(
            completedAt = completedAt,
            success = sharedPreferences.getBoolean("lastSyncSucceeded", false),
            error = sharedPreferences.getString("lastSyncError", null)
                ?.takeIf { it.isNotEmpty() },
            summary = sharedPreferences.getString("lastSyncSummary", null)
                ?.takeIf { it.isNotEmpty() },
            hasReport = sharedPreferences.getBoolean("lastSyncHasReport", false),
            eventsPulled = sharedPreferences.getInt("lastSyncEventsPulled", 0),
            eventsPushed = sharedPreferences.getInt("lastSyncEventsPushed", 0),
            peersImported = sharedPreferences.getInt("lastSyncPeersImported", 0),
            peersSkipped = sharedPreferences.getInt("lastSyncPeersSkipped", 0),
            peersFailed = sharedPreferences.getInt("lastSyncPeersFailed", 0),
            // Warnings are normalized to single lines before they get here, so a
            // newline join round-trips exactly and unlike a JSON array it cannot
            // fail to parse.
            warnings = sharedPreferences.getString("lastSyncWarnings", null)
                ?.split("\n")
                ?.filter { it.isNotEmpty() }
                ?: emptyList(),
        )
    }

    fun setLastSyncStatus(status: SyncStatus) {
        val editor = sharedPreferences.edit()
            .putLong("lastSyncCompletedAt", status.completedAt)
            .putBoolean("lastSyncSucceeded", status.success)
            .putBoolean("lastSyncHasReport", status.hasReport)
            .putInt("lastSyncEventsPulled", status.eventsPulled)
            .putInt("lastSyncEventsPushed", status.eventsPushed)
            .putInt("lastSyncPeersImported", status.peersImported)
            .putInt("lastSyncPeersSkipped", status.peersSkipped)
            .putInt("lastSyncPeersFailed", status.peersFailed)
        val error = status.error?.takeIf { it.isNotEmpty() }
        if (error == null) {
            editor.remove("lastSyncError")
        } else {
            editor.putString("lastSyncError", error)
        }
        val summary = status.summary?.takeIf { it.isNotEmpty() }
        if (summary == null) {
            editor.remove("lastSyncSummary")
        } else {
            editor.putString("lastSyncSummary", summary)
        }
        if (status.warnings.isEmpty()) {
            editor.remove("lastSyncWarnings")
        } else {
            editor.putString("lastSyncWarnings", status.warnings.joinToString("\n"))
        }
        editor.apply()
        appContext.sendBroadcast(
            android.content.Intent(LAST_SYNC_STATUS_CHANGED_ACTION).setPackage(appContext.packageName)
        )
    }

    // When the SyncScheduler registers the next run (on start or after each completed sync),
    // it records the epoch-ms of that run here so the UI can display the actual scheduled time
    // rather than computing it from lastCompletedAt + interval (which diverges after restarts).
    // Returns 0L if no scheduled time has been recorded yet.
    fun getSchedulerNextRunAt(): Long {
        return sharedPreferences.getLong("schedulerNextRunAt", 0L)
    }

    fun setSchedulerNextRunAt(epochMs: Long) {
        sharedPreferences.edit().putLong("schedulerNextRunAt", epochMs).apply()
    }

    // Dashboard authentication. Defaults to true so first-run gets a key generated
    // automatically. Set to false when the user explicitly disables auth in settings;
    // ensureDashboardApiKey() checks this before generating a new key so that the
    // "disabled" setting survives app restarts.
    fun isDashboardAuthEnabled(): Boolean {
        return sharedPreferences.getBoolean("dashboardAuthEnabled", true)
    }

    fun setDashboardAuthEnabled(enabled: Boolean) {
        sharedPreferences.edit().putBoolean("dashboardAuthEnabled", enabled).commit()
    }
}
