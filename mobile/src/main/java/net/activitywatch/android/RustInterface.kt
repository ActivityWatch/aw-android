package net.activitywatch.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.system.Os
import android.util.Log
import java.util.concurrent.Executors
import net.activitywatch.android.models.Event
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.threeten.bp.Instant

private const val TAG = "RustInterface"

class RustInterface constructor(context: Context? = null) {

    private val appContext: Context? = context?.applicationContext

    init {
        // NOTE: This doesn't work, probably because I can't get gradle to not strip symbols on
        // release builds
        Os.setenv("RUST_BACKTRACE", "1", true)

        if (context != null) {
            Os.setenv("SQLITE_TMPDIR", context.cacheDir.absolutePath, true)
        }

        System.loadLibrary("aw_server")

        initialize()
        if (context != null) {
            setDataDir(context.filesDir.absolutePath)
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
        if (!serverStarted) {
            // check if port 5600 is already in use
            try {
                val socket = java.net.ServerSocket(5600)
                socket.close()
            } catch (e: java.net.BindException) {
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

    fun createBucketHelper(bucket_id: String, type: String, client: String = "aw-android") {
        val context =
                appContext
                        ?: throw IllegalStateException(
                                "Context is required but was not provided during initialization"
                        )
        val hostname = getDeviceName(context)
        if (bucket_id in getBucketsJSON().keys().asSequence()) {
            Log.i(TAG, "Bucket with ID '$bucket_id', already existed. Not creating.")
        } else {
            val msg =
                    createBucket(
                            """{"id": "$bucket_id", "type": "$type", "hostname": "$hostname", "client": "$client"}"""
                    )
            Log.w(TAG, msg)
        }
    }

    /**
     * Send a heartbeat event that may be merged with nearby events.
     *
     * Heartbeats are useful for:
     * - Live tracking where events are sent continuously
     * - Situations where event merging is desired
     *
     * However, for app usage tracking, heartbeats can cause data loss due to:
     * - Events being merged incorrectly
     * - Zero-duration events being created
     * - Significant underreporting (up to 97% data loss observed)
     *
     * @param bucket_id The bucket to send the heartbeat to
     * @param timestamp The event timestamp
     * @param duration The event duration in seconds
     * @param data Event metadata
     * @param pulsetime Time window for merging events (default: 60 seconds)
     */
    fun heartbeatHelper(
            bucket_id: String,
            timestamp: Instant,
            duration: Double,
            data: JSONObject,
            pulsetime: Double = 60.0
    ) {
        val event = Event(timestamp, duration, data)
        val msg = heartbeat(bucket_id, event.toString(), pulsetime)
        // Log.w(TAG, msg)
    }

    /**
     * Insert a discrete event that will not be merged with other events.
     *
     * This method is preferred for accurate app usage tracking because:
     * - Each event represents a complete app session with precise start time and duration
     * - Events are not merged or modified by the heartbeat system
     * @param bucket_id The bucket to insert the event into
     * @param timestamp The exact start time of the event
     * @param duration The precise duration in seconds
     * @param data Event metadata (app name, package, etc.)
     */
    fun insertEvent(bucket_id: String, timestamp: Instant, duration: Double, data: JSONObject) {
        val event = Event(timestamp, duration, data)
        val msg = heartbeat(bucket_id, event.toString(), 0.0)
    }

    fun getBucketsJSON(): JSONObject {
        // TODO: Handle errors
        val json = JSONObject(getBuckets())
        if (json.length() <= 0) {
            Log.w(TAG, "Length: ${json.length()}")
        }
        return json
    }

    fun getEventsJSON(bucket_id: String, limit: Int = 0): JSONArray {
        // TODO: Handle errors
        val result = getEvents(bucket_id, limit)
        return try {
            JSONArray(result)
        } catch (e: JSONException) {
            Log.e(TAG, "Error when trying to fetch events from bucket: $result")
            JSONArray()
        }
    }
    fun getDeviceName(context: Context): String {
        return Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
                ?: android.os.Build.MODEL ?: "Unknown"
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
