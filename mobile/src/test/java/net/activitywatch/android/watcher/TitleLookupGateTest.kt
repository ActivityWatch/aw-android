package net.activitywatch.android.watcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TitleLookupGateTest {
    private var clock = 10_000L
    private val gate = TitleLookupGate { clock }
    private val chrome = "com.android.chrome"

    @Test
    fun firstLookupRunsImmediately() {
        assertEquals(0L, gate.delayBeforeLookup(chrome, hasTitle = false))
    }

    @Test
    fun untitledPageIsLookedUpAgainAfterTheShortInterval() {
        gate.delayBeforeLookup(chrome, hasTitle = false)
        clock += 100
        assertEquals(TITLE_LOOKUP_MS - 100, gate.delayBeforeLookup(chrome, hasTitle = false))
        clock += TITLE_LOOKUP_MS - 100
        assertEquals(0L, gate.delayBeforeLookup(chrome, hasTitle = false))
    }

    @Test
    fun titledPageIsRecheckedOnlyAfterTheLongInterval() {
        gate.delayBeforeLookup(chrome, hasTitle = true)
        clock += TITLE_LOOKUP_MS
        assertEquals(TITLE_RECHECK_MS - TITLE_LOOKUP_MS, gate.delayBeforeLookup(chrome, hasTitle = true))
        clock += TITLE_RECHECK_MS - TITLE_LOOKUP_MS
        assertEquals(0L, gate.delayBeforeLookup(chrome, hasTitle = true))
    }

    @Test
    fun skippedLookupDoesNotPushTheNextOneBack() {
        gate.delayBeforeLookup(chrome, hasTitle = false)
        clock += 200
        gate.delayBeforeLookup(chrome, hasTitle = false) // skipped
        clock += TITLE_LOOKUP_MS - 200
        assertEquals(0L, gate.delayBeforeLookup(chrome, hasTitle = false))
    }

    @Test
    fun firefoxIsNeverLookedUp() {
        assertNull(gate.delayBeforeLookup(TitleLookupGate.FIREFOX_PACKAGE, hasTitle = false))
    }
}
