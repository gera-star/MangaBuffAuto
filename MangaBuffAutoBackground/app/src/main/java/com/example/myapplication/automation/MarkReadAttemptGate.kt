package com.example.myapplication.automation

/**
 * Single-flight bookkeeping for the "mark manga as read" UI action.
 *
 * Outcomes of one attempt can arrive from two different threads at nearly the same time:
 * the site callback comes in on the WebView JavaScript-bridge thread, while the watchdog
 * timeout runs on the main thread. Whoever consumes the in-flight flag first owns the
 * outcome of that attempt; the other signal is late or duplicate and must be ignored.
 * Without this, both signals can schedule their own retry sequence, which burns the
 * retry budget twice and reloads the title page twice.
 */
class MarkReadAttemptGate {
    private val lock = Any()
    private var inFlight = false
    private var currentGeneration = 0L

    val isInFlight: Boolean
        get() = synchronized(lock) { inFlight }

    /** Starts a new attempt and returns its generation. */
    fun begin(): Long = synchronized(lock) {
        inFlight = true
        currentGeneration += 1L
        currentGeneration
    }

    /**
     * Claims the outcome for the attempt that is currently in flight.
     * Returns false when nothing is in flight (duplicate or stale callback).
     */
    fun consumeResult(): Boolean = synchronized(lock) {
        if (inFlight) {
            inFlight = false
            true
        } else {
            false
        }
    }

    /**
     * Claims a watchdog timeout for [attemptGeneration]. Returns false when that attempt
     * already produced an outcome or has been superseded by a newer attempt.
     */
    fun consumeTimeout(attemptGeneration: Long): Boolean = synchronized(lock) {
        if (inFlight && currentGeneration == attemptGeneration) {
            inFlight = false
            true
        } else {
            false
        }
    }

    /** Drops any in-flight attempt without producing an outcome. */
    fun clear() {
        synchronized(lock) { inFlight = false }
    }
}
