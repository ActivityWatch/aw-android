package net.activitywatch.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        assertEquals("poco_f8_ultra", sanitizeDeviceHostname("POCO F8 Ultra"))
    }

    // ActivityWatch/aw-android#402: a device name with no Latin letters or digits
    // sanitizes to the "unknown" sentinel; fall back to Build.DEVICE instead.
    @Test
    fun resolveDeviceHostname_fallsBackToBuildDeviceWhenNameSanitizesToUnknown() {
        assertEquals("cepheus", resolveDeviceHostname("小米手机", "cepheus"))
        assertEquals("husky", resolveDeviceHostname("Телефон", "husky"))
        assertEquals("shiba", resolveDeviceHostname("📱", "shiba"))
        assertEquals("shiba", resolveDeviceHostname(null, "shiba"))
    }

    @Test
    fun resolveDeviceHostname_keepsSanitizableNameAndUnknownAsLastResort() {
        assertEquals("pixel_8", resolveDeviceHostname("Pixel 8", "shiba"))
        assertEquals("unknown", resolveDeviceHostname("小米手机", null))
        assertEquals("unknown", resolveDeviceHostname("📱", "📱"))
    }

    @Test
    fun legacyHostnames_carryTheOldUnknownSentinelAfterTheFallback() {
        // Devices that were stored as "unknown" before the fallback must have it
        // as a legacy candidate, so buckets and the sync folder migrate.
        val legacy = SanitizedHostnameMigration.legacyHostnames("cepheus", "小米手机", "MI 9")
        assertTrue("unknown" in legacy)
    }
}
