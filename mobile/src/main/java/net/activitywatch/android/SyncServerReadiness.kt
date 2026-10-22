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
    start()
    while (!ready()) {
        val remaining = deadline - now()
        if (remaining <= 0) throw IOException("Local ActivityWatch server did not become ready within ${timeoutMs}ms")
        pause(minOf(100, remaining))
    }
}
