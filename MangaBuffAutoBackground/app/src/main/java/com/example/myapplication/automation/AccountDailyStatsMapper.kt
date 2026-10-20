package com.example.myapplication.automation

import com.example.myapplication.data.DailyStats
import com.example.myapplication.data.MangaBuffAccount

/**
 * Restores the account's persisted daily counters when its automation engine is recreated.
 * DailyStats are stored with the account, so a process/engine restart must not reset battle
 * attempts and accidentally exceed the configured daily target.
 */
internal object AccountDailyStatsMapper {
    fun fromAccount(account: MangaBuffAccount, today: String): DailyStats {
        if (account.dailyStatsDay != today) return DailyStats(day = today)

        return DailyStats(
            day = account.dailyStatsDay,
            battles = account.dailyBattles,
            battleAttempts = account.dailyBattleAttempts,
            quiz = account.dailyQuiz,
            ads = account.dailyAds,
            mineOre = account.dailyMineOre,
            mineExchangeOre = account.dailyMineExchangeOre,
            mineDiamonds = account.dailyMineDiamonds,
            readerChapters = account.dailyReaderChapters,
            comments = account.dailyComments,
            deckComments = account.dailyDeckComments
        )
    }
}
