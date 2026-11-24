package net.activitywatch.android

import org.junit.Assert.assertEquals
import org.junit.Test

class SafMirrorCleanupTest {
    @Test
    fun staleDatabaseFiles_removesLegacyDatabaseAndSidecars() {
        assertEquals(
            setOf("test.db", "test.db-wal", "test.db-shm"),
            SafMirrorCleanup.staleDatabaseFiles(
                localFileNames = setOf("sync.db", "sync.db-wal", "sync.db-shm"),
                safFileNames =
                    setOf(
                        "test.db",
                        "test.db-wal",
                        "test.db-shm",
                        "sync.db",
                        "sync.db-wal",
                        "sync.db-shm",
                    ),
            ),
        )
    }

    @Test
    fun staleDatabaseFiles_keepsFilesThatStillExistLocally() {
        assertEquals(
            emptySet<String>(),
            SafMirrorCleanup.staleDatabaseFiles(
                localFileNames = setOf("test.db", "test.db-wal", "sync.db"),
                safFileNames = setOf("test.db", "test.db-wal", "sync.db"),
            ),
        )
    }

    @Test
    fun staleDatabaseFiles_ignoresUnrelatedFilesAndDirectories() {
        assertEquals(
            setOf("orphan.db-journal"),
            SafMirrorCleanup.staleDatabaseFiles(
                localFileNames = emptySet(),
                safFileNames =
                    setOf(
                        "notes.txt",
                        "database-backup",
                        "peer.db.zip",
                        "orphan.db-journal",
                    ),
            ),
        )
    }

    @Test
    fun matchChildName_prefersExactName() {
        assertEquals("pixel8", SafMirrorCleanup.matchChildName(listOf("PIXEL8", "pixel8"), "pixel8"))
    }

    @Test
    fun matchChildName_reusesCaseOnlyVariant() {
        // #402: SAF still holds PIXEL8/ after the local dir became pixel8/.
        assertEquals("PIXEL8", SafMirrorCleanup.matchChildName(listOf("PIXEL8", "other"), "pixel8"))
    }

    @Test
    fun matchChildName_refusesAmbiguousOrMissing() {
        assertEquals(null, SafMirrorCleanup.matchChildName(listOf("PIXEL8", "Pixel8"), "pixel8"))
        assertEquals(null, SafMirrorCleanup.matchChildName(listOf("other"), "pixel8"))
    }

    @Test
    fun staleSafHostnames_keepsCaseOnlyVariantOfCurrent() {
        // Deleting PIXEL8/ on case-insensitive storage deletes this device's mirror.
        assertEquals(
            listOf("Pixel 8", "unknown"),
            SafMirrorCleanup.staleSafHostnames(listOf("PIXEL8", "Pixel 8", "unknown"), "pixel8"),
        )
    }
}

