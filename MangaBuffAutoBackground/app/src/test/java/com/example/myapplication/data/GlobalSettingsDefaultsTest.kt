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

    @Test
    fun readerTargetAllowsUpTo500Chapters() {
        assertEquals(500, GlobalSettings.MAX_READER_CHAPTERS)
        assertEquals(10, GlobalSettings().readerChapters)
    }

    @Test
    fun readerSpeedCyclesX2X3X4AndOff() {
        assertEquals(2, ReaderSpeedMode.next(ReaderSpeedMode.OFF))
        assertEquals(3, ReaderSpeedMode.next(2))
        assertEquals(4, ReaderSpeedMode.next(3))
        assertEquals(ReaderSpeedMode.OFF, ReaderSpeedMode.next(4))
        assertEquals("−Х2", ReaderSpeedMode.label(ReaderSpeedMode.OFF))
        assertEquals("−Х3", ReaderSpeedMode.label(3))
    }

    @Test
    fun oldSavedHalfSpeedSettingMigratesToX2() {
        assertEquals(2, ReaderSpeedMode.normalize(0, legacyHalfSpeed = true))
        assertEquals(ReaderSpeedMode.OFF, ReaderSpeedMode.normalize(0, legacyHalfSpeed = false))
    }
}
