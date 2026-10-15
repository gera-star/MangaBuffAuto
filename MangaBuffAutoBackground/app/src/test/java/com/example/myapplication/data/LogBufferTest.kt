package com.example.myapplication.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LogBufferTest {
    @Test
    fun keepsNewestEntriesAndCapsHistoryAt2000() {
        var logs: List<LogEntry> = emptyList()

        for (index in 0..2100) {
            logs = appendLogEntry(logs, LogEntry(message = index.toString()))
        }

        assertEquals(2000, logs.size)
        assertEquals("2100", logs.first().message)
        assertEquals("101", logs.last().message)
    }

    @Test
    fun putsNewestEntryAtTheFront() {
        val older = LogEntry(message = "older")
        val newest = LogEntry(message = "newest")

        val logs = appendLogEntry(listOf(older), newest)

        assertEquals(listOf("newest", "older"), logs.map { it.message })
    }
}
