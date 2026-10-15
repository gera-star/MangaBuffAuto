package com.example.myapplication.automation

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkReadAttemptGateTest {

    @Test
    fun duplicateResultIsIgnoredAfterFirstIsConsumed() {
        val gate = MarkReadAttemptGate()
        gate.begin()

        assertTrue(gate.consumeResult())
        assertFalse(gate.consumeResult())
        assertFalse(gate.isInFlight)
    }

    @Test
    fun resultWithoutAnAttemptIsIgnored() {
        val gate = MarkReadAttemptGate()

        assertFalse(gate.consumeResult())
    }

    @Test
    fun timeoutAfterResultIsIgnored() {
        val gate = MarkReadAttemptGate()
        val generation = gate.begin()

        assertTrue(gate.consumeResult())
        assertFalse(gate.consumeTimeout(generation))
    }

    @Test
    fun lateResultAfterTimeoutIsIgnored() {
        val gate = MarkReadAttemptGate()
        val generation = gate.begin()

        assertTrue(gate.consumeTimeout(generation))
        assertFalse(gate.consumeResult())
    }

    @Test
    fun staleTimeoutCannotConsumeANewerAttempt() {
        val gate = MarkReadAttemptGate()
        val first = gate.begin()
        assertTrue(gate.consumeResult())

        val second = gate.begin()

        assertFalse(gate.consumeTimeout(first))
        assertTrue(gate.isInFlight)
        assertTrue(gate.consumeTimeout(second))
    }

    @Test
    fun clearDropsTheAttemptWithoutAnOutcome() {
        val gate = MarkReadAttemptGate()
        val generation = gate.begin()

        gate.clear()

        assertFalse(gate.isInFlight)
        assertFalse(gate.consumeResult())
        assertFalse(gate.consumeTimeout(generation))
    }

    @Test
    fun exactlyOneOfCallbackAndWatchdogWinsUnderContention() {
        repeat(500) {
            val gate = MarkReadAttemptGate()
            val generation = gate.begin()
            val winners = AtomicInteger(0)
            val start = CountDownLatch(1)
            val done = CountDownLatch(2)

            val callback = Thread {
                start.await()
                if (gate.consumeResult()) winners.incrementAndGet()
                done.countDown()
            }
            val watchdog = Thread {
                start.await()
                if (gate.consumeTimeout(generation)) winners.incrementAndGet()
                done.countDown()
            }
            callback.start()
            watchdog.start()
            start.countDown()
            done.await()

            assertEquals(1, winners.get())
        }
    }
}
