package net.activitywatch.android

import net.activitywatch.android.fragments.resolveEmbeddedExportUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerPortTest {
    @Test
    fun mainProfileKeepsTheBasePort() {
        // user 0, app id 10123
        assertEquals(5600, ServerPort.forUid(10_123, basePort = 5600))
    }

    @Test
    fun workProfileGetsItsOwnPort() {
        // user 10 (a typical managed/work profile), same app id
        assertEquals(6600, ServerPort.forUid(1_010_123, basePort = 5600))
        assertEquals(6700, ServerPort.forUid(1_110_123, basePort = 5600))
    }

    @Test
    fun offsetAppliesToTheNightlyFlavorToo() {
        assertEquals(6667, ServerPort.forUid(1_010_123, basePort = 5667))
    }

    @Test
    fun flavorsNeverShareAPortForAnyUser() {
        // An additive `+ userId` offset put standard user 67 on research's 5667,
        // so whichever install started second found its port in use.
        // 0..598 is every user both flavors can place inside the port range.
        val users = 0..598
        val standard = users.map { ServerPort.forUid(it * 100_000 + 10_123, basePort = 5600) }
        val research = users.map { ServerPort.forUid(it * 100_000 + 10_123, basePort = 5667) }
        assertEquals(users.count(), standard.toSet().size)
        assertEquals(users.count(), research.toSet().size)
        assertEquals(emptySet<Int>(), standard.toSet() intersect research.toSet())
        assertEquals(65_400, standard.last())
        assertEquals(65_467, research.last())
    }

    @Test
    fun userBeyondThePortRangeFallsBackToTheBasePort() {
        assertEquals(5600, ServerPort.forUid(600 * 100_000 + 10_123, basePort = 5600))
        assertEquals(5667, ServerPort.forUid(599 * 100_000 + 10_123, basePort = 5667))
    }

    @Test
    fun unresolvedPortIsTheBuildPortAndBaseUrlFollowsIt() {
        assertEquals(BuildConfig.SERVER_PORT, ServerPort.value)
        assertEquals("http://127.0.0.1:${BuildConfig.SERVER_PORT}", baseURL)
    }

    @Test
    fun workProfileBaseAndExportUrlsFollowTheResolvedPort() {
        // Exercise the process-initialized value without calling Android's Process
        // stub in a plain JVM test. Restore it even if an assertion fails.
        val portField = ServerPort::class.java.getDeclaredField("value").apply {
            isAccessible = true
        }
        val originalPort = ServerPort.value
        try {
            val workPort = ServerPort.forUid(1_010_123)
            portField.setInt(null, workPort)
            assertEquals("http://127.0.0.1:$workPort", baseURL)
            assertEquals(
                "http://127.0.0.1:$workPort/api/0/export",
                resolveEmbeddedExportUrl("/api/0/export"),
            )
        } finally {
            portField.setInt(null, originalPort)
        }
    }

    @Test
    fun exportUrlDefaultBaseFollowsTheServerPort() {
        assertEquals(
            "http://127.0.0.1:${ServerPort.value}/api/0/export",
            resolveEmbeddedExportUrl("/api/0/export"),
        )
    }
}
