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
        // PerformanceResourceTiming guards against the race where a link is
        // appended dynamically (post-load) but still in-flight when the hook
        // runs: responseEnd > 0 confirms the request completed before the hook.
        assertTrue(ANDROID_THEME_HOOK_JS.contains("getEntriesByName"))
        assertTrue(ANDROID_THEME_HOOK_JS.contains("responseEnd"))
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
    fun themeHookObservesADocumentWithoutARootElement() {
        // onPageFinished can run this hook in a document that has not parsed its
        // root element yet. Observing a null documentElement threw and aborted
        // the hook, so that document could never report settlement and the
        // WebView stayed hidden (observed as a DashboardThemePaintTest timeout).
        assertTrue(ANDROID_THEME_HOOK_JS.contains("document.documentElement || document"))
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
