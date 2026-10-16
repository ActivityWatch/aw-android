package net.activitywatch.android

import java.io.Closeable
import java.net.BindException
import java.net.SocketException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerPortProbeTest {
    private val ignore: (Exception) -> Unit = {}

    @Test
    fun freePortIsReportedAndClosed() {
        var closed = false
        val result = probeServerPort(5600, open = { Closeable { closed = true } }, onDenied = ignore)
        assertEquals(PortProbe.FREE, result)
        assertTrue(closed)
    }

    @Test
    fun portHeldByAnotherProcessIsInUse() {
        val result =
            probeServerPort(5600, open = { throw BindException("Address already in use") }, onDenied = ignore)
        assertEquals(PortProbe.IN_USE, result)
    }

    @Test
    fun socketCreationDeniedDoesNotEscape() {
        // GrapheneOS with the Network permission off: socket() fails with EACCES,
        // surfaced as a SocketException that is not a BindException.
        val result =
            probeServerPort(
                5600,
                open = { throw SocketException("socket failed: EACCES (Permission denied)") },
                onDenied = ignore,
            )
        assertEquals(PortProbe.SOCKET_DENIED, result)
    }

    @Test
    fun securityExceptionIsTreatedAsDenied() {
        val result = probeServerPort(5600, open = { throw SecurityException("denied") }, onDenied = ignore)
        assertEquals(PortProbe.SOCKET_DENIED, result)
    }

    @Test
    fun realServerSocketOnTakenPortIsInUse() {
        java.net.ServerSocket(0).use { held ->
            assertEquals(PortProbe.IN_USE, probeServerPort(held.localPort, onDenied = ignore))
        }
    }
}
