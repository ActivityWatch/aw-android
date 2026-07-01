package net.activitywatch.android.workers

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import net.activitywatch.android.RustInterface
import net.activitywatch.android.watcher.SessionEventWatcher
import org.json.JSONException

private const val TAG = "EventParsingWorker"

class EventParsingWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        Log.i(TAG, "Starting periodic event parsing")
        return try {
            // Guard against cursor reset to epoch-0 when the aw-server is not yet running.
            // getBucketsJSON() throws JSONException when the server returns a non-JSON error
            // response (server down or starting up). An empty {} response means the server IS
            // reachable — it just has no buckets yet (e.g. fresh install) — so we proceed and
            // let processEventsSinceLastUpdate() create the bucket.
            val ri = RustInterface(applicationContext)
            try {
                ri.getBucketsJSON()
            } catch (e: JSONException) {
                Log.w(TAG, "Server not reachable (non-JSON response); retrying later")
                return Result.retry()
            }

            val watcher = SessionEventWatcher(applicationContext)
            val eventsSent = watcher.processEventsSinceLastUpdate()
            Log.i(TAG, "Successfully processed events: $eventsSent")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error processing events; retrying later", e)
            Result.retry()
        }
    }
}
