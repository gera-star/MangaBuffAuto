package com.example.myapplication.automation

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderPollingPolicyTest {
    @Test
    fun healthyReaderUsesLowerFrequencyPolling() {
        assertEquals(
            500L,
            ReaderPollingPolicy.nextDelayMs(idleMs = 1_000L, stallThresholdMs = 8_000L)
        )
    }

    @Test
    fun suspiciousIdleReturnsToExistingRecoveryCadence() {
        assertEquals(
            180L,
            ReaderPollingPolicy.nextDelayMs(idleMs = 4_000L, stallThresholdMs = 8_000L)
        )
    }

    @Test
    fun negativeIdleIsTreatedAsNoIdleTime() {
        assertEquals(
            500L,
            ReaderPollingPolicy.nextDelayMs(idleMs = -100L, stallThresholdMs = 8_000L)
        )
    }

    @Test
    fun invalidThresholdIsRejected() {
        try {
            ReaderPollingPolicy.nextDelayMs(idleMs = 0L, stallThresholdMs = 0L)
            throw AssertionError("Expected invalid stall threshold to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
