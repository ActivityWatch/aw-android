package net.activitywatch.android

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
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
    fun captureExistingData_retryKeepsFreshInstallSignalAfterDatabaseCreated() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = AWPreferences(context)
        val datastore = File(context.filesDir, "sqlite.db")
        datastore.delete()
        context.getSharedPreferences(AWPreferences.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()

        DatastoreStartup.captureExistingData(context, prefs)
        assertEquals(false, prefs.notifyOptInExistingData())
        datastore.writeBytes(ByteArray(4096))

        // Migration is still pending, but this is our own DB, not a restored backup.
        DatastoreStartup.captureExistingData(context, AWPreferences(context))
        assertFalse(prefs.hasMigratedNotifyOptIn())
        assertEquals(false, prefs.notifyOptInExistingData())
    }

    @Test
    fun captureExistingData_restoredDatastoreIsExistingData() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = AWPreferences(context)
        context.getSharedPreferences(AWPreferences.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        prefs.setSanitizedHostnameMigratedTo(deviceHostname(context))
        File(context.filesDir, "sqlite.db").writeBytes(ByteArray(4096))

        DatastoreStartup.captureExistingData(context, prefs)

        assertEquals(true, prefs.notifyOptInExistingData())
        assertFalse(prefs.hasMigratedNotifyOptIn())
    }

    @Test
    fun start_failedCaptureDoesNotReleaseDatastoreAndWritesOffCallerThread() {
        val app = RuntimeEnvironment.getApplication()
        val shared = app.getSharedPreferences(AWPreferences.PREFERENCES_NAME, Context.MODE_PRIVATE)
        shared.edit().clear().commit()
        var writeThread: Thread? = null
        val failing = object : SharedPreferences by shared {
            override fun edit(): SharedPreferences.Editor {
                val editor = shared.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                        editor.putBoolean(key, value)
                        return this
                    }
                    override fun commit(): Boolean {
                        writeThread = Thread.currentThread()
                        return false
                    }
                }
            }
        }
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = failing
        }

        DatastoreStartup.start(context)
        assertThrows(IllegalStateException::class.java) { DatastoreStartup.awaitReady() }
        assertTrue(writeThread != null && writeThread !== Thread.currentThread())
        assertFalse(shared.contains("sanitizedHostnameMigratedTo"))
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
