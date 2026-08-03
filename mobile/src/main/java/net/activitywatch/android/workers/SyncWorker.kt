package net.activitywatch.android.workers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.suspendCancellableCoroutine
import net.activitywatch.android.SyncInterface
import kotlin.coroutines.resume

private const val TAG = "SyncWorker"

/** Keeps alarm-triggered sync and SAF mirroring inside WorkManager's process lifecycle. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = suspendCancellableCoroutine { continuation ->
        try {
            SyncInterface(applicationContext).syncBothAndMirrorAsync { success, message ->
                if (!continuation.isActive) return@syncBothAndMirrorAsync

                if (success) {
                    Log.i(TAG, "Automatic sync completed successfully: $message")
                    continuation.resume(Result.success())
                } else {
                    Log.w(TAG, "Automatic sync failed: $message")
                    continuation.resume(Result.failure())
                }
            }
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "aw-sync native library unavailable; skipping sync", e)
            continuation.resume(Result.failure())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to perform sync", e)
            continuation.resume(Result.failure())
        }
    }
}
