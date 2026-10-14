package net.activitywatch.android

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class NativeScreenThemeTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    private fun themeOf(activity: String): String? =
        Regex("""android:name="\.$activity"[^>]*?android:theme="@style/([\w.]+)"""")
            .find(manifest)?.groupValues?.get(1)

    @Test
    fun settingsScreensFollowingNightModeUseDayNightTheme() {
        // applySafeWindowInsets(followNightMode = true) paints chrome_dark in night
        // mode; a light-only theme would then draw dark text on it.
        for (activity in listOf("AuthSettingsActivity", "SyncSettingsActivity")) {
            val source = File("src/main/java/net/activitywatch/android/$activity.kt").readText()
            assert(source.contains("applySafeWindowInsets(followNightMode = true)")) { activity }
            assertEquals(activity, "AppTheme.FollowNight", themeOf(activity))
        }
    }
}
