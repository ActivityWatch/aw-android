package net.activitywatch.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

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

    @Test
    fun bugReportUrl_pointsAtNewIssueFormWithSingleBodyParam() {
        val url = bugReportUrl(appVersion = "0.12.3", androidVersion = "14", apiLevel = 34)

        assertTrue(url.startsWith("$BUG_REPORT_ISSUE_URL?body="))
        assertFalse(url.substringAfter("?body=").contains("&"))
    }

    @Test
    fun bugReportUrl_bodyDecodesToOutlineWithEnvironmentDetails() {
        val url = bugReportUrl(appVersion = "0.12.3", androidVersion = "14", apiLevel = 34)
        val body = URLDecoder.decode(url.substringAfter("?body="), StandardCharsets.UTF_8.name())

        assertTrue(body.startsWith("## Description\n"))
        assertTrue(body.contains("## Steps to reproduce\n"))
        assertTrue(body.contains("## Expected behavior\n"))
        assertTrue(body.endsWith("## Environment\n\n- App version: 0.12.3\n- Android version: 14 (API 34)"))
    }

    @Test
    fun bugReportUrl_encodesReservedCharactersInVersions() {
        val url = bugReportUrl(appVersion = "1.0-rc&1 #2", androidVersion = "?", apiLevel = 1)
        val encoded = url.substringAfter("?body=")
        val body = URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())

        assertFalse(encoded.contains("&"))
        assertFalse(encoded.contains("#"))
        assertFalse(encoded.contains("?"))
        assertTrue(body.contains("- App version: 1.0-rc&1 #2\n- Android version: ? (API 1)"))
    }
}
