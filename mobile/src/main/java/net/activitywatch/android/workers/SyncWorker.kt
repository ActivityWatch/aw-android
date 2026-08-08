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
    override suspend fun doWork(): Result {
        val syncInterface = SyncInterface(applicationContext)
        return suspendCancellableCoroutine { continuation ->
            // When WorkManager stops or cancels this worker, propagate the cancellation to the
            // SyncInterface executor so the SAF mirror thread is also interrupted promptly.
            // Without this, the executor continues copying files after the coroutine is cancelled,
            // leaving a truncated SAF write running without WorkManager's lifecycle protection.
            continuation.invokeOnCancellation { syncInterface.cancel() }

            try {
                syncInterface.syncBothAndMirrorAsync { success, message ->
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
}
