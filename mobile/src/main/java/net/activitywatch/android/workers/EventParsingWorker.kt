package net.activitywatch.android.workers

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import net.activitywatch.android.RustInterface
import net.activitywatch.android.watcher.SessionEventWatcher

private const val TAG = "EventParsingWorker"

class EventParsingWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        Log.i(TAG, "Starting periodic event parsing")
        return try {
            // Guard against cursor reset to epoch-0 when the aw-server is not yet running.
            // getBucketsJSON() throws JSONException on a non-JSON (error) response, and returns
            // an empty object {} when the server is up but the bucket list is genuinely empty.
            // Either way, an empty bucket list means the server isn't ready — retry later.
            val ri = RustInterface(applicationContext)
            val buckets = ri.getBucketsJSON()
            if (buckets.length() == 0) {
                Log.w(TAG, "Server not ready (no buckets returned); retrying later")
                return Result.retry()
            }

            val watcher = SessionEventWatcher(applicationContext)
            val eventsSent = watcher.processEventsSinceLastUpdate()
            Log.i(TAG, "Successfully processed events: $eventsSent")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Server unreachable or error processing events; retrying later", e)
            Result.retry()
        }
    }
}
