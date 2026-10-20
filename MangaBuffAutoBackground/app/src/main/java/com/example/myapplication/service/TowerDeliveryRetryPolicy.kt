package com.example.myapplication.service

/** Bounded alarm-delivery retry policy, separated from Android APIs for unit testing. */
internal object TowerDeliveryRetryPolicy {
    const val MAX_RETRIES = 3
    const val RETRY_DELAY_MS = 60_000L

    fun nextAttempt(currentAttempt: Int): Int? =
        if (currentAttempt < MAX_RETRIES) (currentAttempt + 1).coerceAtLeast(1) else null

    fun retryAt(nowMillis: Long, currentAttempt: Int): Long? =
        nextAttempt(currentAttempt)?.let { nowMillis + RETRY_DELAY_MS }

    fun attemptsCompleted(currentAttempt: Int): Int = currentAttempt.coerceAtLeast(0) + 1
}
