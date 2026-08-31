package net.activitywatch.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatcherAndroidBucketMigrationTest {
    @Test
    fun canonicalBucketId_stripsTestInfix() {
        assertEquals(
            "aw-watcher-android_pixel_8",
            WatcherAndroidBucketMigration.canonicalBucketId("aw-watcher-android-test_pixel_8"),
        )
    }

    @Test
    fun isLegacyBucketId_matchesPrefixOnly() {
        assertTrue(WatcherAndroidBucketMigration.isLegacyBucketId("aw-watcher-android-test_phone"))
        assertFalse(WatcherAndroidBucketMigration.isLegacyBucketId("aw-watcher-android_phone"))
        assertFalse(WatcherAndroidBucketMigration.isLegacyBucketId("aw-watcher-web"))
    }

    @Test
    fun legacyBucketIds_filtersGetBucketsPayload() {
        val buckets = JSONObject()
        buckets.put("aw-watcher-android-test_phone", JSONObject())
        buckets.put("aw-watcher-android_phone", JSONObject())
        buckets.put("aw-watcher-web", JSONObject())

        assertEquals(
            listOf("aw-watcher-android-test_phone"),
            WatcherAndroidBucketMigration.legacyBucketIds(buckets),
        )
    }

    @Test
    fun shouldMarkComplete_onlyWhenNoLegacyBucketsRemain() {
        assertTrue(WatcherAndroidBucketMigration.shouldMarkComplete(emptyList()))
        assertFalse(
            WatcherAndroidBucketMigration.shouldMarkComplete(
                listOf("aw-watcher-android-test_phone"),
            ),
        )
    }

    @Test
    fun migrationSucceeded_acceptsCountIncludingZero() {
        assertTrue(
            WatcherAndroidBucketMigration.migrationSucceeded(
                "Migrated 0 'aw-watcher-android-test' bucket(s)",
            ),
        )
        assertTrue(
            WatcherAndroidBucketMigration.migrationSucceeded(
                "Migrated 2 'aw-watcher-android-test' bucket(s)",
            ),
        )
        assertFalse(
            WatcherAndroidBucketMigration.migrationSucceeded(
                """{"error": "Failed to migrate watcher bucket names: boom"}""",
            ),
        )
    }
}
