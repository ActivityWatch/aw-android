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

    fun getRemoteServerUrl(): String {
        return sharedPreferences.getString("remoteServerUrl", "") ?: ""
    }

    fun setRemoteServerUrl(url: String) {
        val editor = sharedPreferences.edit()
        editor.putString("remoteServerUrl", url)
        editor.apply()
    }

    fun getRemoteServerUsername(): String {
        return sharedPreferences.getString("remoteServerUsername", "") ?: ""
    }

    fun setRemoteServerUsername(username: String) {
        val editor = sharedPreferences.edit()
        editor.putString("remoteServerUsername", username)
        editor.apply()
    }

    fun getRemoteServerPassword(): String {
        return sharedPreferences.getString("remoteServerPassword", "") ?: ""
    }

    fun setRemoteServerPassword(password: String) {
        val editor = sharedPreferences.edit()
        editor.putString("remoteServerPassword", password)
        editor.apply()
    }

    fun getSkipPackages(): Set<String> {
        return sharedPreferences.getStringSet("skipPackages", emptySet()) ?: emptySet()
    }

    fun setSkipPackages(packages: Set<String>) {
        val editor = sharedPreferences.edit()
        editor.putStringSet("skipPackages", packages)
        editor.apply()
    }

    fun addSkipPackage(packageName: String) {
        val current = getSkipPackages().toMutableSet()
        current.add(packageName)
        setSkipPackages(current)
    }

    fun removeSkipPackage(packageName: String) {
        val current = getSkipPackages().toMutableSet()
        current.remove(packageName)
        setSkipPackages(current)
    }
}