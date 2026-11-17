package net.activitywatch.android.fragments

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * Message bridge between the embedded aw-webui menu and native-only actions.
 *
 * Exposed to the page through AndroidX WebKit's WebMessageListener, which only
 * injects [NATIVE_BRIDGE_JS_OBJECT] into frames whose origin matches the
 * embedded server. Unlike `addJavascriptInterface`, a page from another origin
 * never sees the object. Messages are additionally required to come from the
 * main frame, so a same-origin iframe cannot trigger native actions either.
 *
 * Protocol (JSON strings, version [NATIVE_BRIDGE_VERSION]):
 * - page -> native `{"type":"hello"}`: native replies with
 *   `{"type":"capabilities","version":1,"actions":[...]}`. The page shows
 *   native menu entries only after this reply, so ordinary browsers and older
 *   Android builds never show actions they cannot perform.
 * - page -> native `{"type":"action","action":"sync-settings"}`: run one of
 *   the allowlisted [NativeAction]s. There is no generic URL/Intent action.
 * - page -> native `{"type":"menu","open":true}`: the shared menu opened or
 *   closed, so Android Back can dismiss it first.
 * - native -> page `{"type":"close-menu"}`: sent when Back dismisses the menu.
 */
internal const val NATIVE_BRIDGE_JS_OBJECT = "awNativeBridge"
internal const val NATIVE_BRIDGE_VERSION = 1

enum class NativeAction(val wireName: String) {
    SYNC_SETTINGS("sync-settings"),
    AUTH_SETTINGS("auth-settings"),
    OPEN_IN_BROWSER("open-in-browser");

    companion object {
        fun fromWireName(name: String): NativeAction? = entries.firstOrNull { it.wireName == name }
    }
}

internal sealed class NativeBridgeMessage {
    object Hello : NativeBridgeMessage()
    data class Action(val action: NativeAction) : NativeBridgeMessage()
    data class Menu(val open: Boolean) : NativeBridgeMessage()
}

/** Parse a page message; anything malformed or unknown is ignored (null). */
internal fun parseNativeBridgeMessage(data: String?): NativeBridgeMessage? {
    if (data == null) return null
    val json = try {
        JSONObject(data)
    } catch (_: Exception) {
        return null
    }
    return when (json.optString("type")) {
        "hello" -> NativeBridgeMessage.Hello
        "action" -> NativeAction.fromWireName(json.optString("action"))?.let { NativeBridgeMessage.Action(it) }
        "menu" -> if (json.opt("open") is Boolean) NativeBridgeMessage.Menu(json.getBoolean("open")) else null
        else -> null
    }
}

internal fun nativeCapabilitiesMessage(): String = JSONObject()
    .put("type", "capabilities")
    .put("version", NATIVE_BRIDGE_VERSION)
    .put("actions", JSONArray(NativeAction.entries.map { it.wireName }))
    .toString()

internal fun nativeCloseMenuMessage(): String = JSONObject().put("type", "close-menu").toString()

/** `scheme://host:port` with the default port made explicit, or null if [url] has no usable origin. */
internal fun normalizeOrigin(url: String): String? {
    val uri = try {
        URI(url.trim())
    } catch (_: Exception) {
        return null
    }
    val scheme = uri.scheme?.lowercase() ?: return null
    val host = uri.host?.lowercase() ?: return null
    val port = when {
        uri.port != -1 -> uri.port
        scheme == "http" -> 80
        scheme == "https" -> 443
        else -> return null
    }
    return "$scheme://$host:$port"
}

/** The WebMessageListener origin rule for the embedded server, without the default-port expansion. */
internal fun nativeBridgeOriginRule(baseUrl: String): String? {
    val uri = try {
        URI(baseUrl.trim())
    } catch (_: Exception) {
        return null
    }
    val scheme = uri.scheme?.lowercase() ?: return null
    val host = uri.host?.lowercase() ?: return null
    return if (uri.port == -1) "$scheme://$host" else "$scheme://$host:${uri.port}"
}

/**
 * Defense in depth on top of the listener's origin rule: accept only
 * main-frame messages from exactly the embedded server origin.
 */
internal fun isTrustedNativeBridgeMessage(sourceOrigin: String?, isMainFrame: Boolean, trustedBaseUrl: String): Boolean {
    if (!isMainFrame || sourceOrigin == null) return false
    val trusted = normalizeOrigin(trustedBaseUrl) ?: return false
    return normalizeOrigin(sourceOrigin) == trusted
}

/**
 * Tracks whether the page's shared menu is open, as reported over the bridge.
 * A new page load starts with the menu closed.
 */
internal class WebMenuState {
    var open: Boolean = false
        private set

    fun onPageStarted() {
        open = false
    }

    fun onMenuMessage(open: Boolean) {
        this.open = open
    }

    /** Back dismisses the menu: returns true if a close request should be sent. */
    fun requestClose(): Boolean {
        if (!open) return false
        open = false
        return true
    }
}
