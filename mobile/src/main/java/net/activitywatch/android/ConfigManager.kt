package net.activitywatch.android

import android.content.Context
import android.util.Log
import java.io.File
import java.util.UUID

private const val TAG = "ConfigManager"

/**
 * Manages reading and writing the embedded aw-server config.toml stored in
 * the app's filesDir. The config format is TOML; we only need the [auth]
 * section, so we handle it with simple string operations rather than pulling
 * in a full TOML library.
 *
 * Changes take effect after the server restarts (i.e. app restart).
 */
class ConfigManager(context: Context) {

    private val configFile = File(context.filesDir, "config.toml")

    data class AuthConfig(
        val apiKey: String? = null
    ) {
        val isEnabled: Boolean get() = !apiKey.isNullOrEmpty()
    }

    fun readAuthConfig(): AuthConfig {
        if (!configFile.exists()) {
            Log.d(TAG, "config.toml not found, returning defaults")
            return AuthConfig()
        }
        return try {
            val content = configFile.readText()
            val apiKey = parseApiKey(content)
            AuthConfig(apiKey = apiKey)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read config.toml: ${e.message}")
            AuthConfig()
        }
    }

    fun setApiKey(key: String?) {
        try {
            val current = if (configFile.exists()) configFile.readText() else ""
            val updated = writeApiKey(current, key)
            configFile.writeText(updated)
            Log.d(TAG, "API key updated in config.toml")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write config.toml: ${e.message}")
        }
    }

    fun generateAndSetApiKey(): String {
        val key = UUID.randomUUID().toString().replace("-", "")
        setApiKey(key)
        return key
    }

    fun clearApiKey() = setApiKey(null)

    // ---- internal TOML manipulation ----

    private fun parseApiKey(content: String): String? {
        // Find the [auth] section, then look for api_key = "..."
        val authSectionRegex = Regex("""(?m)^\[auth\].*?(?=^\[|\z)""", RegexOption.DOT_MATCHES_ALL)
        val authSection = authSectionRegex.find(content)?.value ?: return null
        val keyMatch = Regex("""api_key\s*=\s*"([^"]*)"""").find(authSection)
        val key = keyMatch?.groupValues?.get(1)
        return if (key.isNullOrEmpty()) null else key
    }

    private fun writeApiKey(content: String, key: String?): String {
        val authLine = if (key.isNullOrEmpty()) null else """api_key = "$key""""
        val authSectionRegex = Regex("""(?m)^\[auth\].*?(?=^\[|\z)""", RegexOption.DOT_MATCHES_ALL)
        val authSectionContent = authSectionRegex.find(content)?.value
        val authSectionPresent = authSectionContent != null
        // Scope the api_key check to the [auth] section only, not the whole file
        val apiKeyLinePresent = authSectionContent != null &&
            Regex("""(?m)^api_key\s*=""").containsMatchIn(authSectionContent)

        if (authSectionPresent) {
            return if (apiKeyLinePresent) {
                // Replace or remove the existing api_key line
                val replaced = Regex("""(?m)^api_key\s*=.*$""")
                    .replaceFirst(content, authLine ?: "")
                if (authLine == null) replaced.replace(Regex("\n{3,}"), "\n\n") else replaced
            } else if (authLine != null) {
                // [auth] exists but no api_key line yet — insert after the [auth] header
                content.replaceFirst(oldValue = "[auth]\n", newValue = "[auth]\n$authLine\n")
            } else {
                content
            }
        }

        // No [auth] section — append it if we have a key
        return if (authLine != null) {
            val base = content.trimEnd()
            "$base\n\n[auth]\n$authLine\n"
        } else {
            content
        }
    }
}
