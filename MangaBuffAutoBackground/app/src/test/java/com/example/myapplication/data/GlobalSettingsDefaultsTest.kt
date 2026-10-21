package com.example.myapplication.data

import org.junit.Assert.assertEquals
import org.junit.Test

class GlobalSettingsDefaultsTest {
    @Test
    fun readerTargetDefaultIsDistinctFromDailyProgressCounter() {
        assertEquals(10, GlobalSettings().readerChapters)
        assertEquals(0, DailyStats().readerChapters)
    }

    @Test
    fun accountsRunOneAfterAnotherByDefault() {
        assertEquals(false, GlobalSettings().parallelAccounts)
    }
}
