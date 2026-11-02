package net.activitywatch.android

import android.app.Application
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Exercises the real preferences, activity lifecycle, resources and painted root. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class NativeScreenThemeActivityTest {
    @Test
    fun authDarkOverridesLightDeviceBeforeAndAfterRecreation() {
        checkScreen(AuthSettingsActivity::class.java, "notnight", "dark", Configuration.UI_MODE_NIGHT_YES, R.color.chrome_dark)
    }

    @Test
    fun authLightOverridesDarkDeviceBeforeAndAfterRecreation() {
        checkScreen(AuthSettingsActivity::class.java, "night", "light", Configuration.UI_MODE_NIGHT_NO, R.color.chrome_light)
    }

    @Test
    fun syncDarkOverridesLightDeviceBeforeAndAfterRecreation() {
        checkScreen(SyncSettingsActivity::class.java, "notnight", "dark", Configuration.UI_MODE_NIGHT_YES, R.color.chrome_dark)
    }

    @Test
    fun syncLightOverridesDarkDeviceBeforeAndAfterRecreation() {
        checkScreen(SyncSettingsActivity::class.java, "night", "light", Configuration.UI_MODE_NIGHT_NO, R.color.chrome_light)
    }

    private fun checkScreen(
        activityClass: Class<out AppCompatActivity>,
        deviceMode: String,
        setting: String,
        expectedMode: Int,
        color: Int,
    ) {
        RuntimeEnvironment.setQualifiers(deviceMode)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        val context = RuntimeEnvironment.getApplication()
        // Robolectric does not grant this merged AndroidX manifest permission.
        shadowOf(context).grantPermissions("${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val preferences = AWPreferences(context)
        val original = preferences.getWebUiTheme()
        try {
            preferences.setWebUiTheme(setting)
            val controller = Robolectric.buildActivity(activityClass).setup()
            try {
                assertScreen(controller.get(), expectedMode, color)
                controller.recreate()
                assertScreen(controller.get(), expectedMode, color)
            } finally {
                controller.pause().stop().destroy()
            }
        } finally {
            preferences.setWebUiTheme(original)
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_UNSPECIFIED)
        }
    }

    private fun assertScreen(activity: AppCompatActivity, expectedMode: Int, color: Int) {
        assertEquals(
            activity.javaClass.simpleName,
            expectedMode,
            activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK,
        )
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        assertEquals(
            "${activity.javaClass.simpleName} must paint the saved Appearance color",
            ContextCompat.getColor(activity, color),
            (root.background as ColorDrawable).color,
        )
    }
}
