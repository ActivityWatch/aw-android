package net.activitywatch.android.widget

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

private const val TAG = "CategoryTimeWidget"

class CategoryTimeWidgetWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "WorkManager: Updating widget in background")
        return try {
            CategoryTimeWidgetUpdater.updateAllWidgets(context)
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "WorkManager: Error updating widget", e)
            Result.retry()
        }
    }
}
