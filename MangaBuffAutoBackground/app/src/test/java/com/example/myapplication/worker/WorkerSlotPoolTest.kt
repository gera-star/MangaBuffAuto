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
    private fun WorkerSlotPool.Lease.stopCleanly() {
        transition(WorkerSlotState.STOPPING)
        markVerifiedClosed()
        release()
    }

    @Test
    fun workerStateMustReachVerifiedClosedBeforeFree() = runBlocking {
        val pool = WorkerSlotPool(1)
        val lease = pool.acquire("system")
        assertEquals(WorkerSlotState.RESERVED, pool.state(lease.index))
        lease.transition(WorkerSlotState.PROXY_READY)
        lease.transition(WorkerSlotState.RUNNING)
        lease.transition(WorkerSlotState.STOPPING)
        lease.markVerifiedClosed()
        assertEquals(WorkerSlotState.VERIFIED_CLOSED, pool.state(lease.index))
        lease.release()
        assertEquals(WorkerSlotState.FREE, pool.state(lease.index))
    }

    @Test
    fun differentRoutesReserveDifferentWorkers() = runBlocking {
        val pool = WorkerSlotPool(4)
        val leases = listOf(pool.acquire("proxy:a:1"), pool.acquire("proxy:b:2"), pool.acquire("system"))
        assertEquals(3, leases.map { it.index }.distinct().size)
        leases.forEach { it.stopCleanly() }
    }

    @Test
    fun sameRouteNeverSharesAnActiveWorkerLease() = runBlocking {
        val pool = WorkerSlotPool(4)
        val a = pool.acquire("proxy:a:1")
        val b = pool.acquire("proxy:a:1")
        assertNotEquals(a.index, b.index)
        a.stopCleanly()
        b.stopCleanly()
    }

    @Test
    fun newRouteWaitsForVerifiedClose() = runBlocking {
        val pool = WorkerSlotPool(1)
        val a = pool.acquire("proxy:a:1")
        var entered = false
        val waiting = async {
            val lease = pool.acquire("proxy:b:2")
            entered = true
            lease
        }
        repeat(5) { yield() }
        assertFalse(entered)
        a.stopCleanly()
        val b = waiting.await()
        assertTrue(entered)
        assertEquals(a.index, b.index)
        assertNotEquals(a.route, b.route)
        b.stopCleanly()
    }

    @Test
    fun releaseWithoutStopConfirmationQuarantinesWorker() = runBlocking {
        val pool = WorkerSlotPool(1)
        val lease = pool.acquire("proxy:a:1")
        lease.release()
        assertEquals(WorkerSlotState.QUARANTINED, pool.state(lease.index))
        assertEquals("STOP_NOT_CONFIRMED", pool.quarantineReason(lease.index))
        var unavailable = false
        try { pool.acquire("proxy:b:2") } catch (_: WorkerPoolUnavailableException) { unavailable = true }
        assertTrue(unavailable)
    }

    @Test
    fun quarantineCannotBeOverriddenByLateConfirmation() = runBlocking {
        val pool = WorkerSlotPool(1)
        val lease = pool.acquire("proxy:a:1")
        lease.quarantine("STOP_ACK_TIMEOUT")
        lease.transition(WorkerSlotState.STOPPING)
        lease.markVerifiedClosed()
        lease.release()
        assertEquals(WorkerSlotState.QUARANTINED, pool.state(lease.index))
        assertEquals("STOP_ACK_TIMEOUT", pool.quarantineReason(lease.index))
    }

    @Test
    fun repeatedReleaseIsIdempotentAfterConfirmedStop() = runBlocking {
        val pool = WorkerSlotPool(1)
        val lease = pool.acquire("system")
        lease.stopCleanly()
        lease.release()
        assertEquals(WorkerSlotState.FREE, pool.state(lease.index))
    }
}
