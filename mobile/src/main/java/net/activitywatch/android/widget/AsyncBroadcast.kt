package net.activitywatch.android.widget

import android.util.Log
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "CategoryTimeWidget"

/**
 * Runs the work of a widget broadcast that called `goAsync()`, so that nothing can throw out
 * of the coroutine and the `PendingResult` is finished exactly once, within the broadcast's
 * time budget.
 *
 * The widget's top Play crash (`CategoryTimeWidgetProvider$onUpdate$1.invokeSuspend`,
 * `IllegalStateException`) was thrown by `PendingResult.finish()` → `sendFinished()`, which
 * only throws when the result is already finished ("Broadcast already finished"). The app
 * finished it once, so something else had finished it first: some OEM builds do this
 * themselves, notably when the work runs past the broadcast deadline (AndroidX Glance
 * catches the same exception for the same reason, b/257513022). The `finish()` call sat in
 * a `finally` outside the `try`/`catch`, so its exception escaped an IO coroutine with no
 * handler and killed the process.
 */
internal object AsyncBroadcast {
    /**
     * Foreground broadcasts get 10 s and background broadcasts 60 s before an ANR. Stay
     * under the smaller one: the system and OEM watchdogs measure from dispatch, not from
     * when this coroutine starts.
     */
    const val DEFAULT_BUDGET_MS = 8_000L

    /**
     * Calls [finish] at most once. An [IllegalStateException] from it means the system or
     * OEM framework already finished the broadcast, so it is logged, not rethrown.
     */
    internal class FinishOnce(
        private val finish: () -> Unit,
        private val log: (String, Throwable?) -> Unit = ::logError,
    ) {
        private val finished = AtomicBoolean(false)

        fun finish() {
            if (!finished.compareAndSet(false, true)) return
            try {
                finish.invoke()
            } catch (e: IllegalStateException) {
                log("Broadcast was already finished by the system", e)
            }
        }
    }

    /** Budget for [launch]'s `onFailure`, on top of the work budget (8 s + 1 s < 10 s). */
    const val FALLBACK_BUDGET_MS = 1_000L

    /** Starts [work] in a coroutine that is not a child of the caller, for [launch]. */
    fun detached(
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        work: suspend () -> Unit,
    ): Deferred<Unit> = CoroutineScope(SupervisorJob() + dispatcher).async { work() }

    /**
     * Launches [work] for a broadcast and finishes it through [finish] exactly once.
     *
     * [work] starts (or queues) the actual work in a coroutine that is not a child of this one,
     * e.g. with [detached] or a [CoalescingWorker], and the broadcast waits for the returned
     * [Deferred] for at most [budgetMs]. Detached, because the widget's work blocks in calls
     * that ignore coroutine cancellation (JNI into the Rust datastore,
     * `DatastoreStartup.awaitReady()`), so `withTimeout` around a child would still wait for
     * the child to return. Work that overruns keeps going and still renders its own result.
     *
     * If [work] throws or overruns, [onFailure] is called with the exception, or with a
     * [TimeoutException] when it overran (the work is then still running, so [onFailure] must
     * not overwrite what it renders). [onFailure] also runs detached, for at most
     * [fallbackBudgetMs], so a slow fallback can't hold the broadcast either. Anything it
     * throws is logged.
     */
    fun launch(
        finish: () -> Unit,
        budgetMs: Long = DEFAULT_BUDGET_MS,
        fallbackBudgetMs: Long = FALLBACK_BUDGET_MS,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        workDispatcher: CoroutineDispatcher = Dispatchers.IO,
        log: (String, Throwable?) -> Unit = ::logError,
        onFailure: (Throwable) -> Unit = {},
        work: () -> Deferred<Unit>,
    ): Job {
        val once = FinishOnce(finish, log)
        return scope.launch {
            try {
                val failure: Throwable? = try {
                    val pending = work()
                    val done = withTimeoutOrNull(budgetMs) { pending.await() }
                    if (done == null) TimeoutException("Widget update exceeded ${budgetMs} ms") else null
                } catch (e: Throwable) {
                    e
                }
                if (failure != null) {
                    log("Widget broadcast work failed", failure)
                    val fallback = detached(workDispatcher) {
                        try {
                            onFailure(failure)
                        } catch (e: Throwable) {
                            log("Rendering the widget fallback failed", e)
                        }
                    }
                    if (withTimeoutOrNull(fallbackBudgetMs) { fallback.await() } == null) {
                        log("Widget fallback exceeded ${fallbackBudgetMs} ms", null)
                    }
                }
            } catch (e: Throwable) {
                log("Widget broadcast wrapper failed", e)
            } finally {
                once.finish()
            }
        }
    }

    private fun logError(msg: String, e: Throwable?) {
        try {
            Log.e(TAG, msg, e)
        } catch (_: Throwable) {
            // android.util.Log is not available in JVM unit tests.
        }
    }
}
