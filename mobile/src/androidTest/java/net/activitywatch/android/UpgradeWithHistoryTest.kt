package net.activitywatch.android

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "UpgradeWithHistoryTest"

// A couple of years of five-minute events, split the way an upgrading install
// has them: most in the legacy bucket an older release wrote to, the rest in the
// bucket the current release created, plus one heartbeat straddling the cutover.
internal const val LEGACY_EVENTS = 100_000
internal const val DESTINATION_EVENTS = 10_000
private const val EVENT_NS = 300L * 1_000_000_000L

private const val SERVER_READY_TIMEOUT_MS = 20_000L
private const val MIGRATION_TIMEOUT_MS = 60_000L

/**
 * Regression test for ActivityWatch/aw-android#261: startup work that scales with
 * the amount of stored history must not take the local server down with it.
 *
 * Every other instrumented test starts from a fresh install, so the legacy-bucket
 * migration path (both buckets present, six-figure history) never ran in CI. This
 * test seeds that state before the first datastore open, launches the app and
 * asserts that the API answers promptly and the migration completes.
 *
 * Must run in a fresh instrumentation process (see `make test-e2e-upgrade`): the
 * native datastore is opened once per process and would not see a database file
 * swapped underneath it.
 */
@RunWith(AndroidJUnit4::class)
class UpgradeWithHistoryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val packageName = instrumentation.targetContext.packageName
    private var originalUsageAccessMode: String? = null

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)
        ).bufferedReader().use { it.readText() }

    @Before
    fun grantUsageAccess() {
        // PACKAGE_USAGE_STATS is AppOps-controlled; GrantPermissionRule cannot
        // enable it on a fresh CI emulator. Without this, MainActivity redirects
        // to onboarding and the server never starts.
        originalUsageAccessMode = Regex("GET_USAGE_STATS: (\\w+)")
            .find(shell("appops get $packageName GET_USAGE_STATS"))
            ?.groupValues?.get(1) ?: "default"
        shell("appops set $packageName GET_USAGE_STATS allow")
    }

    @After
    fun restoreUsageAccess() {
        originalUsageAccessMode?.let {
            shell("appops set $packageName GET_USAGE_STATS $it")
        }
    }

    @Test
    fun serverAnswersWhileLegacyHistoryMigrates() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeFalse(
            "needs a fresh process: the native datastore is already open",
            RustInterface.serverStarted,
        )
        val hostname = deviceHostname(context)
        val legacyBucket = "aw-watcher-android-test_$hostname"
        val destinationBucket = "aw-watcher-android_$hostname"

        seedDatabase(File(context.filesDir, "sqlite.db"), legacyBucket, destinationBucket, hostname)
        context.getSharedPreferences(AWPreferences.PREFERENCES_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean("isFirstTime", false)
            .putBoolean("hasMigratedHostname", true)
            // Distinct from hasMigratedHostname: without this, startup opens sqlite.db
            // from Java to rewrite bucket hostnames while the rust worker is switching
            // journal_mode to WAL. On this 17MB seed that races SQLITE_BUSY, the worker
            // panics, and events/count stays 500 for the rest of the test.
            .putString("sanitizedHostnameMigratedTo", hostname)
            .putBoolean("hasMigratedWatcherAndroidBucketNames", false)
            .putBoolean("hasRequestedNotificationPermission", true)
            .commit()
        val apiKey = ensureDashboardApiKey(context)

        val launchedAt = System.currentTimeMillis()
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        try {
            awaitUntil(SERVER_READY_TIMEOUT_MS, "server did not answer /api/0/info") {
                httpGet("$baseURL/api/0/info", apiKey)?.first == 200
            }
            Log.i(TAG, "Server answered after ${System.currentTimeMillis() - launchedAt} ms")

            val expectedDestination = (LEGACY_EVENTS - 1 + DESTINATION_EVENTS).toLong()
            awaitUntil(MIGRATION_TIMEOUT_MS, "legacy events were not merged") {
                eventCount(destinationBucket, apiKey) == expectedDestination
            }
            Log.i(TAG, "Migration finished after ${System.currentTimeMillis() - launchedAt} ms")
            // The cutover heartbeat overlaps its legacy neighbour; both stay behind by design.
            assertEquals(2L, eventCount(legacyBucket, apiKey))
        } finally {
            scenario.close()
        }
    }

    private fun eventCount(bucketId: String, apiKey: String): Long? =
        httpGet("$baseURL/api/0/buckets/$bucketId/events/count", apiKey)
            ?.takeIf { it.first == 200 }
            ?.second?.trim()?.toLongOrNull()

    private fun awaitUntil(timeoutMs: Long, failure: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(500)
        }
        throw AssertionError("$failure within ${timeoutMs} ms")
    }

    private fun httpGet(url: String, apiKey: String): Pair<Int, String>? {
        return try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 2_000
                readTimeout = 2_000
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
            try {
                val code = connection.responseCode
                val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                code to body
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Writes the aw-datastore v5 schema the same way aw-datastore creates it. */
    private fun seedDatabase(file: File, legacyBucket: String, destinationBucket: String, hostname: String) {
        File(file.path + "-wal").delete()
        File(file.path + "-shm").delete()
        file.delete()
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.execSQL(
                "CREATE TABLE buckets (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT UNIQUE NOT NULL, " +
                    "type TEXT NOT NULL, client TEXT NOT NULL, hostname TEXT NOT NULL, created TEXT NOT NULL, " +
                    "data_deprecated TEXT DEFAULT '{}', data TEXT NOT NULL DEFAULT '{}')"
            )
            db.execSQL("CREATE INDEX bucket_id_index ON buckets(id)")
            db.execSQL(
                "CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, bucketrow INTEGER NOT NULL, " +
                    "starttime INTEGER NOT NULL, endtime INTEGER NOT NULL, data TEXT NOT NULL, " +
                    "FOREIGN KEY (bucketrow) REFERENCES buckets(id))"
            )
            db.execSQL(
                "CREATE INDEX events_bucketrow_starttime_endtime_index ON events(bucketrow, starttime DESC, endtime)"
            )
            db.execSQL("CREATE TABLE key_value (key TEXT PRIMARY KEY, value TEXT, last_modified NUMBER NOT NULL)")
            db.execSQL("PRAGMA user_version = 5")

            db.execSQL(
                "INSERT INTO buckets(name, type, client, hostname, created) VALUES (?, 'currentwindow', 'aw-watcher-android', ?, '2024-01-01T00:00:00Z')",
                arrayOf(legacyBucket, hostname)
            )
            db.execSQL(
                "INSERT INTO buckets(name, type, client, hostname, created) VALUES (?, 'currentwindow', 'aw-watcher-android', ?, '2026-08-24T00:00:00Z')",
                arrayOf(destinationBucket, hostname)
            )

            val insert = db.compileStatement("INSERT INTO events(bucketrow, starttime, endtime, data) VALUES (?, ?, ?, ?)")
            try {
                val data = """{"app":"com.android.chrome","package":"com.android.chrome","classname":"x"}"""
                // 2024-01-01T00:00:00Z in nanoseconds; events are back to back and disjoint.
                val start = 1_704_067_200L * 1_000_000_000L
                db.beginTransaction()
                try {
                    for (i in 0 until LEGACY_EVENTS) {
                        val s = start + i * EVENT_NS
                        bind(insert, 1, s, s + EVENT_NS - 1, data)
                    }
                    val cutover = start + LEGACY_EVENTS * EVENT_NS
                    bind(insert, 1, cutover - EVENT_NS / 2, cutover + EVENT_NS / 2, data)
                    for (i in 0 until DESTINATION_EVENTS) {
                        val s = cutover + i * EVENT_NS
                        bind(insert, 2, s, s + EVENT_NS - 1, data)
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            } finally {
                // SQLiteStatement holds a SQLiteClosable ref on the database.
                // db.close() only drops one ref, so an unclosed statement keeps
                // the Java connection alive. rust then panics on BEGIN EXCLUSIVE
                // for the v6 index (`database is locked`) and events/count stays 500.
                insert.close()
            }
        } finally {
            db.close()
        }
        File(file.path + "-wal").delete()
        File(file.path + "-shm").delete()
        Log.i(TAG, "Seeded ${file.length()} bytes of history into ${file.path}")
    }

    private fun bind(statement: android.database.sqlite.SQLiteStatement, bucketrow: Long, start: Long, end: Long, data: String) {
        statement.clearBindings()
        statement.bindLong(1, bucketrow)
        statement.bindLong(2, start)
        statement.bindLong(3, end)
        statement.bindString(4, data)
        statement.executeInsert()
    }
}
