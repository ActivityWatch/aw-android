package net.activitywatch.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupMigrationRetryTest {
    @Test
    fun firstStartCanQueueImmediately() {
        assertTrue(StartupMigrationRetry().tryQueue(0))
    }

    @Test
    fun overlappingStartsCannotQueueEvenAfterCooldownTime() {
        val retry = StartupMigrationRetry()
        assertTrue(retry.tryQueue(0))
        assertFalse(retry.tryQueue(STARTUP_MIGRATION_RETRY_DELAY_MS * 2))
    }

    @Test
    fun finishedAttemptDoesNotRetryOnEveryStart() {
        val retry = StartupMigrationRetry()
        assertTrue(retry.tryQueue(0))
        retry.finished(10)
        assertFalse(retry.tryQueue(10))
        assertFalse(retry.tryQueue(10 + STARTUP_MIGRATION_RETRY_DELAY_MS - 1))
        assertTrue(retry.tryQueue(10 + STARTUP_MIGRATION_RETRY_DELAY_MS))
    }

    @Test
    fun cooldownStartsAtCompletionNotAtQueueTime() {
        val retry = StartupMigrationRetry()
        assertTrue(retry.tryQueue(0))
        val finish = STARTUP_MIGRATION_RETRY_DELAY_MS * 2
        retry.finished(finish)
        assertFalse(retry.tryQueue(finish))
        assertTrue(retry.tryQueue(finish + STARTUP_MIGRATION_RETRY_DELAY_MS))
    }

    @Test
    fun repeatedFailuresEachGetACooldown() {
        val retry = StartupMigrationRetry()
        assertTrue(retry.tryQueue(0))
        retry.finished(0)
        val next = STARTUP_MIGRATION_RETRY_DELAY_MS
        assertTrue(retry.tryQueue(next))
        retry.finished(next)
        assertFalse(retry.tryQueue(next + 1))
        assertTrue(retry.tryQueue(next * 2))
    }

    @Test
    fun newServiceDoesNotInheritPreviousCooldown() {
        val oldService = StartupMigrationRetry()
        assertTrue(oldService.tryQueue(0))
        oldService.finished(0)
        assertTrue(StartupMigrationRetry().tryQueue(1))
    }
}
