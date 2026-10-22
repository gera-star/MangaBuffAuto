package com.example.myapplication.service

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyRouteExecutionGateTest {
    @Test
    fun sameRouteMayBeUsedConcurrently() = runBlocking {
        val gate = ProxyRouteExecutionGate()
        val first = gate.acquire("proxy-a:8080")
        val second = gate.acquire("proxy-a:8080")

        second.release()
        first.release()
    }

    @Test
    fun differentRouteWaitsUntilEveryActiveLeaseIsReleased() = runBlocking {
        val gate = ProxyRouteExecutionGate()
        val first = gate.acquire("proxy-a:8080")
        val second = gate.acquire("proxy-a:8080")
        var entered = false

        val waitingRoute = async {
            val permit = gate.acquire("proxy-b:8080")
            entered = true
            permit.release()
        }

        yield()
        assertFalse("A different route must not overlap an active route", entered)

        first.release()
        yield()
        assertFalse("One remaining lease must keep the active route pinned", entered)

        second.release()
        waitingRoute.await()
        assertTrue(entered)
    }

    @Test
    fun changingBackToTheCurrentRouteDoesNotBreakTheLease() = runBlocking {
        val gate = ProxyRouteExecutionGate()
        val first = gate.acquire("system")
        val same = gate.acquire("system")
        val otherRoute = async {
            val permit = gate.acquire("proxy-b:8080")
            permit.release()
        }

        yield()
        assertFalse(otherRoute.isCompleted)

        same.release()
        assertFalse(otherRoute.isCompleted)

        first.release()
        otherRoute.await()
    }
}
