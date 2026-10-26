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
 *
 * Also calls `Android.reportThemeSettled(window.__awAndroidNavigation)` once
 * per document (the native side tags each finished navigation), when the page's
 * theme is actually applied: aw-webui's App.vue renders `#wrapper` only after
 * loading settings and appending any `/dark.css` link, and that stylesheet must
 * then have loaded or failed. Before that the body is painted with the light
 * base styles even when dark is selected (aw-android#300).
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

  var settledReported = false;

  // This hook is injected from onPageFinished, after the window load event, so
  // a dark.css link already in the document has already fired load or error.
  // Its listener would then never fire, leaving the document hidden until the
  // failsafe. Mark it so a null sheet is read as "failed" below.
  var linkAtInstall = document.querySelector('link[href*="dark.css"]');
  if (linkAtInstall) linkAtInstall.__awPresentAtHookInstall = true;

  function darkStylesheetSettled(link) {
    if (link.__awSettled) return true;
    try {
      if (link.sheet && link.sheet.cssRules.length > 0) return true;
    } catch (e) {
      // The sheet exists but its rules are unreadable (cross-origin): loaded.
      return true;
    }
    if (!link.__awWatched) {
      link.__awWatched = true;
      var done = function () {
        link.__awSettled = true;
        checkSettled();
      };
      link.addEventListener('load', done);
      link.addEventListener('error', done);
    }
    if (link.__awPresentAtHookInstall && document.readyState === 'complete') {
      // Present before this hook ran and the document finished loading. A link
      // that was added before the load event has already dispatched load/error
      // (our listener above will never fire). However, a link appended in a
      // post-load fetch callback may still be in-flight when this hook runs.
      // PerformanceResourceTiming's responseEnd distinguishes the two cases:
      // > 0 means the response was received before the hook; treat a null
      // sheet as "failed" and reveal the readable fallback now.
      try {
        var entries = performance.getEntriesByName(link.href, 'resource');
        if (entries.length > 0 && entries[entries.length - 1].responseEnd > 0) {
          return true;
        }
        // Request still in-flight; the listener above will fire on completion.
        return false;
      } catch (e) {
        // performance API unavailable; fall back: treat null sheet as failed.
        return true;
      }
    }
    return false;
  }

  // Lets the native failsafe distinguish "the page will report shortly" from
  // "this document can never report", instead of revealing a dashboard that is
  // still applying its theme (which paints the light base styles first).
  window.__awAndroidThemeState = function () {
    if (settledReported) return 'settled';
    if (typeof Android === 'undefined' || !Android.reportThemeSettled) return 'no-bridge';
    // aw-webui's index.html always ships Vue's mount point. Without it this
    // document is not a dashboard and will never render #wrapper or report.
    if (!document.getElementById('app')) return 'not-dashboard';
    return 'pending';
  };

  function checkSettled() {
    if (settledReported || !document.getElementById('wrapper')) return;
    var link = document.querySelector('link[href*="dark.css"]');
    if (link && !darkStylesheetSettled(link)) return;
    if (typeof Android === 'undefined' || !Android.reportThemeSettled) return;
    settledReported = true;
    settledObserver.disconnect();
    Android.reportThemeSettled(String(window.__awAndroidNavigation));
  }

  var settledObserver = new MutationObserver(checkSettled);
  // onPageFinished can deliver this hook into a document that has not parsed
  // its root element yet (the previous navigation finishing after the next one
  // started). document.documentElement is then null and observing it throws,
  // which aborted the whole hook and left that document unable to ever report
  // settlement — the WebView stayed hidden. Observing the Document covers the
  // same subtree and survives the empty-document window.
  settledObserver.observe(document.documentElement || document, {
    childList: true,
    subtree: true,
  });
  checkSettled();

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
