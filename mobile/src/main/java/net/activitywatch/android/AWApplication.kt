package net.activitywatch.android

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class AWApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Follow the OS dark/light setting from process start so that:
        // - WebView correctly reports prefers-color-scheme: dark when system is dark
        // - The initial chrome (applyWebUiChrome in MainActivity) matches the system
        // Without this, Theme.AppCompat.Light keeps the Activity in light mode and
        // WebView always sees prefers-color-scheme: light (aw-android#300).
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }
}
