package net.activitywatch.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardAuthTest {
    @Test
    fun buildDashboardUrl_skipsTokenWhenAuthDisabled() {
        assertEquals("http://127.0.0.1:5600", buildDashboardUrl(baseURL, null))
    }

    @Test
    fun buildDashboardUrl_appendsEncodedTokenBeforeHashRoute() {
        assertEquals(
            "http://127.0.0.1:5600/?token=secret%2B+%2F%3F%3D%26#/activity/unknown/",
            buildDashboardUrl(
                "$baseURL/#/activity/unknown/",
                "secret+ /?=&",
            ),
        )
    }

    @Test
    fun extractApiKey_readsExistingAuthSection() {
        val config = """
            address = "127.0.0.1"

            [auth]
            api_key = "existing-token"
        """.trimIndent()

        assertEquals("existing-token", extractApiKey(config))
    }

    @Test
    fun extractApiKey_ignoresCommentedDefaults() {
        val config = """
            ### DEFAULT SETTINGS ###
            #[auth]
            #api_key = "commented"
        """.trimIndent()

        assertNull(extractApiKey(config))
    }

    @Test
    fun upsertApiKey_appendsAuthSectionWhenMissing() {
        val updated = upsertApiKey("address = \"127.0.0.1\"\n", "generated-token")

        assertEquals(
            "address = \"127.0.0.1\"\n\n[auth]\napi_key = \"generated-token\"\n",
            updated,
        )
    }

    @Test
    fun upsertApiKey_replacesExistingValueWithoutDroppingOtherSections() {
        val config = """
            [auth]
            api_key = "old-token"

            [custom_static]
            test = "/tmp/static"
        """.trimIndent()

        assertEquals(
            "[auth]\napi_key = \"new-token\"\n\n[custom_static]\ntest = \"/tmp/static\"\n",
            upsertApiKey(config, "new-token"),
        )
    }
}
