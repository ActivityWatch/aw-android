package net.activitywatch.android

import java.io.IOException

/** Shared by foreground and alarm sync; never treat a queued server start as readiness. */
internal fun awaitSyncServer(
    start: () -> Unit,
    ready: () -> Boolean,
    now: () -> Long = { System.nanoTime() / 1_000_000 },
    pause: (Long) -> Unit = { Thread.sleep(it) },
    timeoutMs: Long = 30_000,
) {
    if (ready()) return
    val deadline = now() + timeoutMs
    try {
        start()
    } catch (e: UnsatisfiedLinkError) {
        // RustInterface is constructed on the sync executor. Linkage errors are
        // Errors, so normalize this recoverable startup failure for its existing
        // Exception handler, which persists failure and completes the callback.
        throw IOException("Local ActivityWatch server native library unavailable: ${e.message}", e)
    }
    while (!ready()) {
        val remaining = deadline - now()
        if (remaining <= 0) throw IOException("Local ActivityWatch server did not become ready within ${timeoutMs}ms")
        pause(minOf(100, remaining))
    }
}
