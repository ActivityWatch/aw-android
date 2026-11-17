package net.activitywatch.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SafMirrorTest {
    private val written = SafMirrorStamp(sourceLength = 4096, sourceModified = 1_000, destLength = 4096, destModified = 2_000)

    @Test
    fun copyUntouchedSinceTheAppWroteItIsUpToDate() {
        assertTrue(safMirrorIsUpToDate(written, written.copy()))
    }

    @Test
    fun sourceChangedSinceTheCopyIsCopiedAgain() {
        // Same size: SQLite rewrites pages in place, so length alone can't tell.
        assertFalse(safMirrorIsUpToDate(written, written.copy(sourceModified = 3_000)))
    }

    @Test
    fun copyReplacedByAnotherToolIsCopiedAgain() {
        // Same size and a newer timestamp than the source, but not the file this app wrote.
        assertFalse(safMirrorIsUpToDate(written, written.copy(destModified = 5_000)))
    }

    @Test
    fun fileNeverWrittenByTheAppIsCopied() {
        assertFalse(safMirrorIsUpToDate(null, written))
    }

    @Test
    fun manifestIsOnlyReusedForTheSameFolder() {
        val file = File.createTempFile("saf-manifest", ".json").apply { deleteOnExit() }
        val stamps = mapOf("host/device/test.db" to written)
        saveSafMirrorManifest(file, "content://tree/a", stamps)

        assertEquals(stamps, loadSafMirrorManifest(file, "content://tree/a"))
        assertEquals(emptyMap<String, SafMirrorStamp>(), loadSafMirrorManifest(file, "content://tree/b"))
    }
}
