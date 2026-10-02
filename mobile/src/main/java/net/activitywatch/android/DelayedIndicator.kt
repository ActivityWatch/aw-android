package net.activitywatch.android

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long an import may run before the "importing" banner appears, so a quick catch-up doesn't flash one. */
internal const val IMPORT_INDICATOR_DELAY_MS = 1_000L

/**
 * Run [block], showing an indicator only if it is still running after [delayMs]
 * (ActivityWatch/aw-android#129: a long usage-history import left the dashboard
 * empty with no hint that work was going on).
 *
 * [hide] runs if and only if [show] ran, also when [block] throws or the caller
 * is cancelled.
 */
internal suspend fun <T> withDelayedIndicator(
    delayMs: Long,
    show: () -> Unit,
    hide: () -> Unit,
    block: suspend () -> T,
): T = coroutineScope {
    var shown = false
    val timer = launch {
        delay(delayMs)
        shown = true
        show()
    }
    try {
        block()
    } finally {
        timer.cancel()
        if (shown) hide()
    }
}
