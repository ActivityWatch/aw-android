package ca.uqam.espaceunaw

import android.content.Context
import android.os.AsyncTask
import android.system.Os
import android.util.Log
import ca.uqam.espaceunaw.models.Event
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.threeten.bp.Instant
import java.io.File

private const val TAG = "RustInterface"

class RustInterface constructor(context: Context? = null) {

    init {
        // NOTE: This doesn't work, probably because I can't get gradle to not strip symbols on release builds
        //Os.setenv("RUST_BACKTRACE", "1", true)

        if(context != null) {
            Os.setenv("SQLITE_TMPDIR", context.cacheDir.absolutePath, true)
        }

        System.loadLibrary("aw_server")

        initialize()
        if(context != null) {
            setDataDir(context.filesDir.absolutePath)
        }
    }

    companion object {
        var serverStarted = false
    }

    private external fun initialize(): String
    private external fun greeting(pattern: String): String
    private external fun startServer(assetDir: String)
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
            serverStarted = true
            ServerTask(context).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
            Log.w(TAG, "Server started")
        }
    }

    // TODO: This probably shouldn't be an AsyncTask
    private inner class ServerTask(val context: Context) : AsyncTask<String, Nothing, Unit>() {
        override fun doInBackground(vararg inputs: String) {
            AssetExtractor.extractAssets("webui", context)

            Log.w(TAG, "Starting server...")
            val assetDir = context.cacheDir.path + File.separator + "webui"
            startServer(assetDir)
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