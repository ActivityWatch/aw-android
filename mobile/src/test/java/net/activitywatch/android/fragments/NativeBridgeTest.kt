package net.activitywatch.android.fragments

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeBridgeTest {
    private val base = "http://127.0.0.1:5600"

    @Test
    fun acceptsMainFrameMessagesFromTheEmbeddedServerOnly() {
        assertTrue(isTrustedNativeBridgeMessage("http://127.0.0.1:5600", isMainFrame = true, trustedBaseUrl = base))
        // WebView may report the origin with a trailing slash.
        assertTrue(isTrustedNativeBridgeMessage("http://127.0.0.1:5600/", isMainFrame = true, trustedBaseUrl = base))
    }

    @Test
    fun rejectsIframesEvenFromTheTrustedOrigin() {
        assertFalse(isTrustedNativeBridgeMessage("http://127.0.0.1:5600", isMainFrame = false, trustedBaseUrl = base))
    }

    @Test
    fun rejectsOtherOriginsPortsAndSchemes() {
        listOf(
            "https://example.com",
            "http://127.0.0.1:5666",
            "https://127.0.0.1:5600",
            "http://localhost:5600",
            "http://127.0.0.1.evil.example:5600",
            "null",
            "",
        ).forEach { origin ->
            assertFalse(origin, isTrustedNativeBridgeMessage(origin, isMainFrame = true, trustedBaseUrl = base))
        }
        assertFalse(isTrustedNativeBridgeMessage(null, isMainFrame = true, trustedBaseUrl = base))
    }

    @Test
    fun originRuleMatchesEmbeddedServer() {
        assertEquals("http://127.0.0.1:5600", nativeBridgeOriginRule("http://127.0.0.1:5600"))
        assertEquals("http://127.0.0.1:5600", nativeBridgeOriginRule("http://127.0.0.1:5600/#/activity/"))
        assertNull(nativeBridgeOriginRule("not a url"))
    }

    @Test
    fun parsesAllowlistedMessages() {
        assertEquals(NativeBridgeMessage.Hello, parseNativeBridgeMessage("""{"type":"hello"}"""))
        assertEquals(
            NativeBridgeMessage.Action(NativeAction.SYNC_SETTINGS),
            parseNativeBridgeMessage("""{"type":"action","action":"sync-settings"}"""),
        )
        assertEquals(
            NativeBridgeMessage.Action(NativeAction.AUTH_SETTINGS),
            parseNativeBridgeMessage("""{"type":"action","action":"auth-settings"}"""),
        )
        assertEquals(
            NativeBridgeMessage.Action(NativeAction.OPEN_IN_BROWSER),
            parseNativeBridgeMessage("""{"type":"action","action":"open-in-browser"}"""),
        )
        assertEquals(NativeBridgeMessage.Menu(true), parseNativeBridgeMessage("""{"type":"menu","open":true}"""))
        assertEquals(NativeBridgeMessage.Menu(false), parseNativeBridgeMessage("""{"type":"menu","open":false}"""))
    }

    @Test
    fun ignoresUnknownOrMalformedMessages() {
        listOf(
            null,
            "",
            "not json",
            "[]",
            """{"type":"action","action":"open-url","url":"https://example.com"}""",
            """{"type":"action"}""",
            """{"type":"menu","open":"true"}""",
            """{"type":"menu"}""",
            """{"type":"exec"}""",
        ).forEach { data ->
            assertNull(data.toString(), parseNativeBridgeMessage(data))
        }
    }

    @Test
    fun capabilitiesListEveryNativeAction() {
        val json = JSONObject(nativeCapabilitiesMessage())
        assertEquals("capabilities", json.getString("type"))
        assertEquals(NATIVE_BRIDGE_VERSION, json.getInt("version"))
        val actions = json.getJSONArray("actions")
        assertEquals(
            listOf("sync-settings", "auth-settings", "open-in-browser"),
            (0 until actions.length()).map { actions.getString(it) },
        )
        assertEquals("close-menu", JSONObject(nativeCloseMenuMessage()).getString("type"))
    }

    @Test
    fun webMenuStateClosesOnceAndResetsOnNavigation() {
        val state = WebMenuState()
        assertFalse(state.requestClose())

        state.onMenuMessage(true)
        assertTrue(state.open)
        assertTrue(state.requestClose())
        assertFalse(state.open)
        assertFalse(state.requestClose())

        state.onMenuMessage(true)
        state.onPageStarted()
        assertFalse(state.open)
    }
}
