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

// Strips the URI scheme so the same page is represented identically regardless of which
// browser/UI-variant's view happened to include it (e.g. Samsung Internet's regular vs.
// custom-tab toolbar previously disagreed on this, splitting one continuous visit in two).
internal val stripProtocol: (String) -> String = { url ->
    url.removePrefix("http://").removePrefix("https://")
}

// Pure post-processing of text read off an accessibility node: blank text (e.g. a
// momentarily-cleared address bar) is treated as "no url", not as a real value.
internal fun processExtractedText(rawText: String?): String? =
    rawText?.takeIf { it.isNotBlank() }
