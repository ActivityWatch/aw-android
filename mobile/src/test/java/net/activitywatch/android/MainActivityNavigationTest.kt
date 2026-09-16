package net.activitywatch.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityNavigationTest {
    @Test
    fun initialWebUiUrl_defaultsToDashboardHome() {
        assertEquals(
            baseURL,
            initialWebUiUrl(openActivityView = false, hostname = "pixel_8"),
        )
    }

    @Test
    fun initialWebUiUrl_opensActivityNavDestinationForDeviceHost() {
        assertEquals(
            "$baseURL/#/activity/pixel_8/",
            initialWebUiUrl(openActivityView = true, hostname = "pixel_8"),
        )
    }

    @Test
    fun activityViewUrl_sanitizesRawDeviceName() {
        assertEquals(
            "$baseURL/#/activity/pixel_8/",
            activityViewUrl("Pixel 8"),
        )
    }

    @Test
    fun activityViewUrl_unknownIsFallbackNotDefault() {
        assertEquals(
            "$baseURL/#/activity/unknown/",
            activityViewUrl("unknown"),
        )
        assertEquals(
            "$baseURL/#/activity/unknown/",
            activityViewUrl(""),
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

    @Test
    fun nativeHomeResetsChrome_webUiKeepsPageReports() {
        assertTrue(shouldResetChromeForNativeDestination(isWebUiDestination = false))
        assertFalse(shouldResetChromeForNativeDestination(isWebUiDestination = true))
    }

    @Test
    fun configChangeKeepsPageReportedWebUiChrome() {
        assertEquals(
            null,
            chromeOnConfigurationChange(
                showingWebUi = true,
                webUiSchemeFromPage = true,
                systemNight = false,
            ),
        )
    }

    @Test
    fun configChangeAppliesSystemNightUntilPageReports() {
        assertEquals(
            true,
            chromeOnConfigurationChange(
                showingWebUi = true,
                webUiSchemeFromPage = false,
                systemNight = true,
            ),
        )
        assertEquals(
            false,
            chromeOnConfigurationChange(
                showingWebUi = true,
                webUiSchemeFromPage = false,
                systemNight = false,
            ),
        )
    }

    @Test
    fun configChangeKeepsNativeHomeLightEvenIfSystemIsNight() {
        assertEquals(
            false,
            chromeOnConfigurationChange(
                showingWebUi = false,
                webUiSchemeFromPage = true,
                systemNight = true,
            ),
        )
        assertEquals(
            false,
            chromeOnConfigurationChange(
                showingWebUi = false,
                webUiSchemeFromPage = false,
                systemNight = true,
            ),
        )
    }
}
