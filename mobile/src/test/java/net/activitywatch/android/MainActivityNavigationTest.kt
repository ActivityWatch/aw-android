package net.activitywatch.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityNavigationTest {
    @Test
    fun initialWebUiUrl_defaultsToDashboardHome() {
        assertEquals(baseURL, initialWebUiUrl(openActivityView = false))
    }

    @Test
    fun initialWebUiUrl_opensActivityNavDestination() {
        assertEquals(
            "$baseURL/#/activity/unknown/",
            initialWebUiUrl(openActivityView = true),
        )
    }

    @Test
    fun notificationIntent_navigatesImmediatelyWhenActivityIsResumed() {
        assertTrue(
            shouldOpenActivityViewImmediately(openActivityView = true, isResumed = true),
        )
    }

    @Test
    fun notificationIntent_defersNavigationWhenActivityIsStopped() {
        assertFalse(
            shouldOpenActivityViewImmediately(openActivityView = true, isResumed = false),
        )
    }
}
