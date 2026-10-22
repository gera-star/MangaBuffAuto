package com.example.myapplication.data

/**
 * The UI keeps newest log entries first. When the history reaches this limit the whole
 * buffer is cleared, so sustained background runs cannot grow the StateFlow list.
 */
internal const val MAX_LOG_ENTRIES = 2000

internal fun appendLogEntry(existing: List<LogEntry>, entry: LogEntry): List<LogEntry> {
    // Достигли лимита: очищаем журнал полностью и начинаем с новой записи.
    if (existing.size >= MAX_LOG_ENTRIES) return listOf(entry)
    val next = ArrayList<LogEntry>(existing.size + 1)
    next.add(entry)
    next.addAll(existing)
    return next
}
