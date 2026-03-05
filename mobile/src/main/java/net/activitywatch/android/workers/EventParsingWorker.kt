package net.activitywatch.android.workers

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import net.activitywatch.android.watcher.SessionEventWatcher

private const val TAG = "EventParsingWorker"

class EventParsingWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        Log.i(TAG, "Starting periodic event parsing")
        return try {
            val watcher = SessionEventWatcher(applicationContext)
            val eventsSent = watcher.processEventsSinceLastUpdate()
            Log.i(TAG, "Successfully processed events: $eventsSent")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error processing events", e)
            Result.retry()
        }
    }
}
