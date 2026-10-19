package net.activitywatch.android.watcher

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserDetectionTest {

    @Test
    fun `keeps unknown https handlers and drops built-in browsers and ourselves`() {
        val resolved = listOf(
            "com.android.chrome",
            "org.example.browser",
            "net.activitywatch.android",
            "org.example.browser",
            "org.example.other",
        )
        assertEquals(
            setOf("org.example.browser", "org.example.other"),
            selectDetectedBrowsers(resolved, setOf("com.android.chrome"), "net.activitywatch.android"),
        )
    }

    @Test
    fun `nothing resolved means nothing detected`() {
        assertEquals(emptySet<String>(), selectDetectedBrowsers(emptyList(), setOf("a.b"), "c.d"))
    }
}
