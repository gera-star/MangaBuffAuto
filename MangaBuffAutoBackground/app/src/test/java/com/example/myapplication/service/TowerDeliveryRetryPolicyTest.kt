package com.example.myapplication.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TowerDeliveryRetryPolicyTest {
    @Test
    fun retriesAreBounded() {
        assertEquals(1, TowerDeliveryRetryPolicy.nextAttempt(0))
        assertEquals(2, TowerDeliveryRetryPolicy.nextAttempt(1))
        assertEquals(3, TowerDeliveryRetryPolicy.nextAttempt(2))
        assertNull(TowerDeliveryRetryPolicy.nextAttempt(3))
    }

    @Test
    fun retriesUseBoundedBackoffAndMonotonicAttemptCount() {
        assertEquals(60_123L, TowerDeliveryRetryPolicy.retryAt(123L, 0))
        assertEquals(120_123L, TowerDeliveryRetryPolicy.retryAt(123L, 1))
        assertEquals(300_123L, TowerDeliveryRetryPolicy.retryAt(123L, 2))
        assertNull(TowerDeliveryRetryPolicy.retryAt(1_123L, 3))
        assertEquals(60_000L, TowerDeliveryRetryPolicy.retryDelayMs(-1))
        assertNull(TowerDeliveryRetryPolicy.retryDelayMs(3))
        assertEquals(4, TowerDeliveryRetryPolicy.attemptsCompleted(3))
    }
}
