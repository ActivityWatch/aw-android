package net.activitywatch.android

internal const val STARTUP_MIGRATION_RETRY_DELAY_MS = 60_000L

/** One in-flight migration run, with a cooldown before a later start may retry. */
internal class StartupMigrationRetry {
    private var queued = false
    private var nextAttemptAt = 0L

    // Called on the main thread; finished() is called by the IO coroutine.
    @Synchronized
    fun tryQueue(now: Long): Boolean {
        if (queued || now < nextAttemptAt) return false
        queued = true
        return true
    }

    @Synchronized
    fun finished(now: Long) {
        // Start the cooldown at completion, even when the run itself took a long time.
        nextAttemptAt = now + STARTUP_MIGRATION_RETRY_DELAY_MS
        queued = false
    }
}
