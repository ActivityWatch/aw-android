package net.activitywatch.android

import net.activitywatch.android.fragments.resolveEmbeddedExportUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ServerPortTest {
    @Test
    fun mainProfileKeepsTheBasePort() {
        // user 0, app id 10123
        assertEquals(5600, ServerPort.forUid(10_123, basePort = 5600))
        assertEquals(5667, ServerPort.forUid(10_123, basePort = 5667))
    }

    @Test
    fun workProfileGetsItsOwnPort() {
        // user 10 (a typical managed/work profile), same app id
        assertEquals(11600, ServerPort.forUid(1_010_123, basePort = 5600))
        assertEquals(11700, ServerPort.forUid(1_110_123, basePort = 5600))
    }

    @Test
    fun offsetAppliesToTheNightlyFlavorToo() {
        assertEquals(11667, ServerPort.forUid(1_010_123, basePort = 5667))
    }

    @Test
    fun profilePortsAreSafeForBrowserDashboards() {
        // Chromium/Fetch restricted ports at or above our lowest base port.
        // In particular, the old stride assigned standard user 4 to X11's
        // 6000 and research user 10 to IRC's 6667 (ERR_UNSAFE_PORT).
        val blocked = setOf(6000, 6566, 6665, 6666, 6667, 6668, 6669, 6697, 10080)
        for (base in listOf(5600, 5667)) {
            for (user in 0..600) {
                val port = ServerPort.forUid(user * 100_000 + 10_123, basePort = base)
                assertFalse("base=$base user=$user port=$port", port in blocked)
            }
        }
    }

    @Test
    fun flavorsNeverShareAPortForAnyUser() {
        // An additive `+ userId` offset put standard user 67 on research's 5667,
        // so whichever install started second found its port in use.
        // 0..548 is every user both flavors can place inside the port range.
        val users = 0..548
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
        assertEquals(65500, ServerPort.forUid(549 * 100_000 + 10_123, basePort = 5600))
        assertEquals(5600, ServerPort.forUid(550 * 100_000 + 10_123, basePort = 5600))
        assertEquals(5667, ServerPort.forUid(549 * 100_000 + 10_123, basePort = 5667))
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
