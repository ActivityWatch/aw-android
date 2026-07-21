package net.activitywatch.android

import android.content.Context
import android.content.SharedPreferences

class AWPreferences(context: Context) {
    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences("AWPreferences", Context.MODE_PRIVATE)

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

    // Sync is off by default; user must explicitly enable it once a sync directory
    // is configured (e.g. via Storage Access Framework).
    fun isSyncEnabled(): Boolean {
        return sharedPreferences.getBoolean("syncEnabled", false)
    }

    fun setSyncEnabled(enabled: Boolean) {
        sharedPreferences.edit().putBoolean("syncEnabled", enabled).apply()
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
