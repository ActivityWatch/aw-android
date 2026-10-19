package net.activitywatch.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebUiThemeTest {
    @Test
    fun forcedDarkIsDarkRegardlessOfSystem() {
        assertTrue(webUiSchemeIsDark("dark", prefersDark = false, darkStylesheetPresent = false))
        assertTrue(webUiSchemeIsDark("DARK", prefersDark = false, darkStylesheetPresent = false))
    }

    @Test
    fun forcedLightIsLightEvenIfSystemAndStylesheetAreDark() {
        assertFalse(webUiSchemeIsDark("light", prefersDark = true, darkStylesheetPresent = true))
    }

    @Test
    fun autoFollowsStylesheetOrSystemPreference() {
        assertTrue(webUiSchemeIsDark("auto", prefersDark = true, darkStylesheetPresent = false))
        assertTrue(webUiSchemeIsDark("auto", prefersDark = false, darkStylesheetPresent = true))
        assertFalse(webUiSchemeIsDark("auto", prefersDark = false, darkStylesheetPresent = false))
        assertTrue(webUiSchemeIsDark(null, prefersDark = true, darkStylesheetPresent = false))
    }

    @Test
    fun themeHookReportsResolvedSchemeAndWatchesStorage() {
        assertTrue(ANDROID_THEME_HOOK_JS.contains("Android.reportColorScheme"))
        assertTrue(ANDROID_THEME_HOOK_JS.contains("localStorage.getItem('theme')"))
        assertTrue(ANDROID_THEME_HOOK_JS.contains("prefers-color-scheme: dark"))
        assertTrue(ANDROID_THEME_HOOK_JS.contains("dark.css"))
        assertTrue(ANDROID_THEME_HOOK_JS.contains("k === 'theme'"))
    }

    @Test
    fun themeHookTreatsAStylesheetThatFailedBeforeInjectionAsSettled() {
        // The hook runs from onPageFinished, after window load: a link already in
        // the document has already fired load/error, so a null sheet means it
        // failed and the light fallback must reveal without waiting.
        assertTrue(ANDROID_THEME_HOOK_JS.contains("__awPresentAtHookInstall"))
        assertTrue(ANDROID_THEME_HOOK_JS.contains("document.readyState === 'complete'"))
    }

    @Test
    fun themeHookExposesItsSettleStateToTheNativeFailsafe() {
        assertTrue(ANDROID_THEME_HOOK_JS.contains("__awAndroidThemeState"))
        assertTrue(ANDROID_THEME_HOOK_JS.contains("no-bridge"))
        // A document without aw-webui's mount point is not a dashboard and must
        // not keep the failsafe waiting.
        assertTrue(ANDROID_THEME_HOOK_JS.contains("not-dashboard"))
        assertTrue(ANDROID_THEME_HOOK_JS.contains("getElementById('app')"))
        assertTrue(ANDROID_THEME_HOOK_JS.contains("'pending'"))
    }

    @Test
    fun initialWebViewBackgroundResFollowsSystemNightMode() {
        val dark = android.content.res.Configuration().apply {
            uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        val light = android.content.res.Configuration().apply {
            uiMode = android.content.res.Configuration.UI_MODE_NIGHT_NO
        }
        assertEquals(R.color.chrome_dark, initialWebViewBackgroundRes(dark))
        assertEquals(R.color.chrome_light, initialWebViewBackgroundRes(light))
    }

    @Test
    fun systemNightModeReadsUiModeMask() {
        val dark = android.content.res.Configuration().apply {
            uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        val light = android.content.res.Configuration().apply {
            uiMode = android.content.res.Configuration.UI_MODE_NIGHT_NO
        }
        assertTrue(isSystemNightMode(dark))
        assertFalse(isSystemNightMode(light))
        assertEquals(
            android.content.res.Configuration.UI_MODE_NIGHT_YES,
            dark.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK,
        )
    }
}
