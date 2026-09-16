package net.activitywatch.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class OffThreadInitTest {
    @Test
    fun constructorDoesNotWaitForConstruct() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val init = OffThreadInit<String>(
            threadName = "off-thread-init-test",
            logTag = "OffThreadInitTest",
            awaitTimeoutSeconds = 2,
        ) {
            started.countDown()
            release.await()
            "ok"
        }
        assertTrue(
            "constructor blocked until construct ran",
            started.await(2, TimeUnit.SECONDS),
        )
        assertNull(init.get())
        release.countDown()
        assertEquals("ok", init.await())
    }

    @Test
    fun constructRunsOffCallingThread() {
        val caller = Thread.currentThread().id
        val constructedOn = AtomicLong(-1)
        val init = OffThreadInit(
            threadName = "off-thread-init-test",
            logTag = "OffThreadInitTest",
        ) {
            constructedOn.set(Thread.currentThread().id)
            "ok"
        }
        assertEquals("ok", init.await())
        assertNotEquals(caller, constructedOn.get())
    }

    @Test
    fun awaitOnMainThreadDoesNotBlock() {
        val release = CountDownLatch(1)
        val init = OffThreadInit(
            threadName = "off-thread-init-test",
            logTag = "OffThreadInitTest",
            isMainThread = { true },
            awaitTimeoutSeconds = 5,
        ) {
            release.await()
            "ok"
        }
        val start = System.nanoTime()
        assertNull(init.await())
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        release.countDown()
        assertTrue("await blocked on main thread for ${elapsedMs}ms", elapsedMs < 500)
    }

    @Test
    fun constructFailureReturnsNull() {
        val init = OffThreadInit<String>(
            threadName = "off-thread-init-test",
            logTag = "OffThreadInitTest",
        ) {
            error("boom")
        }
        assertNull(init.await())
    }

    @Test
    fun awaitTimesOutWhenConstructHangs() {
        val release = CountDownLatch(1)
        val init = OffThreadInit(
            threadName = "off-thread-init-test",
            logTag = "OffThreadInitTest",
            awaitTimeoutSeconds = 1,
        ) {
            release.await()
            "ok"
        }
        assertNull(init.await())
        release.countDown()
    }
}
