package net.activitywatch.android

import android.app.Service
import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundServicePolicyTest {
    @Test
    fun missingOriginIdentifiesSystemRestart() {
        assertEquals(
            BackgroundService.START_ORIGIN_SYSTEM_RESTART,
            backgroundServiceStartOrigin(null),
        )
    }

    @Test
    fun explicitOriginIsPreserved() {
        assertEquals(
            BackgroundService.START_ORIGIN_BOOT,
            backgroundServiceStartOrigin(BackgroundService.START_ORIGIN_BOOT),
        )
    }

    @Test
    fun serviceDoesNotRequestUnsafeStickyRestart() {
        assertEquals(Service.START_NOT_STICKY, BACKGROUND_SERVICE_RESTART_MODE)
    }
}
