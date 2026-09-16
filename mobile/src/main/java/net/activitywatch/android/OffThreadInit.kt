package net.activitywatch.android

import android.os.Looper
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Runs [construct] on a dedicated worker thread so the calling thread (Activity,
 * BroadcastReceiver, AccessibilityService, …) is not blocked by JNI / `loadLibrary`.
 *
 * This is the MediaWatcher / WebWatcher pattern from aw-android#262, extracted so
 * [net.activitywatch.android.watcher.SessionEventWatcher] and
 * [net.activitywatch.android.watcher.UsageStatsWatcher] can share it.
 *
 * Worker-thread callers should use [await], which waits until construction
 * finishes (or failed) so batch processors do not drop events. Main-thread
 * callers get null if construction has not finished, so they never ANR.
 */
internal class OffThreadInit<T>(
    threadName: String,
    private val logTag: String,
    private val isMainThread: () -> Boolean = Companion::isAndroidMainThread,
    private val awaitTimeoutSeconds: Long = UNBOUNDED_AWAIT,
    construct: () -> T,
) {
    @Volatile private var value: T? = null
    @Volatile private var failure: Throwable? = null
    private val ready = CountDownLatch(1)

    init {
        thread(name = threadName) {
            try {
                value = construct()
            } catch (ex: Throwable) {
                // System.loadLibrary throws UnsatisfiedLinkError (an Error subclass).
                failure = ex
                logE("Failed to initialize: ${ex.message}")
            } finally {
                ready.countDown()
            }
        }
    }

    /** Snapshot; never waits. Null if construction is still in flight or failed. */
    fun get(): T? = value

    /**
     * Wait for construction unless this is the Android main thread.
     *
     * Off the main thread this waits until the worker finishes, then rethrows
     * a construction failure so batch callers (EventParsingWorker, IO
     * coroutines) retry instead of silently skipping a cycle. On the main
     * thread it returns null if the value is not ready yet.
     *
     * [awaitTimeoutSeconds] is 0 (unbounded) in production. Tests may pass a
     * positive timeout to exercise the hang path without stalling the suite.
     */
    fun await(): T? {
        value?.let { return it }
        if (isMainThread()) {
            logW("Not ready; skipping on main thread")
            return null
        }
        if (awaitTimeoutSeconds > 0) {
            if (!ready.await(awaitTimeoutSeconds, TimeUnit.SECONDS)) {
                logW("Timed out waiting after ${awaitTimeoutSeconds}s")
                return value
            }
        } else {
            ready.await()
        }
        failure?.let { throw it }
        return value
    }

    private fun logE(msg: String) {
        try {
            Log.e(logTag, msg)
        } catch (_: Throwable) {
            // android.util.Log is a throwing stub in JVM unit tests.
        }
    }

    private fun logW(msg: String) {
        try {
            Log.w(logTag, msg)
        } catch (_: Throwable) {
            // android.util.Log is a throwing stub in JVM unit tests.
        }
    }

    companion object {
        const val UNBOUNDED_AWAIT = 0L

        fun isAndroidMainThread(): Boolean {
            return try {
                val main = Looper.getMainLooper() ?: return false
                Looper.myLooper() == main
            } catch (_: Throwable) {
                false
            }
        }
    }
}
