package com.example.myapplication.worker

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerSlotPoolTest {
    @Test
    fun differentRoutesGetDifferentWorkersAtTheSameTime() = runBlocking {
        val pool = WorkerSlotPool(4)
        val a = pool.acquire("proxy:a:1")
        val b = pool.acquire("proxy:b:2")
        val direct = pool.acquire("system")

        assertEquals(3, setOf(a.index, b.index, direct.index).size)
    }

    @Test
    fun sameRouteSharesOneWorker() = runBlocking {
        val pool = WorkerSlotPool(4)
        val first = pool.acquire("proxy:a:1")
        val second = pool.acquire("proxy:a:1")

        assertEquals(first.index, second.index)
    }

    @Test
    fun freeWorkerWithSameLastRouteIsReusedWithoutRouteChange() = runBlocking {
        val pool = WorkerSlotPool(4)
        val a = pool.acquire("proxy:a:1")
        val b = pool.acquire("proxy:b:2")
        a.release()
        b.release()

        assertEquals(b.index, pool.acquire("proxy:b:2").index)
    }

    @Test
    fun newRouteWaitsWhenEveryWorkerIsBusyWithOtherRoutes() = runBlocking {
        val pool = WorkerSlotPool(2)
        val a = pool.acquire("proxy:a:1")
        pool.acquire("proxy:b:2")
        var entered = false

        val waiting = async {
            val lease = pool.acquire("proxy:c:3")
            entered = true
            lease
        }
        repeat(5) { yield() }
        assertFalse(entered)

        a.release()
        val lease = waiting.await()
        assertTrue(entered)
        assertEquals(a.index, lease.index)
        assertNotEquals("proxy:a:1", lease.route)
    }

    @Test
    fun releaseIsIdempotent() = runBlocking {
        val pool = WorkerSlotPool(1)
        val lease = pool.acquire("system")
        lease.release()
        lease.release()

        pool.acquire("proxy:x:1").release()
    }
}
