package net.activitywatch.android

import androidx.test.core.app.takeScreenshot
import androidx.test.core.graphics.writeToTestStorage
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.rules.activityScenarioRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import java.io.IOException

/*
 * When this test is executed via gradle managed devices, the saved image files will be stored at
 * build/outputs/managed_device_android_test_additional_output/debugAndroidTest/managedDevice/nexusOneApi30/
 */
class ScreenshotTest {

    // a handy JUnit rule that stores the method name, so it can be used to generate unique
    // screenshot files per test method
    @get:Rule
    var nameRule = TestName()

    @get:Rule
    val activityScenarioRule = activityScenarioRule<MainActivity>()

    /**
     * Captures and saves an image of the entire device screen to storage.
     */
    @Test
    @Throws(IOException::class)
    fun saveDeviceScreenBitmap() {
        // TODO: Not a good method to sleep, need to properly hook on page load
        Thread.sleep(2000)
        takeScreenshot()
            .writeToTestStorage("${javaClass.simpleName}_${nameRule.methodName}")
    }
}