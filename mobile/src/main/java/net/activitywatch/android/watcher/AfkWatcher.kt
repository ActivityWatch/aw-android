package net.activitywatch.android.watcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.util.Log
import net.activitywatch.android.RustInterface
import org.json.JSONObject
import org.threeten.bp.Instant
import java.util.concurrent.Executors

class AfkWatcher(private val context: Context) {

    companion object {
        private const val TAG = "AfkWatcher"
        private const val BUCKET_ID = "aw-watcher-android-realtime-afk"
        private const val BUCKET_TYPE = "afkstatus"

        @Volatile
        var isAfk: Boolean = false
            private set
    }

    private val executor = Executors.newSingleThreadExecutor()
    private var registered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.i(TAG, "Screen OFF → AFK")
                    isAfk = true
                    executor.execute { sendAfkEvent(true) }
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.i(TAG, "Screen ON → NOT AFK")
                    isAfk = false
                    executor.execute { sendAfkEvent(false) }
                }
            }
        }
    }

    fun register() {
        if (!registered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            context.registerReceiver(screenReceiver, filter)
            registered = true
            Log.i(TAG, "AfkWatcher registered")

            // Check initial screen state
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            isAfk = !pm.isInteractive
            Log.i(TAG, "Initial screen state: ${if (isAfk) "AFK" else "NOT AFK"}")
        }
    }

    fun unregister() {
        if (registered) {
            try {
                context.unregisterReceiver(screenReceiver)
            } catch (e: Exception) {
                Log.e(TAG, "Unregister error: ${e.message}")
            }
            registered = false
            Log.i(TAG, "AfkWatcher unregistered")
        }
    }

    private fun sendAfkEvent(afk: Boolean) {
        try {
            val ri = RustInterface(context)
            ri.createBucketHelper(BUCKET_ID, BUCKET_TYPE)

            val data = JSONObject()
            data.put("status", if (afk) "afk" else "not-afk")

            val now = Instant.now()
            ri.heartbeatHelper(BUCKET_ID, now, 0.0, data, 60.0)
            Log.d(TAG, "Sent AFK event: ${if (afk) "afk" else "not-afk"}")
        } catch (e: Exception) {
            Log.e(TAG, "sendAfkEvent error: ${e.message}")
        }
    }
}
