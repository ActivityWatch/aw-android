package net.activitywatch.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for ConfigManager's TOML parsing/writing logic, tested via
 * standalone helpers that mirror the private functions (Android-free JVM tests).
 */
class ConfigManagerTest {

    private fun parseApiKey(content: String): String? {
        val authSectionRegex = Regex("""(?m)^\[auth\].*?(?=^\[|\z)""", RegexOption.DOT_MATCHES_ALL)
        val authSection = authSectionRegex.find(content)?.value ?: return null
        val keyMatch = Regex("""api_key\s*=\s*"([^"]*)"""").find(authSection)
        val key = keyMatch?.groupValues?.get(1)
        return if (key.isNullOrEmpty()) null else key
    }

    private fun writeApiKey(content: String, key: String?): String {
        val authLine = if (key.isNullOrEmpty()) null else """api_key = "$key""""
        val authSectionRegex = Regex("""(?m)^\[auth\].*?(?=^\[|\z)""", RegexOption.DOT_MATCHES_ALL)
        val authSectionMatch = authSectionRegex.find(content)
        if (authSectionMatch != null) {
            val authSectionContent = authSectionMatch.value
            val apiKeyLinePresent = Regex("""(?m)^api_key\s*=""").containsMatchIn(authSectionContent)
            val updatedSection = if (apiKeyLinePresent) {
                val replaced = Regex("""(?m)^api_key\s*=.*$""")
                    .replaceFirst(authSectionContent, authLine ?: "")
                if (authLine == null) replaced.replace(Regex("\n{3,}"), "\n\n") else replaced
            } else if (authLine != null) {
                val separator = if (authSectionContent.endsWith("\n")) "" else "\n"
                "$authSectionContent$separator$authLine\n"
            } else {
                authSectionContent
            }
            return content.replaceRange(authSectionMatch.range, updatedSection)
        }
        return if (authLine != null) {
            val base = content.trimEnd()
            "$base\n\n[auth]\n$authLine\n"
        } else {
            content
        }
    }

    @Test
    fun `parseApiKey returns null on empty config`() {
        assertNull(parseApiKey(""))
    }

    @Test
    fun `parseApiKey returns null when no auth section`() {
        val config = "address = \"127.0.0.1\"\nport = 5600"
        assertNull(parseApiKey(config))
    }

    @Test
    fun `parseApiKey reads key from auth section`() {
        val config = "address = \"127.0.0.1\"\n\n[auth]\napi_key = \"abc123\"\n"
        assertEquals("abc123", parseApiKey(config))
    }

    @Test
    fun `parseApiKey returns null for empty api_key`() {
        val config = "[auth]\napi_key = \"\"\n"
        assertNull(parseApiKey(config))
    }

    @Test
    fun `writeApiKey appends auth section when absent`() {
        val config = "address = \"127.0.0.1\"\nport = 5600"
        val result = writeApiKey(config, "mykey")
        assertTrue(result.contains("[auth]"))
        assertTrue(result.contains("""api_key = "mykey""""))
    }

    @Test
    fun `writeApiKey updates existing key`() {
        val config = "[auth]\napi_key = \"oldkey\"\n"
        val result = writeApiKey(config, "newkey")
        assertTrue(result.contains("""api_key = "newkey""""))
        assertFalse(result.contains("oldkey"))
    }

    @Test
    fun `writeApiKey clears key when null`() {
        val config = "[auth]\napi_key = \"oldkey\"\n"
        val result = writeApiKey(config, null)
        assertFalse(result.contains("oldkey"))
    }

    @Test
    fun `writeApiKey leaves config unchanged when no auth and key is null`() {
        val config = "address = \"127.0.0.1\"\n"
        val result = writeApiKey(config, null)
        assertFalse(result.contains("[auth]"))
    }

    @Test
    fun `writeApiKey inserts key when auth section exists but no api_key line`() {
        val config = "address = \"127.0.0.1\"\n\n[auth]\n"
        val result = writeApiKey(config, "inserted-key")
        assertEquals("inserted-key", parseApiKey(result))
    }

    @Test
    fun `writeApiKey inserts key when auth section has no trailing newline`() {
        val config = "address = \"127.0.0.1\"\n\n[auth]"
        val result = writeApiKey(config, "inserted-key")
        assertEquals("inserted-key", parseApiKey(result))
        assertEquals("address = \"127.0.0.1\"\n\n[auth]\napi_key = \"inserted-key\"\n", result)
    }

    @Test
    fun `roundtrip write then read returns same key`() {
        val config = "address = \"127.0.0.1\"\n"
        val written = writeApiKey(config, "roundtrip-key")
        assertEquals("roundtrip-key", parseApiKey(written))
    }

    @Test
    fun `roundtrip double write returns new key`() {
        val config = "address = \"127.0.0.1\"\n"
        val first = writeApiKey(config, "first-key")
        val second = writeApiKey(first, "second-key")
        assertEquals("second-key", parseApiKey(second))
        assertFalse(second.contains("first-key"))
    }

    @Test
    fun `writeApiKey does not overwrite api_key in other sections`() {
        // Another section also has an api_key field — must not be touched
        val config = "[logging]\napi_key = \"log-key\"\n\n[auth]\napi_key = \"auth-key\"\n"
        val result = writeApiKey(config, "new-auth-key")
        assertEquals("new-auth-key", parseApiKey(result))
        assertTrue(result.contains("""api_key = "log-key""""))
    }
}
