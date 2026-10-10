package net.activitywatch.android

import org.json.JSONObject

/**
 * One-shot reset of this device's push staging db (ActivityWatch/aw-android#296).
 *
 * Pushes made before ActivityWatch/aw-server-rust#713 left duplicate copies in
 * `{sync_dir}/{hostname}/{device_id}/test.db`. Staging is derived data, so the
 * native side deletes it and the next push rebuilds it from the local datastore.
 *
 * Kept free of Android types so the flag logic is unit-testable on the JVM.
 */
object StagingReset {
    fun prefKey(hostname: String) = "stagingResetDone_$hostname"

    sealed class Result {
        object AlreadyDone : Result()
        object Done : Result()
        data class Failed(val reason: String) : Result()
    }

    /**
     * Runs [reset] unless [isDone], and calls [markDone] only when the native
     * side reports `{"success": true}`. Never throws: a failed reset must not
     * block the sync that follows it.
     *
     * [LinkageError] is caught as well as [Exception] because a native library
     * built without `resetStaging` throws [UnsatisfiedLinkError]; letting that
     * escape would kill the sync executor before its callback clears
     * `syncInFlight`, rejecting every later sync as "already in flight".
     */
    fun runOnce(isDone: () -> Boolean, markDone: () -> Unit, reset: () -> String): Result {
        if (isDone()) return Result.AlreadyDone
        return try {
            val json = JSONObject(reset())
            if (json.optBoolean("success", false)) {
                markDone()
                Result.Done
            } else {
                Result.Failed(json.optString("error", "unknown error"))
            }
        } catch (e: Exception) {
            Result.Failed("${e.javaClass.simpleName}: ${e.message}")
        } catch (e: LinkageError) {
            Result.Failed("${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
