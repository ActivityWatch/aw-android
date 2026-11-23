package net.activitywatch.android

import android.app.Application
import android.system.Os
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ConfigureNativeTempDirsTest {
    @Test
    fun setsTmpdirAndSqliteTmpdirToAppCacheDir() {
        val context = RuntimeEnvironment.getApplication()
        val expected = context.cacheDir.absolutePath

        configureNativeTempDirs(context)

        assertEquals(expected, Os.getenv("TMPDIR"))
        assertEquals(expected, Os.getenv("SQLITE_TMPDIR"))
    }
}
