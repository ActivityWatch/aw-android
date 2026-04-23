package net.activitywatch.android

import android.content.Context
import android.util.Log
import net.activitywatch.android.models.Event
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.threeten.bp.Instant
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

private const val TAG = "RustInterface"

class RustInterface constructor(private val context: Context? = null) {

    private val prefs: AWPreferences? = context?.let { AWPreferences(it) }

    companion object {
        var serverStarted = false
    }

    private fun getServerUrl(): String {
        val remote = prefs?.getRemoteServerUrl()
        return when {
            remote.isNullOrBlank() -> "http://127.0.0.1:5600"
            remote.startsWith("http://") || remote.startsWith("https://") -> remote
            else -> "http://$remote"
        }
    }

    private fun httpGet(path: String): String {
        return try {
            val url = URL("${getServerUrl()}$path")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                Log.e(TAG, "HTTP GET failed: $path, status=$responseCode")
                "{}"
            }
        } catch (e: Exception) {
            Log.e(TAG, "HTTP GET error: $path, ${e.message}")
            "{}"
        }
    }

    private fun httpPost(path: String, payload: String): String {
        return try {
            val url = URL("${getServerUrl()}$path")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.doOutput = true

            conn.outputStream.use { it.write(payload.toByteArray(StandardCharsets.UTF_8)) }

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                Log.i(TAG, "HTTP POST OK: $path")
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                Log.w(TAG, "HTTP POST failed: $path, status=$responseCode")
                ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "HTTP POST error: $path, ${e.message}")
            ""
        }
    }

    fun startServerTask(context: Context) {
        // No-op in remote-only mode
        Log.i(TAG, "Remote-only mode: no local server to start")
    }

    fun createBucketHelper(bucket_id: String, type: String, hostname: String = "unknown", client: String = "aw-android") {
        try {
            val buckets = getBucketsJSON()
            if (bucket_id in buckets.keys().asSequence()) {
                Log.i(TAG, "Bucket with ID '$bucket_id', already existed. Not creating.")
            } else {
                val payload = """{"id": "$bucket_id", "type": "$type", "hostname": "$hostname", "client": "$client"}"""
                httpPost("/api/0/buckets/$bucket_id", payload)
                Log.w(TAG, "Created bucket: $bucket_id")
            }
        } catch (e: Exception) {
            Log.e(TAG, "createBucketHelper error: ${e.message}")
        }
    }

    fun heartbeatHelper(bucket_id: String, timestamp: Instant, duration: Double, data: JSONObject, pulsetime: Double = 60.0) {
        try {
            val event = Event(timestamp, duration, data)
            val eventJson = event.toString()
            httpPost("/api/0/buckets/$bucket_id/heartbeat?pulsetime=$pulsetime", eventJson)
        } catch (e: Exception) {
            Log.e(TAG, "heartbeatHelper error: ${e.message}")
        }
    }

    fun getBucketsJSON(): JSONObject {
        return try {
            val result = httpGet("/api/0/buckets")
            JSONObject(result)
        } catch (e: Exception) {
            Log.e(TAG, "getBucketsJSON error: ${e.message}")
            JSONObject()
        }
    }

    fun getEventsJSON(bucket_id: String, limit: Int = 0): JSONArray {
        return try {
            val limitParam = if (limit > 0) "?limit=$limit" else ""
            val result = httpGet("/api/0/buckets/$bucket_id/events$limitParam")
            Log.w(TAG, "getEventsJSON($bucket_id): raw result length=${result.length}")
            JSONArray(result)
        } catch (e: JSONException) {
            Log.e(TAG, "getEventsJSON parse error: ${e.message}")
            JSONArray()
        } catch (e: Exception) {
            Log.e(TAG, "getEventsJSON error: ${e.message}")
            JSONArray()
        }
    }

}
