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
    private fun WorkerSlotPool.Lease.closeVerified() {
        markVerifiedClosed()
        release()
    }

    @Test
    fun differentRoutesReserveDifferentWorkersAtTheSameTime() = runBlocking {
        val pool = WorkerSlotPool(4)
        val a = pool.acquire("proxy:a:1")
        val b = pool.acquire("proxy:b:2")
        val direct = pool.acquire("system")
        assertEquals(3, setOf(a.index, b.index, direct.index).size)
        a.closeVerified()
        b.closeVerified()
        direct.closeVerified()
    }

    @Test
    fun sameRouteNeverSharesAnActiveWorkerLease() = runBlocking {
        val pool = WorkerSlotPool(4)
        val first = pool.acquire("proxy:a:1")
        val second = pool.acquire("proxy:a:1")
        assertNotEquals(first.index, second.index)
        first.closeVerified()
        second.closeVerified()
    }

    @Test
    fun freeWorkerWithSameLastRouteIsReusedAfterVerifiedClose() = runBlocking {
        val pool = WorkerSlotPool(4)
        val a = pool.acquire("proxy:a:1")
        val b = pool.acquire("proxy:b:2")
        val bIndex = b.index
        a.closeVerified()
        b.closeVerified()
        val next = pool.acquire("proxy:b:2")
        assertEquals(bIndex, next.index)
        next.closeVerified()
    }

    @Test
    fun newRouteWaitsUntilAnActiveWorkerIsVerifiedClosed() = runBlocking {
        val pool = WorkerSlotPool(2)
        val a = pool.acquire("proxy:a:1")
        val b = pool.acquire("proxy:b:2")
        var entered = false
        val waiting = async {
            val lease = pool.acquire("proxy:c:3")
            entered = true
            lease
        }
        repeat(5) { yield() }
        assertFalse(entered)
        a.closeVerified()
        val next = waiting.await()
        assertTrue(entered)
        assertEquals(a.index, next.index)
        assertNotEquals("proxy:a:1", next.route)
        b.closeVerified()
        next.closeVerified()
    }

    @Test
    fun releaseWithoutStopConfirmationQuarantinesWorker() = runBlocking {
        val pool = WorkerSlotPool(1)
        val lease = pool.acquire("proxy:a:1")
        val index = lease.index
        lease.release()
        assertTrue(pool.isQuarantined(index))
        assertEquals("STOP_NOT_CONFIRMED", pool.quarantineReason(index))
        var failed = false
        try {
            pool.acquire("proxy:b:2")
        } catch (_: WorkerPoolUnavailableException) {
            failed = true
        }
        assertTrue("A quarantined worker must not be reused", failed)
    }

    @Test
    fun explicitQuarantineCannotBeOverriddenByLateConfirmation() = runBlocking {
        val pool = WorkerSlotPool(1)
        val lease = pool.acquire("proxy:a:1")
        val index = lease.index
        lease.quarantine("STOP_ACK_TIMEOUT")
        lease.markVerifiedClosed()
        lease.release()
        assertTrue(pool.isQuarantined(index))
        assertEquals("STOP_ACK_TIMEOUT", pool.quarantineReason(index))
    }

    @Test
    fun releaseIsIdempotentAfterVerifiedStop() = runBlocking {
        val pool = WorkerSlotPool(1)
        val lease = pool.acquire("system")
        lease.markVerifiedClosed()
        lease.release()
        lease.release()
        val next = pool.acquire("proxy:x:1")
        next.closeVerified()
    }
}
