package com.example.myapplication.service

/**
 * Bounds automatic recovery of an account's task.
 *
 * Without a bound, a WebView that keeps losing its renderer (or a page that keeps hanging)
 * is restarted forever. At most [maxRecoveries] recoveries are allowed per key inside a
 * sliding window of [windowMs]; after that the caller must stop and report the failure.
 *
 * Time is passed in by the caller so the logic is deterministic and testable. Use a
 * monotonic clock (elapsedRealtime) in production so wall-clock changes cannot reopen
 * the window.
 */
class RecoveryBudget(
    private val maxRecoveries: Int,
    private val windowMs: Long
) {
    init {
        require(maxRecoveries > 0) { "maxRecoveries must be positive" }
        require(windowMs > 0L) { "windowMs must be positive" }
    }

    private val attempts = HashMap<String, ArrayDeque<Long>>()

    /** Records a recovery if the budget allows it. Returns false when the budget is spent. */
    @Synchronized
    fun tryConsume(key: String, nowMs: Long): Boolean {
        val window = attempts.getOrPut(key) { ArrayDeque() }
        trim(window, nowMs)
        if (window.size >= maxRecoveries) return false
        window.addLast(nowMs)
        return true
    }

    /** Recoveries still counted inside the window for [key]. */
    @Synchronized
    fun used(key: String, nowMs: Long): Int {
        val window = attempts[key] ?: return 0
        trim(window, nowMs)
        return window.size
    }

    /** A deliberate user start opens a fresh budget for that account. */
    @Synchronized
    fun reset(key: String) {
        attempts.remove(key)
    }

    @Synchronized
    fun resetAll() {
        attempts.clear()
    }

    private fun trim(window: ArrayDeque<Long>, nowMs: Long) {
        // Entries older than the window no longer count. A clock that went backwards
        // (nowMs < entry) is treated as "still inside the window", the safe direction.
        while (window.isNotEmpty() && nowMs - window.first() >= windowMs) {
            window.removeFirst()
        }
    }
}
