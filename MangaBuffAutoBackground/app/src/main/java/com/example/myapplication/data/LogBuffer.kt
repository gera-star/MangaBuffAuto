package com.example.myapplication.data

/**
 * The UI keeps newest log entries first. Bound the in-memory history so
 * sustained background runs cannot grow the StateFlow list without limit.
 */
internal const val MAX_LOG_ENTRIES = 2000

internal fun appendLogEntry(existing: List<LogEntry>, entry: LogEntry): List<LogEntry> {
    val retainedCount = minOf(existing.size, MAX_LOG_ENTRIES - 1)
    val next = ArrayList<LogEntry>(minOf(existing.size + 1, MAX_LOG_ENTRIES))
    next.add(entry)
    for (index in 0 until retainedCount) {
        next.add(existing[index])
    }
    return next
}
