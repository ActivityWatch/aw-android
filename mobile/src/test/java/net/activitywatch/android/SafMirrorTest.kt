package net.activitywatch.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafMirrorTest {
    @Test
    fun copyWrittenAfterTheLastChangeIsUpToDate() {
        assertTrue(safMirrorIsUpToDate(4096, sourceModified = 1_000, destLength = 4096, destModified = 2_000))
    }

    @Test
    fun sourceChangedSinceTheCopyIsCopiedAgain() {
        // Same size: SQLite rewrites pages in place, so length alone can't tell.
        assertFalse(safMirrorIsUpToDate(4096, sourceModified = 3_000, destLength = 4096, destModified = 2_000))
    }

    @Test
    fun interruptedCopyIsCopiedAgain() {
        assertFalse(safMirrorIsUpToDate(4096, sourceModified = 1_000, destLength = 1024, destModified = 2_000))
    }

    @Test
    fun unknownSourceTimeIsCopiedAgain() {
        assertFalse(safMirrorIsUpToDate(4096, sourceModified = 0, destLength = 4096, destModified = 2_000))
    }
}
