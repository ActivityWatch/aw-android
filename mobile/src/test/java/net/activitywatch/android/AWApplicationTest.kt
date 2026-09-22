package net.activitywatch.android

import androidx.appcompat.app.AppCompatDelegate
import org.junit.Assert.assertEquals
import org.junit.Test

class AWApplicationTest {
    @Test
    fun applicationClassExists() {
        // AWApplication must exist; Application.onCreate sets MODE_NIGHT_FOLLOW_SYSTEM
        // so that WebView reports the correct prefers-color-scheme from the very first
        // frame (aw-android#300).
        val clazz = AWApplication::class.java
        val superClass = clazz.superclass
        assertEquals("android.app.Application", superClass?.name)
    }

    @Test
    fun modeNightFollowSystemConstantValue() {
        // Sanity-check that the constant we rely on is stable across AppCompat versions.
        assertEquals(-1, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }
}
