package net.activitywatch.android

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.threeten.bp.Instant
import java.lang.Exception

class RustInterface constructor(context: Context? = null) {
    private val TAG = "RustGreetings"

    init {
        System.loadLibrary("aw_server")

        if(context != null) {
            setAndroidDataDir(context.filesDir.absolutePath)
        }
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

    fun createBucketHelper(bucket_id: String, type: String, hostname: String = "unknown", client: String = "aw-android") {
        if(bucket_id in getBucketsJSON().keys().asSequence()) {
            Log.i(TAG, "Bucket with ID '$bucket_id', already existed. Not creating.")
        } else {
            val msg = createBucket("""{"id": "$bucket_id", "type": "$type", "hostname": "$hostname", "client": "$client"}""");
            Log.w(TAG, msg)
        }
    }

    // TODO: Implement handling of pulsetime
    fun heartbeatHelper(bucket_id: String, timestamp: Instant, duration: Double, data: JSONObject, pulsetime: Double = 60.0) {
        val event = """{"timestamp": "$timestamp", "duration": $duration, "data": $data}"""
        val msg = heartbeat(bucket_id, event)
        Log.w(TAG, msg)
    }

    fun getBucketsJSON(): JSONObject {
        // TODO: Handle errors
        return JSONObject(getBuckets())
    }

    fun getEventsJSON(bucket_id: String, limit: Int = 0): JSONArray {
        // TODO: Handle errors
        // TODO: Use limit
        return try {
            JSONArray(getEvents(bucket_id))
        } catch(e: JSONException) {
            Log.e(TAG, "Error when trying to fetch events from bucket, are you sure it exists?")
            JSONArray()
        }
    }

    fun test() {
        // TODO: Move to instrumented test
        Log.w(TAG, sayHello("Android"))
        createBucketHelper("test", "test")
        Log.w(TAG, getBucketsJSON().toString(2))

        val event = """{"timestamp": "${Instant.now()}", "duration": 0, "data": {"key": "value"}}"""
        Log.w(TAG, event)
        Log.w(TAG, heartbeat("test", event))
        Log.w(TAG, getBucketsJSON().toString(2))
        Log.w(TAG, getEventsJSON("test").toString(2))
    }
}