package net.activitywatch.android

import android.content.Context
import android.util.Log
import org.json.JSONObject

class RustGreetings constructor(context: Context) {
    private external fun greeting(pattern: String): String
    private external fun setAndroidDataDir(path: String)
    private external fun getBucketsJSONString(): String
    private external fun createBucket(bucket: String): String

    private val TAG = "RustGreetings"

    init {
        setAndroidDataDir(context.filesDir.absolutePath)
    }

    fun sayHello(to: String): String {
        return greeting(to)
    }

    fun getBuckets(): JSONObject {
        return JSONObject(getBucketsJSONString())
    }

    fun test() {
        val r = sayHello("world")
        Log.w(TAG, r)
        Log.w(TAG, getBuckets().toString(2))
        val msg = createBucket("""{"id": "test", "type": "test", "hostname": "test", "client": "test"}""")
        Log.w(TAG, msg)
        Log.w(TAG, getBuckets().toString(2))
    }
}