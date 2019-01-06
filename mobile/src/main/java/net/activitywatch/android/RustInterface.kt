package net.activitywatch.android

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.threeten.bp.Instant

class RustInterface constructor(context: Context) {
    private val TAG = "RustGreetings"

    init {
        setAndroidDataDir(context.filesDir.absolutePath)
    }

    external fun greeting(pattern: String): String
    external fun setAndroidDataDir(path: String)
    external fun getBuckets(): String
    external fun createBucket(bucket: String): String
    external fun getEvents(bucket_id: String): String
    external fun heartbeat(bucket_id: String, event: String): String

    fun sayHello(to: String): String {
        return greeting(to)
    }

    fun getBucketsJSON(): JSONObject {
        return JSONObject(getBuckets())
    }

    fun getEventsJSON(bucket_id: String): JSONArray {
        return JSONArray(getEvents(bucket_id))
    }

    fun test() {
        Log.w(TAG, sayHello("Android"))
        Log.w(TAG, createBucket("""{"id": "test", "type": "test", "hostname": "test", "client": "test"}"""))
        Log.w(TAG, getBucketsJSON().toString(2))

        val event = """{"timestamp": "${Instant.now()}", "duration": 0, "data": {"key": "value"}}"""
        Log.w(TAG, event)
        Log.w(TAG, heartbeat("test", event))
        Log.w(TAG, getBucketsJSON().toString(2))
        Log.w(TAG, getEventsJSON("test").toString(2))
    }
}