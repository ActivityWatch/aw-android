package net.activitywatch.android.watcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val PKG = "org.example.browser"

class BrowserProbeMemoryTest {

    private var saved: String? = null
    private val memory = BrowserProbeMemory(null) { saved = it }
    private val probed = mutableListOf<UrlBarStyle>()

    private fun probe(result: UrlBarStyle?): (UrlBarStyle) -> String? = { style ->
        probed.add(style)
        if (style == result) "example.org" else null
    }

    @Test
    fun `unknown package tries Chromium then Gecko and remembers what worked`() {
        assertEquals("example.org", memory.extract(PKG, 1, 0, probe(UrlBarStyle.GECKO)))
        assertEquals(listOf(UrlBarStyle.CHROMIUM, UrlBarStyle.GECKO), probed)
        probed.clear()
        memory.extract(PKG, 1, 10, probe(UrlBarStyle.GECKO))
        assertEquals(listOf(UrlBarStyle.GECKO), probed)
    }

    @Test
    fun `a known browser is never backed off`() {
        memory.extract(PKG, 1, 0, probe(UrlBarStyle.CHROMIUM))
        repeat(100) { i ->
            assertTrue(memory.isActive(PKG, 1, i.toLong()))
            assertNull(memory.extract(PKG, 1, i.toLong(), probe(null)))
        }
        assertEquals(101, probed.size)
    }

    @Test
    fun `failures back off exponentially up to 30 minutes`() {
        var now = 0L
        val delays = mutableListOf<Long>()
        repeat(15) {
            assertTrue(memory.isActive(PKG, 1, now))
            assertNull(memory.extract(PKG, 1, now, probe(null)))
            var next = now + 1
            while (!memory.isActive(PKG, 1, next)) next += 1_000 - next % 1_000
            delays.add(next - now)
            now = next
        }
        val expected = (0 until 15).map { minOf(1_000L shl it, PROBE_BACKOFF_MAX_MS) }
        assertEquals(expected, delays)
    }

    @Test
    fun `skipped events do not probe or count as failures`() {
        memory.extract(PKG, 1, 0, probe(null))
        probed.clear()
        assertFalse(memory.isActive(PKG, 1, 500))
        assertNull(memory.extract(PKG, 1, 500, probe(UrlBarStyle.CHROMIUM)))
        assertTrue(probed.isEmpty())
        memory.extract(PKG, 1, 1_000, probe(null))
        assertFalse(memory.isActive(PKG, 1, 2_999))
        assertTrue(memory.isActive(PKG, 1, 3_000))
    }

    @Test
    fun `success after failures ends the backoff`() {
        memory.extract(PKG, 1, 0, probe(null))
        memory.extract(PKG, 1, 1_000, probe(UrlBarStyle.CHROMIUM))
        memory.extract(PKG, 1, 1_001, probe(null))
        assertTrue(memory.isActive(PKG, 1, 1_002))
    }

    @Test
    fun `a new app version is probed from scratch`() {
        memory.extract(PKG, 1, 0, probe(UrlBarStyle.GECKO))
        repeat(3) { memory.extract(PKG, 2, 0, probe(null)) }
        assertFalse(memory.isActive(PKG, 2, 1))
        probed.clear()
        memory.extract(PKG, 3, 2, probe(UrlBarStyle.CHROMIUM))
        assertEquals(listOf(UrlBarStyle.CHROMIUM), probed)
    }

    @Test
    fun `state survives a restart`() {
        memory.extract(PKG, 1, 0, probe(null))
        memory.extract("org.example.gecko", 7, 0, probe(UrlBarStyle.GECKO))
        val restored = BrowserProbeMemory(saved) {}
        assertFalse(restored.isActive(PKG, 1, 999))
        assertTrue(restored.isActive(PKG, 1, 1_000))
        probed.clear()
        restored.extract("org.example.gecko", 7, 5, probe(UrlBarStyle.GECKO))
        assertEquals(listOf(UrlBarStyle.GECKO), probed)
    }

    @Test
    fun `a clock stepped backwards does not freeze the backoff`() {
        memory.extract(PKG, 1, 1_000_000_000, probe(null))
        assertFalse(memory.isActive(PKG, 1, 1_000_000_500))
        assertTrue(memory.isActive(PKG, 1, 0))
    }

    @Test
    fun `forgets packages that are no longer detected`() {
        memory.extract(PKG, 1, 0, probe(null))
        memory.retain(emptySet())
        assertTrue(memory.isActive(PKG, 1, 1))
        assertEquals("{}", saved)
    }

    @Test
    fun `corrupt saved state is ignored`() {
        assertTrue(BrowserProbeMemory("not json") {}.isActive(PKG, 1, 0))
        assertTrue(BrowserProbeMemory("""{"$PKG":{"v":"x"}}""") {}.isActive(PKG, 1, 0))
    }
}
