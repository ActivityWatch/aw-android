package net.activitywatch.android

import org.json.JSONObject

/**
 * Naming and completion rules for migrating `aw-watcher-android-test*` buckets
 * onto the canonical `aw-watcher-android*` names.
 *
 * The actual event move lives in aw-server-rust (`migrate_test_bucket_names`).
 * Kotlin is responsible for invoking that JNI entry and for not marking the
 * preference complete while a legacy bucket still exists (overlap leftovers).
 */
object WatcherAndroidBucketMigration {
    const val LEGACY_PREFIX = "aw-watcher-android-test"
    const val CANONICAL_PREFIX = "aw-watcher-android"
    const val SUCCESS_PREFIX = "Migrated "

    fun isLegacyBucketId(id: String): Boolean = id.startsWith(LEGACY_PREFIX)

    fun canonicalBucketId(legacyId: String): String =
        legacyId.replaceFirst(LEGACY_PREFIX, CANONICAL_PREFIX)

    fun legacyBucketIds(buckets: JSONObject): List<String> =
        buckets.keys().asSequence().filter(::isLegacyBucketId).toList()

    fun migrationSucceeded(result: String): Boolean = result.startsWith(SUCCESS_PREFIX)

    fun shouldMarkComplete(legacyIdsRemaining: Collection<String>): Boolean =
        legacyIdsRemaining.isEmpty()
}
