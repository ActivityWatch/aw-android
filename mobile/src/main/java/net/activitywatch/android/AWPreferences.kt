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

    // Whether the server should bind to all network interfaces (0.0.0.0)
    // instead of localhost only (127.0.0.1).
    // Off by default for security.
    fun isNetworkAccessEnabled(): Boolean {
        return sharedPreferences.getBoolean("networkAccessEnabled", false)
    }

    fun setNetworkAccessEnabled(enabled: Boolean) {
        val editor = sharedPreferences.edit()
        editor.putBoolean("networkAccessEnabled", enabled)
        editor.apply()
    }
}