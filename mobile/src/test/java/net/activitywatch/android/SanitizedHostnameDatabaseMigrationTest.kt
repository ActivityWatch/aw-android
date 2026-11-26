package net.activitywatch.android

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SanitizedHostnameDatabaseMigrationTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun unknownHostnameUpgrade_rewritesLocalBucketsButPreservesSyncedPeers() {
        val file = File(temp.root, "sqlite.db")
        val local = "aw-watcher-android_unknown"
        val synced = "aw-watcher-android_unknown-synced-from-peer"
        val unrelated = "aw-watcher-android_other"
        // aw-datastore's id is a numeric row key; the public bucket ID is name.
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE buckets (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "name TEXT UNIQUE NOT NULL, type TEXT NOT NULL, client TEXT NOT NULL, " +
                    "hostname TEXT NOT NULL, created TEXT NOT NULL, " +
                    "data_deprecated TEXT DEFAULT '{}', data TEXT NOT NULL DEFAULT '{}')"
            )
            val buckets = listOf(local to "unknown", synced to "unknown", unrelated to "other")
            for ((name, hostname) in buckets) {
                db.execSQL(
                    "INSERT INTO buckets(name, type, client, hostname, created) " +
                        "VALUES (?, 'currentwindow', 'aw-watcher-android', ?, '2024-01-01T00:00:00Z')",
                    arrayOf(name, hostname),
                )
            }
        }

        val current = resolveDeviceHostname("小米手机", "cepheus")
        val legacy = SanitizedHostnameMigration.legacyHostnames(current, "小米手机", "Mi 9")
        assertEquals(
            1,
            SanitizedHostnameMigration.rewriteBucketHostnamesInDatabase(file, current, legacy),
        )
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT name, hostname FROM buckets ORDER BY id", null).use { cursor ->
                val hostnames = linkedMapOf<String, String>()
                while (cursor.moveToNext()) {
                    hostnames[cursor.getString(0)] = cursor.getString(1)
                }
                assertEquals(
                    mapOf(local to "cepheus", synced to "unknown", unrelated to "other"),
                    hostnames,
                )
            }
        }
        assertEquals(
            0,
            SanitizedHostnameMigration.rewriteBucketHostnamesInDatabase(file, current, legacy),
        )
    }
}
