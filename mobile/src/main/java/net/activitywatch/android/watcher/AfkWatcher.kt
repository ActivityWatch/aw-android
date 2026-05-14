package net.activitywatch.android.watcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import net.activitywatch.android.RustInterface
import org.json.JSONObject
import org.threeten.bp.Instant
import java.util.concurrent.Executors

class AfkWatcher : BroadcastReceiver() {

    companion object {
        private const val TAG = "AfkWatcher"
        private const val BUCKET_ID = "aw-watcher-android-realtime-afk"
        private const val BUCKET_TYPE = "afkstatus"

        @Volatile
        var isAfk: Boolean = false
            private set
    }

    private val executor = Executors.newSingleThreadExecutor()

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        when (action) {
            Intent.ACTION_SCREEN_OFF -> {
                Log.i(TAG, "Screen OFF → AFK")
                isAfk = true
                executor.execute { sendAfkEvent(context, true) }
            }
            Intent.ACTION_SCREEN_ON -> {
                Log.i(TAG, "Screen ON → NOT AFK")
                isAfk = false
                executor.execute { sendAfkEvent(context, false) }
            }
        }
    }

    private fun sendAfkEvent(context: Context, afk: Boolean) {
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
