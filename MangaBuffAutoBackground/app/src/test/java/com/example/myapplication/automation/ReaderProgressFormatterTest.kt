package com.example.myapplication.automation

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderProgressFormatterTest {
    @Test
    fun displaysConfiguredSessionTargetRatherThanMangaChapterCount() {
        assertEquals("0 / 10", ReaderProgressFormatter.format(completed = 0, target = 10))
        assertEquals("1 / 10", ReaderProgressFormatter.format(completed = 1, target = 10))
        assertEquals("10 / 10", ReaderProgressFormatter.format(completed = 10, target = 10))
    }

    @Test
    fun clampsInvalidProgressAndHandlesMissingTarget() {
        assertEquals("0 / ?", ReaderProgressFormatter.format(completed = -1, target = 0))
        assertEquals("2 / ?", ReaderProgressFormatter.format(completed = 2, target = -5))
    }
}
