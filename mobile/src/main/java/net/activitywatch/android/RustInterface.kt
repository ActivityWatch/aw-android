package net.activitywatch.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.util.Log
import android.widget.Toast
import net.activitywatch.android.models.Event
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.threeten.bp.Instant
import java.io.File
import java.util.concurrent.Executors

private const val TAG = "RustInterface"

class RustInterface constructor(context: Context? = null) {

    init {
        // NOTE: This doesn't work, probably because I can't get gradle to not strip symbols on release builds
        Os.setenv("RUST_BACKTRACE", "1", true)

        if(context != null) {
            Os.setenv("SQLITE_TMPDIR", context.cacheDir.absolutePath, true)
        }

        System.loadLibrary("aw_server")

        initialize()
        if(context != null) {
            // See https://developer.android.com/reference/kotlin/android/content/Context#getexternalfilesdir
            setDataDir(context.externalFilesDir.absolutePath)
        }
    }

    companion object {
        var serverStarted = false
    }

    private external fun initialize(): String
    private external fun greeting(pattern: String): String
    private external fun startServer()
    private external fun setDataDir(path: String)
    external fun getBuckets(): String
    external fun createBucket(bucket: String): String
    external fun getEvents(bucket_id: String, limit: Int): String
    external fun heartbeat(bucket_id: String, event: String, pulsetime: Double): String

    fun sayHello(to: String): String {
        return greeting(to)
    }

    fun startServerTask(context: Context) {
        if(!serverStarted) {
            // check if port 5600 is already in use
            try {
                val socket = java.net.ServerSocket(5600)
                socket.close()
            } catch(e: java.net.BindException) {
                Log.e(TAG, "Port 5600 is already in use, server probably already started")
                return
            }

            serverStarted = true
            val executor = Executors.newSingleThreadExecutor()
            val handler = Handler(Looper.getMainLooper())
            executor.execute {
                // will not block the UI thread

                // Start server
                Log.w(TAG, "Starting server...")
                startServer()

                handler.post {
                    // will run on UI thread after the task is done
                    Log.i(TAG, "Server finished")
                    serverStarted = false
                }
            }
            Log.w(TAG, "Server started")
        }
    }

    fun createBucketHelper(bucket_id: String, type: String, hostname: String = "unknown", client: String = "aw-android") {
        if(bucket_id in getBucketsJSON().keys().asSequence()) {
            Log.i(TAG, "Bucket with ID '$bucket_id', already existed. Not creating.")
        } else {
            val msg = createBucket("""{"id": "$bucket_id", "type": "$type", "hostname": "$hostname", "client": "$client"}""");
            Log.w(TAG, msg)
        }
    }

    fun heartbeatHelper(bucket_id: String, timestamp: Instant, duration: Double, data: JSONObject, pulsetime: Double = 60.0) {
        val event = Event(timestamp, duration, data)
        val msg = heartbeat(bucket_id, event.toString(), pulsetime)
        //Log.w(TAG, msg)
    }

    fun getBucketsJSON(): JSONObject {
        // TODO: Handle errors
        val json = JSONObject(getBuckets())
        if(json.length() <= 0) {
            Log.w(TAG, "Length: ${json.length()}")
        }
        return json
    }

    fun getEventsJSON(bucket_id: String, limit: Int = 0): JSONArray {
        // TODO: Handle errors
        val result = getEvents(bucket_id, limit)
        return try {
            JSONArray(result)
        } catch(e: JSONException) {
            Log.e(TAG, "Error when trying to fetch events from bucket: $result")
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
        Log.w(TAG, heartbeat("test", event, 60.0))
        Log.w(TAG, getBucketsJSON().toString(2))
        Log.w(TAG, getEventsJSON("test").toString(2))
    }
}
