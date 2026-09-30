package net.activitywatch.android.watcher

import org.json.JSONException
import org.json.JSONObject

internal const val PROBE_BACKOFF_START_MS = 1_000L
internal const val PROBE_BACKOFF_MAX_MS = 30 * 60 * 1_000L

// Probe order for an unknown package: Chromium first, a by-id lookup is cheap while the
// Gecko path walks the tree.
internal enum class UrlBarStyle(val key: String) {
    CHROMIUM("chromium"),
    GECKO("gecko"),
}

private data class ProbeState(
    val versionCode: Long,
    val style: UrlBarStyle? = null,
    val failures: Int = 0,
    val nextProbeAtMs: Long = 0,
)

// Remembers, per detected browser and app version, which URL bar style works, so each
// event runs one extractor instead of both. Packages that never produced a URL (link
// handlers that are not browsers) are probed with exponential backoff, because a failed
// Gecko probe walks the whole window tree.
//
// A package that produced a URL once is never backed off: a real browser hides its URL
// bar in fullscreen video or on a new tab, and backing off would then miss pages.
// State is kept across restarts through `save`, as a JSON string (see encode()).
// Synchronized: retain() runs on the browser detection thread.
internal class BrowserProbeMemory(saved: String?, private val save: (String) -> Unit) {
    private val states: MutableMap<String, ProbeState> = decode(saved)

    // False while a package that never produced a URL is backed off: the caller should
    // then treat its events like those of any non-browser app.
    // A known browser is always due: its nextProbeAtMs stays 0.
    @Synchronized
    fun isActive(pkg: String, versionCode: Long, nowMs: Long): Boolean =
        current(pkg, versionCode)?.let { isDue(it, nowMs) } ?: true

    @Synchronized
    fun extract(pkg: String, versionCode: Long, nowMs: Long, probe: (UrlBarStyle) -> String?): String? {
        val state = current(pkg, versionCode) ?: ProbeState(versionCode)
        state.style?.let { return probe(it) }
        if (!isDue(state, nowMs)) return null
        for (style in UrlBarStyle.values()) {
            val url = probe(style) ?: continue
            update(pkg, ProbeState(versionCode, style))
            return url
        }
        val failures = state.failures + 1
        val delay = minOf(PROBE_BACKOFF_START_MS shl minOf(failures - 1, 30), PROBE_BACKOFF_MAX_MS)
        update(pkg, ProbeState(versionCode, failures = failures, nextProbeAtMs = nowMs + delay))
        return null
    }

    @Synchronized
    fun retain(packages: Set<String>) {
        if (states.keys.retainAll(packages)) save(encode())
    }

    private fun current(pkg: String, versionCode: Long): ProbeState? =
        states[pkg]?.takeIf { it.versionCode == versionCode }

    // A wall clock stepped backwards would otherwise leave the next probe far in the future.
    private fun isDue(state: ProbeState, nowMs: Long): Boolean =
        nowMs >= state.nextProbeAtMs || state.nextProbeAtMs - nowMs > PROBE_BACKOFF_MAX_MS

    private fun update(pkg: String, state: ProbeState) {
        states[pkg] = state
        save(encode())
    }

    private fun encode(): String {
        val json = JSONObject()
        for ((pkg, s) in states) {
            val entry = JSONObject().put("v", s.versionCode)
            if (s.style != null) entry.put("style", s.style.key)
            else entry.put("failures", s.failures).put("next", s.nextProbeAtMs)
            json.put(pkg, entry)
        }
        return json.toString()
    }
}

// Lenient: a corrupt entry only costs re-probing that package.
private fun decode(saved: String?): MutableMap<String, ProbeState> {
    val out = HashMap<String, ProbeState>()
    if (saved.isNullOrEmpty()) return out
    val json = try {
        JSONObject(saved)
    } catch (e: JSONException) {
        return out
    }
    for (pkg in json.keys()) {
        try {
            val entry = json.getJSONObject(pkg)
            val style = entry.optString("style").let { key -> UrlBarStyle.values().firstOrNull { it.key == key } }
            out[pkg] = ProbeState(
                versionCode = entry.getLong("v"),
                style = style,
                failures = if (style == null) entry.getInt("failures") else 0,
                nextProbeAtMs = if (style == null) entry.getLong("next") else 0,
            )
        } catch (e: JSONException) {
            continue
        }
    }
    return out
}
