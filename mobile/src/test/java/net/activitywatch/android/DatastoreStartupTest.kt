package net.activitywatch.android

import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DatastoreStartupTest {
    private lateinit var dir: File
    private lateinit var db: File

    @Before
    fun setUp() {
        dir = createTempDir()
        db = File(dir, "sqlite.db")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun existingDataSignal_missingDatastoreIsFreshInstall() {
        assertFalse(existingDataSignal(db))
    }

    @Test
    fun existingDataSignal_emptyDatastoreIsFreshInstall() {
        db.createNewFile()
        File(dir, "sqlite.db-wal").createNewFile()
        assertFalse(existingDataSignal(db))
    }

    @Test
    fun existingDataSignal_nonEmptyDatastoreIsExistingData() {
        db.writeBytes(ByteArray(4096))
        assertTrue(existingDataSignal(db))
    }

    @Test
    fun existingDataSignal_nonEmptyWalIsExistingData() {
        db.createNewFile()
        File(dir, "sqlite.db-wal").writeBytes(ByteArray(32))
        assertTrue(existingDataSignal(db))
    }

    @Test
    fun existingDataSignal_directoryIsNotData() {
        db.mkdir()
        assertFalse(existingDataSignal(db))
    }
}
