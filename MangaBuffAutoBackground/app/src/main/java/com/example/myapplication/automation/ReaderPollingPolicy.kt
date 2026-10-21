package com.example.myapplication.automation

/**
 * Chooses the native polling cadence for the background reader.
 *
 * Healthy reading uses a lower-frequency tick. Once the reader has made no
 * progress for half the stall deadline, polling returns to the existing fast
 * cadence to detect/recover from a stalled WebView.
 */
internal object ReaderPollingPolicy {
    fun nextDelayMs(
        idleMs: Long,
        stallThresholdMs: Long,
        normalIntervalMs: Long = 500L,
        recoveryIntervalMs: Long = 180L
    ): Long {
        require(stallThresholdMs > 0L) { "stallThresholdMs must be positive" }
        require(normalIntervalMs > 0L) { "normalIntervalMs must be positive" }
        require(recoveryIntervalMs > 0L) { "recoveryIntervalMs must be positive" }

        return if (idleMs.coerceAtLeast(0L) >= stallThresholdMs / 2L) {
            recoveryIntervalMs
        } else {
            normalIntervalMs
        }
    }
}
