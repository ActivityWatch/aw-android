package net.activitywatch.android.widget

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch

/**
 * Runs requests one at a time, merging the ones that arrive while a pass is running into a
 * single follow-up pass.
 *
 * Widget updates block in calls that ignore cancellation (JNI into the Rust datastore), and a
 * broadcast stops waiting for them after its budget. Starting new work for every broadcast
 * would pile stuck jobs onto `Dispatchers.IO` while the datastore is slow. Joining the running
 * pass instead would drop what the later broadcast asked for (a refresh's usage re-parse, or
 * a widget added meanwhile). So there is at most one running pass and one queued follow-up,
 * and the follow-up carries every request [merge]d into it.
 *
 * [submit] returns a [Deferred] that completes when the pass covering the request has ended,
 * or completes exceptionally with what that pass threw.
 */
internal class CoalescingWorker<R : Any>(
    private val scope: CoroutineScope,
    private val merge: (R, R) -> R,
    private val process: suspend (R) -> Unit,
) {
    private val lock = Any()
    private var running = false
    private var pending: R? = null
    private var pendingResult: CompletableDeferred<Unit>? = null

    fun submit(request: R): Deferred<Unit> = synchronized(lock) {
        if (!running) {
            running = true
            val result = CompletableDeferred<Unit>()
            scope.launch { drain(request, result) }
            result
        } else {
            pending = pending?.let { merge(it, request) } ?: request
            pendingResult ?: CompletableDeferred<Unit>().also { pendingResult = it }
        }
    }

    private suspend fun drain(first: R, firstResult: CompletableDeferred<Unit>) {
        var request = first
        var result = firstResult
        while (true) {
            try {
                process(request)
                result.complete(Unit)
            } catch (e: Throwable) {
                result.completeExceptionally(e)
            }
            synchronized(lock) {
                val next = pending
                val nextResult = pendingResult
                if (next == null || nextResult == null) {
                    running = false
                    return
                }
                pending = null
                pendingResult = null
                request = next
                result = nextResult
            }
        }
    }
}
