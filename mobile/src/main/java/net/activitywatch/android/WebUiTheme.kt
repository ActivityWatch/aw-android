package net.activitywatch.android

import android.content.res.Configuration
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/** Record terminal events before onPageFinished, without reporting an untagged navigation. */
internal fun installThemeStylesheetRecorder(view: WebView) {
    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
        WebViewCompat.addDocumentStartJavaScript(view, ANDROID_THEME_STYLESHEET_RECORDER_JS, setOf("*"))
    }
}

internal val ANDROID_THEME_STYLESHEET_RECORDER_JS = """
(function () {
  if (window !== window.top || window.__awThemeStylesheetRecorder) return;
  window.__awThemeStylesheetRecorder = true;
  function terminal(event) {
    var link = event.target;
    if (link && link.tagName === 'LINK') link.__awSettled = true;
  }
  document.addEventListener('load', terminal, true);
  document.addEventListener('error', terminal, true);
})();
""".trimIndent()

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

  function darkStylesheetSettled(link) {
    if (link.__awSettled) return true;
    try {
      if (link.sheet && link.sheet.cssRules.length > 0) return true;
    } catch (e) {
      // The sheet exists but its rules are unreadable (cross-origin): loaded.
      return true;
    }
    if (!window.__awThemeStylesheetRecorder && !link.__awWatched) {
      // Old WebViews cannot record events at document start. A missing/empty
      // sheet is ambiguous (pending, failed or empty), even after window load.
      // Restart this one link with listeners installed before insertion rather
      // than guessing. Preserve attributes and cascade position; no loaded CSS
      // is removed. Modern WebViews never need this extra request.
      var replacement = link.cloneNode(false);
      replacement.__awWatched = true;
      var completed = function () {
        replacement.__awSettled = true;
        checkSettled();
      };
      replacement.addEventListener('load', completed);
      replacement.addEventListener('error', completed);
      link.parentNode.replaceChild(replacement, link);
      return false;
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
