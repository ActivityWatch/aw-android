package net.activitywatch.android

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceHostnameTest {
    @Test
    fun sanitizeDeviceHostname_fallsBackToUnknown() {
        assertEquals("unknown", sanitizeDeviceHostname(null))
        assertEquals("unknown", sanitizeDeviceHostname(""))
        assertEquals("unknown", sanitizeDeviceHostname("   "))
        assertEquals("unknown", sanitizeDeviceHostname("***"))
    }

    @Test
    fun sanitizeDeviceHostname_lowercasesAndCollapses() {
        assertEquals("pixel_8", sanitizeDeviceHostname("Pixel 8"))
        assertEquals("my-phone_1", sanitizeDeviceHostname("My-Phone_1"))
        assertEquals("pixel_8", sanitizeDeviceHostname("  Pixel  8  "))
    }
}
