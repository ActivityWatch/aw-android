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
     * [Throwable] (not just [Exception]) is caught because the sync executor
     * only catches [Exception]; any [Error] escaping here would leave
     * `syncInFlight` permanently set, rejecting every later sync as
     * "already in flight". [UnsatisfiedLinkError] (a native lib built without
     * `resetStaging`) and hypothetical JVM errors like [AssertionError] are both
     * mapped to [Result.Failed] so the sync that follows still runs.
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
        } catch (e: Throwable) {
            Result.Failed("${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
