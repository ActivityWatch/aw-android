package net.activitywatch.android

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun captureExistingData_failedSaveReturnsSignalWithoutThrowing() {
        val app = RuntimeEnvironment.getApplication()
        val shared = freshPrefs(app)
        File(app.filesDir, "sqlite.db").writeBytes(ByteArray(4096))
        val context = failingCommitContext(app, shared) {}

        val unsaved = DatastoreStartup.captureExistingData(context, AWPreferences(context))

        assertEquals(true, unsaved)
        assertFalse(shared.contains("notifyOptInExistingData"))
    }

    @Test
    fun start_failedCaptureStillReleasesDatastoreAndWritesOffCallerThread() {
        val app = RuntimeEnvironment.getApplication()
        val shared = freshPrefs(app)
        File(app.filesDir, "sqlite.db").delete()
        var writeThread: Thread? = null
        val context = failingCommitContext(app, shared) { writeThread = Thread.currentThread() }
        // A fresh gate, not the process-wide DatastoreStartup, so no state leaks between tests.
        val gate = DatastoreStartupGate()

        gate.start(context)
        gate.awaitReady()

        assertTrue(writeThread != null && writeThread !== Thread.currentThread())
        assertFalse(shared.contains("notifyOptInExistingData"))
        // The migration in this process still sees the fresh-install signal.
        assertEquals(false, gate.notifyOptInExistingData(AWPreferences(context)))
        // The hostname rewrite still ran after the failed save.
        assertTrue(shared.contains("sanitizedHostnameMigratedTo"))
    }

    @Test
    fun notifyOptInExistingData_savedValueWinsOverUnsaved() {
        val app = RuntimeEnvironment.getApplication()
        val shared = freshPrefs(app)
        File(app.filesDir, "sqlite.db").delete()
        val gate = DatastoreStartupGate()
        // The save fails, so the gate keeps `false` in memory only.
        gate.start(failingCommitContext(app, shared) {})
        gate.awaitReady()
        val prefs = AWPreferences(app)
        assertEquals(false, gate.notifyOptInExistingData(prefs))

        // A later successful save of a conflicting value must win over the in-memory one.
        prefs.setNotifyOptInExistingData(true)
        assertEquals(true, gate.notifyOptInExistingData(prefs))
    }

    private fun freshPrefs(app: Context): SharedPreferences =
        app.getSharedPreferences(AWPreferences.PREFERENCES_NAME, Context.MODE_PRIVATE).also {
            it.edit().clear().commit()
        }

    /** A context whose preferences' commit() always fails; apply() still writes. */
    private fun failingCommitContext(
        app: Application,
        shared: SharedPreferences,
        onCommit: () -> Unit,
    ): Context {
        val failing = object : SharedPreferences by shared {
            override fun edit(): SharedPreferences.Editor {
                val editor = shared.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                        editor.putBoolean(key, value)
                        return this
                    }
                    override fun putString(key: String, value: String?): SharedPreferences.Editor {
                        editor.putString(key, value)
                        return this
                    }
                    override fun commit(): Boolean {
                        onCommit()
                        return false
                    }
                }
            }
        }
        return object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = failing
        }
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
