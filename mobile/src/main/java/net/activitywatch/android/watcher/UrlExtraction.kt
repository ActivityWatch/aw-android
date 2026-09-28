package net.activitywatch.android.watcher

// Firefox (Compose toolbar): URL is in content-desc of ADDRESSBAR_URL_BOX as
// " {url}. Search or enter address". Greedy so we split at the LAST ". " (URLs can
// themselves contain ". "), and we don't assume the hint text starts with an ASCII
// capital letter since it's localized and may start with a non-Latin character.
private val FIREFOX_SUFFIX_PATTERN = Regex("""^\s*(.+)\.\s+\S""")

// Pure parsing logic, unit-testable (mobile/src/test) without any Android/Accessibility
// framework dependency.
internal fun parseFirefoxAddressBarContentDescription(contentDescription: String?): String? =
    contentDescription
        ?.let { FIREFOX_SUFFIX_PATTERN.find(it)?.groupValues?.get(1) }
        ?.takeIf { it.isNotBlank() && !it.equals("Search or enter address", ignoreCase = true) }

private val URL_SCHEME_PREFIX = Regex("""^[A-Za-z][A-Za-z0-9+.-]*://""")

// Gives every url a scheme, as desktop aw-watcher-web does, so aw-webui can split out the
// domain. Mobile address bars hide the scheme and there is no reliable way to recover it,
// so a missing one is inferred as https; a scheme the bar does show is kept as is.
internal fun normalizeUrl(raw: String?): String? {
    val url = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return if (URL_SCHEME_PREFIX.containsMatchIn(url)) url else "https://$url"
}

// Pure post-processing of text read off an accessibility node: blank text (e.g. a
// momentarily-cleared address bar) is treated as "no url", not as a real value.
internal fun processExtractedText(rawText: String?): String? =
    rawText?.takeIf { it.isNotBlank() }
