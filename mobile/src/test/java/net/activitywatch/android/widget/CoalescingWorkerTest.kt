package net.activitywatch.android.widget

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CoalescingWorkerTest {

    private data class Req(val refresh: Boolean, val tags: Set<String>)

    private fun merge(a: Req, b: Req) = Req(a.refresh || b.refresh, a.tags + b.tags)

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Requests that arrive during a (possibly stuck) pass must neither start more passes
     * nor be dropped: they run once, merged, after the current pass.
     */
    @Test
    fun requestsDuringAPass_areMergedIntoOneFollowUp_notDropped() = runBlocking {
        val processed = CopyOnWriteArrayList<Req>()
        val firstStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = CoalescingWorker<Req>(scope(), ::merge) { req ->
            processed.add(req)
            if (processed.size == 1) {
                firstStarted.countDown()
                release.await() // stuck, like a blocked JNI call
            }
        }

        val first = worker.submit(Req(false, setOf("update")))
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
        val second = worker.submit(Req(true, setOf("refresh")))
        val third = worker.submit(Req(false, setOf("new-widget")))

        Thread.sleep(100)
        assertEquals("no extra pass while the first is stuck", 1, processed.size)

        release.countDown()
        withTimeout(2_000) {
            first.await()
            second.await()
            third.await()
        }
        assertEquals(
            listOf(Req(false, setOf("update")), Req(true, setOf("refresh", "new-widget"))),
            processed.toList()
        )
    }

    @Test
    fun failedPass_reportsToItsCallers_andWorkerKeepsGoing() = runBlocking {
        var calls = 0
        val worker = CoalescingWorker<Req>(scope(), ::merge) {
            calls++
            if (calls == 1) error("datastore not ready")
        }
        try {
            withTimeout(2_000) { worker.submit(Req(false, emptySet())).await() }
            fail("expected the pass's exception")
        } catch (e: IllegalStateException) {
            assertEquals("datastore not ready", e.message)
        }
        withTimeout(2_000) { worker.submit(Req(false, emptySet())).await() }
        assertEquals(2, calls)
    }

    @Test
    fun concurrentSubmits_neverRunPassesInParallel() = runBlocking {
        val active = java.util.concurrent.atomic.AtomicInteger()
        val maxActive = java.util.concurrent.atomic.AtomicInteger()
        val worker = CoalescingWorker<Req>(scope(), ::merge) {
            val now = active.incrementAndGet()
            maxActive.accumulateAndGet(now, ::maxOf)
            Thread.sleep(5)
            active.decrementAndGet()
        }
        val threads = (1..8).map {
            Thread { repeat(20) { worker.submit(Req(false, emptySet())) } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        withTimeout(5_000) { worker.submit(Req(false, emptySet())).await() }
        assertEquals(1, maxActive.get())
    }
}
