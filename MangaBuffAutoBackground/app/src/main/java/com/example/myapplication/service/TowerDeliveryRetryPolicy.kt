package com.example.myapplication.service

/** Bounded alarm-delivery retry policy, separated from Android APIs for unit testing. */
internal object TowerDeliveryRetryPolicy {
    const val MAX_RETRIES = 3
    const val FIRST_RETRY_DELAY_MS = 60_000L

    /** Delay before retries 1, 2 and 3: 1, 2 and 5 minutes. */
    fun retryDelayMs(currentAttempt: Int): Long? =
        when (currentAttempt.coerceAtLeast(0)) {
            0 -> FIRST_RETRY_DELAY_MS
            1 -> 120_000L
            2 -> 300_000L
            else -> null
        }

    fun nextAttempt(currentAttempt: Int): Int? =
        if (retryDelayMs(currentAttempt) != null) (currentAttempt + 1).coerceAtLeast(1) else null

    fun retryAt(nowMillis: Long, currentAttempt: Int): Long? =
        retryDelayMs(currentAttempt)?.let { nowMillis + it }

    fun attemptsCompleted(currentAttempt: Int): Int = currentAttempt.coerceAtLeast(0) + 1
}
