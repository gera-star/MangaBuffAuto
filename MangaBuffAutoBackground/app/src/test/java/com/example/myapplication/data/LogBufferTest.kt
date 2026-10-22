package com.example.myapplication.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LogBufferTest {
    @Test
    fun clearsWholeHistoryWhenLimitOf2000IsReached() {
        var logs: List<LogEntry> = emptyList()

        for (index in 0 until 2000) {
            logs = appendLogEntry(logs, LogEntry(message = index.toString()))
        }
        assertEquals(2000, logs.size)
        assertEquals("1999", logs.first().message)

        logs = appendLogEntry(logs, LogEntry(message = "after-clear"))

        assertEquals(1, logs.size)
        assertEquals("after-clear", logs.first().message)
    }

    @Test
    fun putsNewestEntryAtTheFront() {
        val older = LogEntry(message = "older")
        val newest = LogEntry(message = "newest")

        val logs = appendLogEntry(listOf(older), newest)

        assertEquals(listOf("newest", "older"), logs.map { it.message })
    }
}
