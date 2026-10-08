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
}
