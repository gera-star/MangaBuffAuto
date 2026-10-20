package com.example.myapplication.service

import com.example.myapplication.data.LogEntry

/**
 * Filters logs used by the service watchdog. Page lifecycle and diagnostic messages are not
 * meaningful progress: redirects to login can produce repeated PAGE_STARTED/PAGE_FINISHED events
 * without advancing the requested task.
 */
internal object ProgressLogClassifier {
    fun isMeaningful(entry: LogEntry): Boolean {
        if (entry.isError) return false
        val message = entry.message.uppercase()
        val ignored = listOf(
            "HEARTBEAT", "WATCHDOG", "METRICS", "DIAGNOSTIC", "PULSE",
            "TIMEOUT", "WAITING", "QUEUED", "GATE_WAITING", "TICK",
            "NETWORK_PAUSE", "NETWORK_RESUMED", "SERVICE_CREATED",
            "LOAD_SAMPLE", "STALLED", "STALL", "NEAR_BOTTOM", "NO_PROGRESS"
        )
        if (ignored.any { message.contains(it) }) return false

        // Do not count generic page lifecycle callbacks as progress. A login redirect loop can
        // repeatedly finish pages while the task itself remains stuck.
        val signals = listOf(
            "HOME_READY", "ROUND_START", "RESULT=",
            "BATTLE:", "TOWER:", "MINE:", "ADS:", "PROMO:", "COMMENT:",
            "READER:", "CHAPTER_COMPLETED", "SCROLL_PROGRESS", "DECK_COMMENT", "TRADE:",
            "TASK: ", "STAT: BALANCE_PARSED"
        )
        return signals.any { message.contains(it) }
    }
}
