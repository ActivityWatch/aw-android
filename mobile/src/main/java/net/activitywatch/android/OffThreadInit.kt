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
 * callers always get null, even after init has finished, so they never run
 * JNI on the UI thread.
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
     * a construction failure as [Exception] so batch callers (EventParsingWorker,
     * IO coroutines) retry instead of silently skipping a cycle. Native
     * [Error]s such as [UnsatisfiedLinkError] are wrapped — Worker's
     * `catch (Exception)` would otherwise let them escape the retry path.
     * On the main thread it always returns null — even after init has finished
     * — so a late MainActivity/AlarmReceiver call cannot run JNI on the UI thread.
     *
     * [awaitTimeoutSeconds] is 0 (unbounded) in production. Tests may pass a
     * positive timeout to exercise the hang path without stalling the suite.
     */
    fun await(): T? {
        if (isMainThread()) {
            logW("Skipping on main thread")
            return null
        }
        value?.let { return it }
        if (awaitTimeoutSeconds > 0) {
            if (!ready.await(awaitTimeoutSeconds, TimeUnit.SECONDS)) {
                logW("Timed out waiting after ${awaitTimeoutSeconds}s")
                return value
            }
        } else {
            ready.await()
        }
        failure?.let { throw asCallerException(it) }
        return value
    }

    /**
     * EventParsingWorker (and other WorkManager workers) catch [Exception], not
     * [Throwable]. [System.loadLibrary] throws [UnsatisfiedLinkError], an
     * [Error], so rethrowing it unchanged would crash the worker instead of
     * returning Result.retry().
     */
    private fun asCallerException(failure: Throwable): Exception {
        return failure as? Exception ?: Exception("Initialization failed", failure)
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
