package net.activitywatch.android

import org.junit.Assert.assertEquals
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
}
