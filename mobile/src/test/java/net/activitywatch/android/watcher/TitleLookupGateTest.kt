package net.activitywatch.android.watcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun earlierFollowUpReplacesAPendingRecheck() {
        // A 5s recheck for the old page must not hold back the new page's 500ms retry.
        assertFalse(keepPendingTitleFollowUp(chrome, pendingDueAt = 5_000L, chrome, dueAt = 500L))
        assertTrue(keepPendingTitleFollowUp(chrome, pendingDueAt = 500L, chrome, dueAt = 5_000L))
        assertFalse(keepPendingTitleFollowUp("com.brave.browser", 500L, chrome, dueAt = 5_000L))
    }
}
