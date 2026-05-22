package net.activitywatch.android.fragments

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebUIFragmentTest {
    @Test
    fun `treats local embedded server hosts as internal`() {
        // http variants
        assertTrue(isEmbeddedActivityWatchUrl("http://127.0.0.1:5600/#/settings/"))
        assertTrue(isEmbeddedActivityWatchUrl("http://localhost:5600/#/settings/"))
        assertTrue(isEmbeddedActivityWatchUrl("http://[::1]:5600/#/settings/"))
        // https variants (function explicitly allows both schemes)
        assertTrue(isEmbeddedActivityWatchUrl("https://127.0.0.1:5600/"))
        assertTrue(isEmbeddedActivityWatchUrl("https://localhost:5600/"))
        assertTrue(isEmbeddedActivityWatchUrl("https://[::1]:5600/"))
        // no-port case
        assertTrue(isEmbeddedActivityWatchUrl("http://localhost/"))
    }

    @Test
    fun `treats non-loopback hosts as external`() {
        assertFalse(isEmbeddedActivityWatchUrl("https://activitywatch.net"))
        assertFalse(isEmbeddedActivityWatchUrl("http://192.168.1.10:5600"))
        assertFalse(isEmbeddedActivityWatchUrl("not a url"))
    }
}
