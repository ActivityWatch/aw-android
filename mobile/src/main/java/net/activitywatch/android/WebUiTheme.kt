package net.activitywatch.android

import android.content.res.Configuration

/**
 * Resolve aw-webui's stored theme (`light` / `dark` / `auto`) to a dark chrome
 * scheme. `auto` follows the stylesheet or `prefers-color-scheme`.
 *
 * Kept in Kotlin so the JS hook in [ANDROID_THEME_HOOK_JS] has a unit-tested
 * twin; the WebView reports the already-resolved `dark`/`light` string.
 */
internal fun webUiSchemeIsDark(
    themeSetting: String?,
    prefersDark: Boolean,
    darkStylesheetPresent: Boolean,
): Boolean {
    return when (themeSetting?.lowercase()) {
        "dark" -> true
        "light" -> false
        else -> darkStylesheetPresent || prefersDark
    }
}

/**
 * Color resource for the WebView background to apply immediately on creation,
 * before the page loads and [ANDROID_THEME_HOOK_JS] fires.
 *
 * Uses the system night mode as the best pre-load approximation. The JS hook
 * corrects the colour after `onPageFinished` reads `localStorage.theme`.
 */
internal fun initialWebViewBackgroundRes(config: Configuration): Int =
    if (isSystemNightMode(config)) R.color.chrome_dark else R.color.chrome_light

/**
 * Observes aw-webui's `localStorage.theme` and the injected `/dark.css` link,
 * then reports `dark` or `light` to `Android.reportColorScheme`.
 */
internal val ANDROID_THEME_HOOK_JS = """
(function () {
  if (window.__awAndroidThemeHook) return;
  window.__awAndroidThemeHook = true;

  function resolve() {
    var setting = localStorage.getItem('theme') || 'auto';
    var prefersDark = !!(window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches);
    var hasDarkCss = !!document.querySelector('link[href*="dark.css"]');
    var dark = setting === 'dark' || (setting !== 'light' && (hasDarkCss || prefersDark));
    return dark ? 'dark' : 'light';
  }

  function notify() {
    if (typeof Android === 'undefined' || !Android.reportColorScheme) return;
    Android.reportColorScheme(resolve());
  }

  var orig = localStorage.setItem.bind(localStorage);
  localStorage.setItem = function (k, v) {
    orig(k, v);
    if (k === 'theme') notify();
  };
  if (document.head) {
    new MutationObserver(notify).observe(document.head, { childList: true, subtree: true });
  }
  var mq = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;
  if (mq) {
    if (mq.addEventListener) mq.addEventListener('change', notify);
    else if (mq.addListener) mq.addListener(notify);
  }
  notify();
  setTimeout(notify, 300);
  setTimeout(notify, 1500);
})();
""".trimIndent()
