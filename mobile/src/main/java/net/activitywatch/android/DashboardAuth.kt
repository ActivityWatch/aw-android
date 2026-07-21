package net.activitywatch.android

import android.content.Context
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

private const val CONFIG_FILE_NAME = "config.toml"
private const val AUTH_SECTION = "auth"

private val sectionHeaderPattern = Regex("""^\s*\[([^\]]+)]\s*(?:#.*)?$""")
private val apiKeyPattern = Regex("""^\s*api_key\s*=\s*(['"])(.*?)\1\s*(?:#.*)?$""")

fun ensureDashboardApiKey(context: Context): String {
    val configFile = File(context.filesDir, CONFIG_FILE_NAME)
    val currentConfig = if (configFile.isFile) configFile.readText() else ""
    val existingApiKey = extractApiKey(currentConfig)
    if (existingApiKey != null) {
        return existingApiKey
    }

    val generatedApiKey = UUID.randomUUID().toString()
    configFile.writeText(upsertApiKey(currentConfig, generatedApiKey))
    return generatedApiKey
}

internal fun buildDashboardUrl(baseUrl: String, apiKey: String?): String {
    val normalizedApiKey = apiKey?.trim().orEmpty()
    if (normalizedApiKey.isEmpty()) {
        return baseUrl
    }
    val parsedUrl = URI(baseUrl)
    val queryParts = mutableListOf<String>()

    if (!parsedUrl.rawQuery.isNullOrBlank()) {
        queryParts.add(parsedUrl.rawQuery)
    }
    queryParts.add(
        "token=${URLEncoder.encode(normalizedApiKey, StandardCharsets.UTF_8.name())}"
    )

    val normalizedPath = parsedUrl.path?.takeIf { it.isNotEmpty() } ?: "/"
    val rawQuery = queryParts.joinToString("&")
    val rawFragment = parsedUrl.rawFragment
    return buildString {
        append(parsedUrl.scheme).append("://").append(parsedUrl.authority)
        append(normalizedPath).append("?").append(rawQuery)
        if (rawFragment != null) append("#").append(rawFragment)
    }
}

internal fun extractApiKey(config: String): String? {
    var inAuthSection = false

    for (line in config.lineSequence()) {
        val trimmed = line.trim()
        if (trimmed.startsWith("#")) {
            continue
        }

        val sectionName = parseSectionName(trimmed)
        if (sectionName != null) {
            inAuthSection = sectionName == AUTH_SECTION
            continue
        }

        if (!inAuthSection) {
            continue
        }

        val apiKeyMatch = apiKeyPattern.matchEntire(trimmed) ?: continue
        val apiKey = apiKeyMatch.groupValues[2].trim()
        if (apiKey.isNotEmpty()) {
            return apiKey
        }
    }

    return null
}

internal fun upsertApiKey(config: String, apiKey: String): String {
    val escapedApiKey = escapeTomlString(apiKey)
    val renderedApiKey = """api_key = "$escapedApiKey""""
    val result = mutableListOf<String>()
    var inAuthSection = false
    var insertedApiKey = false

    for (line in config.lineSequence()) {
        val trimmed = line.trim()
        val sectionName = parseSectionName(trimmed)

        if (sectionName != null) {
            if (inAuthSection && !insertedApiKey) {
                result.add(renderedApiKey)
                insertedApiKey = true
            }
            inAuthSection = sectionName == AUTH_SECTION
            result.add(line)
            continue
        }

        if (inAuthSection && apiKeyPattern.matchEntire(trimmed) != null) {
            if (!insertedApiKey) {
                result.add(renderedApiKey)
                insertedApiKey = true
            }
            continue
        }

        result.add(line)
    }

    if (inAuthSection && !insertedApiKey) {
        result.add(renderedApiKey)
        insertedApiKey = true
    }

    if (!insertedApiKey) {
        if (result.isNotEmpty() && result.last().isNotBlank()) {
            result.add("")
        }
        result.add("[auth]")
        result.add(renderedApiKey)
    }

    return result.joinToString("\n").let { rendered ->
        if (rendered.endsWith("\n")) rendered else "$rendered\n"
    }
}

private fun escapeTomlString(value: String): String {
    return value.replace("\\", "\\\\").replace("\"", "\\\"")
}

private fun parseSectionName(line: String): String? {
    val match = sectionHeaderPattern.matchEntire(line) ?: return null
    return match.groupValues[1].trim().removeSurrounding("\"")
}
