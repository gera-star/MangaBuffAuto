package com.example.myapplication.automation

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** A finite cleanup envelope that remains active after the caller's Job is cancelled. */
internal object StopCleanupBarrier {
    const val DEFAULT_TIMEOUT_MS = 15_000L

    suspend fun execute(timeoutMs: Long = DEFAULT_TIMEOUT_MS, cleanup: suspend () -> Unit) {
        require(timeoutMs > 0L) { "timeoutMs must be positive" }
        withContext(NonCancellable) { withTimeout(timeoutMs) { cleanup() } }
    }
}
