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
    fun matchChildren_reservesExactMatchesRegardlessOfSourceOrder() {
        for (sources in listOf(listOf("pixel8", "PIXEL8"), listOf("PIXEL8", "pixel8"))) {
            assertEquals(
                mapOf("PIXEL8" to "PIXEL8"),
                SafMirrorCleanup.matchChildren(sources, listOf("PIXEL8")),
            )
        }
        assertEquals(
            mapOf("pixel8" to "pixel8", "PIXEL8" to "PIXEL8"),
            SafMirrorCleanup.matchChildren(listOf("pixel8", "PIXEL8"), listOf("PIXEL8", "pixel8")),
        )
    }

    @Test
    fun matchChildren_assignsEachFallbackAtMostOnce() {
        val matches = SafMirrorCleanup.matchChildren(listOf("pixel8", "Pixel8"), listOf("PIXEL8"))
        assertEquals(1, matches.size)
        assertEquals(listOf("PIXEL8"), matches.values.toList())
        assertEquals(
            mapOf("pixel8" to "PIXEL8"),
            SafMirrorCleanup.matchChildren(listOf("pixel8"), listOf("PIXEL8")),
        )
        assertEquals(
            emptyMap<String, String>(),
            SafMirrorCleanup.matchChildren(listOf("pixel8"), listOf("PIXEL8", "Pixel8")),
        )
    }

    @Test
    fun matchChildren_doesNotUseFilesAsCaseOnlyDirectoryMatches() {
        assertEquals(
            emptyMap<String, String>(),
            SafMirrorCleanup.matchChildren(
                listOf("pixel8"), listOf("PIXEL8"), emptySet(),
            ),
        )
        // Exact conflicts remain reserved and are rejected by the mirror.
        assertEquals(
            mapOf("pixel8" to "pixel8"),
            SafMirrorCleanup.matchChildren(
                listOf("pixel8"), listOf("pixel8", "PIXEL8"), setOf("PIXEL8"),
            ),
        )
        assertEquals(
            mapOf("pixel8" to "PIXEL8"),
            SafMirrorCleanup.matchChildren(
                listOf("pixel8"), listOf("PIXEL8"), setOf("PIXEL8"),
            ),
        )
    }

    @Test
    fun matchChildren_reservesExactFilesBeforeDirectoryFallbacks() {
        assertEquals(
            mapOf("PIXEL8" to "PIXEL8"),
            SafMirrorCleanup.matchChildren(
                listOf("pixel8", "PIXEL8"), listOf("PIXEL8"), setOf("PIXEL8"), setOf("pixel8"),
            ),
        )
        assertEquals(
            emptyMap<String, String>(),
            SafMirrorCleanup.matchChildren(
                listOf("pixel8"), listOf("PIXEL8"), setOf("PIXEL8"), emptySet(),
            ),
        )
    }

    @Test
    fun staleSafHostnames_keepsOnlyActualMirrorDestination() {
        // When both case variants exist, only the one actually written is protected.
        assertEquals(
            listOf("PIXEL8"),
            SafMirrorCleanup.staleSafHostnames(listOf("PIXEL8"), "pixel8"),
        )
        assertEquals(
            listOf("Pixel 8", "unknown"),
            SafMirrorCleanup.staleSafHostnames(listOf("PIXEL8", "Pixel 8", "unknown"), "PIXEL8"),
        )
    }
}

