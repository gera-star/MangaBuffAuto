package com.example.myapplication.automation

import org.junit.Assert.assertEquals
import org.junit.Test

class DailyTargetTest {
    @Test
    fun restartResumesOnlyRemainingBattles() {
        assertEquals(4, DailyTarget.remaining(configuredTarget = 10, completedToday = 6))
    }

    @Test
    fun targetAlreadyReachedDoesNotScheduleMoreBattles() {
        assertEquals(0, DailyTarget.remaining(configuredTarget = 10, completedToday = 10))
        assertEquals(0, DailyTarget.remaining(configuredTarget = 10, completedToday = 14))
    }

    @Test
    fun negativeValuesAreClamped() {
        assertEquals(0, DailyTarget.remaining(configuredTarget = -1, completedToday = 0))
        assertEquals(10, DailyTarget.remaining(configuredTarget = 10, completedToday = -3))
    }
}
