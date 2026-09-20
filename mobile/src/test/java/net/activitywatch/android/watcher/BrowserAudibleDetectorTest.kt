package net.activitywatch.android.watcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserAudibleDetectorTest {

    @Test
    fun `browser media session state wins when available`() {
        assertTrue(resolveAudible(browserPlaying = true, musicActive = false))
        assertFalse(resolveAudible(browserPlaying = false, musicActive = true))
    }

    @Test
    fun `falls back to global music state without notification access`() {
        assertTrue(resolveAudible(browserPlaying = null, musicActive = true))
        assertFalse(resolveAudible(browserPlaying = null, musicActive = false))
    }
}
