package net.activitywatch.android

import android.content.Intent
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.takeScreenshot
import androidx.test.core.graphics.writeToTestStorage
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import java.io.IOException

private const val TAG = "ScreenshotTest"

/*
 * When this test is executed via gradle managed devices, the saved image files will be stored at
 * build/outputs/managed_device_android_test_additional_output/debugAndroidTest/managedDevice/nexusOneApi30/
 */
class ScreenshotTest {
    // a handy JUnit rule that stores the method name, so it can be used to generate unique
    // screenshot files per test method
    @get:Rule
    var permissionRule = GrantPermissionRule.grant(android.Manifest.permission.PACKAGE_USAGE_STATS)

    @get:Rule
    var nameRule = TestName()

    lateinit var scenario: ActivityScenario<MainActivity>
    /**
     * Captures and saves an image of the entire device screen to storage.
     */
    @Test
    @Throws(IOException::class)
    fun saveDeviceScreenBitmap() {
        //Thread.sleep(100)
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
        // TODO: scenarios dont clean up automatically ?
        scenario = ActivityScenario.launch(intent)

        // TODO: Not a good method to sleep, need to properly hook on page load
        Thread.sleep(5000)
        Log.i(TAG, "Taking screenshot")

        val bitmap = takeScreenshot()
        bitmap.writeToTestStorage("${javaClass.simpleName}_${nameRule.methodName}")
        Log.i(TAG, "Took screenshot!")
    }
}
