package net.activitywatch.android

import android.app.Application
import android.system.Os
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, shadows = [ConfigureNativeTempDirsTest.RecordingOs::class])
class ConfigureNativeTempDirsTest {
    @Test
    fun setsTmpdirAndSqliteTmpdirToAppCacheDir() {
        val context = RuntimeEnvironment.getApplication()
        val expected = context.cacheDir.absolutePath
        RecordingOs.assignments.clear()

        configureNativeTempDirs(context)

        assertEquals(
            listOf(Triple("TMPDIR", expected, true), Triple("SQLITE_TMPDIR", expected, true)),
            RecordingOs.assignments
        )
    }

    // Robolectric's default Os shadow does not implement setenv/getenv.
    // Record the Android API calls rather than reading the host JVM's environment.
    @Implements(Os::class)
    class RecordingOs {
        companion object {
            val assignments = mutableListOf<Triple<String, String, Boolean>>()

            @JvmStatic
            @Implementation
            fun setenv(name: String, value: String, overwrite: Boolean) {
                assignments.add(Triple(name, value, overwrite))
            }
        }
    }
}
