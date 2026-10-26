package net.activitywatch.android.watcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenStateEventDataTest {

    @Test
    fun `event data names both states`() {
        val data = ScreenState(interactive = true, keyguardShown = false).toEventData()

        assertEquals("interactive", data.getString("screen"))
        assertEquals("hidden", data.getString("keyguard"))
        assertEquals(2, data.length())
    }

    @Test
    fun `every state reads back from its event data`() {
        for (interactive in listOf(true, false)) {
            for (keyguardShown in listOf(true, false)) {
                val state = ScreenState(interactive, keyguardShown)
                assertEquals(state, screenStateFromEventData(state.toEventData()))
            }
        }
    }

    @Test
    fun `data with a missing or unknown value is not a state`() {
        assertNull(screenStateFromEventData(null))
        assertNull(screenStateFromEventData(JSONObject().put("screen", "interactive")))
        assertNull(
            screenStateFromEventData(JSONObject().put("screen", "on").put("keyguard", "hidden"))
        )
    }
}
