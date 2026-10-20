package com.example.myapplication.automation

import com.example.myapplication.data.MangaBuffAccount
import org.junit.Assert.assertEquals
import org.junit.Test

class AccountDailyStatsMapperTest {
    @Test
    fun engineRestartRestoresTodaysBattleAttempts() {
        val account = MangaBuffAccount(
            id = "account-A",
            dailyStatsDay = "20261020",
            dailyBattles = 3,
            dailyBattleAttempts = 7
        )

        val restored = AccountDailyStatsMapper.fromAccount(account, today = "20261020")

        assertEquals(3, restored.battles)
        assertEquals(7, restored.battleAttempts)
        assertEquals(3, DailyTarget.remaining(configuredTarget = 10, completedToday = restored.battleAttempts))
    }

    @Test
    fun newDayStartsWithZeroCountersInsteadOfYesterdayTotals() {
        val account = MangaBuffAccount(
            id = "account-A",
            dailyStatsDay = "20261019",
            dailyBattles = 3,
            dailyBattleAttempts = 7
        )

        val restored = AccountDailyStatsMapper.fromAccount(account, today = "20261020")

        assertEquals("20261020", restored.day)
        assertEquals(0, restored.battles)
        assertEquals(0, restored.battleAttempts)
    }
}
