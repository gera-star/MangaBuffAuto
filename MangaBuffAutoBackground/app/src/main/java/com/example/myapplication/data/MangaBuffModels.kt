package com.example.myapplication.data

import java.util.UUID
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DailyStats(
    val day: String = "",
    val battles: Int = 0,
    val battleAttempts: Int = 0,
    val quiz: Int = 0,
    val ads: Int = 0,
    val mineOre: Int = 0,
    val mineExchangeOre: Int = 0,
    val mineDiamonds: Int = 0,
    val readerChapters: Int = 0,
    val comments: Int = 0
)

fun currentStatsDay(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

data class MangaBuffAccount(
    val id: String = UUID.randomUUID().toString(),
    val username: String = "",
    val cookiesJson: String? = "",
    val csrfToken: String? = "",
    val userAgent: String? = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
    val readerEnabled: Boolean = true,
    val quizEnabled: Boolean = true,
    val advEnabled: Boolean = true,
    val mineEnabled: Boolean = true,
    val commentEnabled: Boolean = true,
    val deckCommentEnabled: Boolean = false,
    val battleEnabled: Boolean = false, // По умолчанию Карточные бои = ВЫКЛ
    val lastRunTime: Long = 0L,
    val statusMessage: String? = "Готов",
    val isRunning: Boolean = false,
    val currentTask: String? = "",
    val taskProgress: Float = 0f,
    val activeMangaUrl: String? = "",
    val activeMangaTitle: String? = "",
    val diamonds: String = "0",
    val cardDrop: String = "0/10",
    val chapterProgress: String = "0/75",
    val commentProgress: String = "0/13",
    val dailyStatsDay: String = "",
    val dailyBattles: Int = 0,
    val dailyBattleAttempts: Int = 0,
    val dailyQuiz: Int = 0,
    val dailyAds: Int = 0,
    val dailyMineOre: Int = 0,
    val dailyMineExchangeOre: Int = 0,
    val dailyMineDiamonds: Int = 0,
    val dailyReaderChapters: Int = 0,
    val dailyComments: Int = 0
) {
    fun getSafeCookiesJson(): String = cookiesJson ?: ""
    fun getSafeCsrfToken(): String = csrfToken ?: ""
    fun getSafeUserAgent(): String = userAgent ?: "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    fun getSafeStatusMessage(): String {
        val msg = statusMessage ?: "Готов"
        if (
            msg.startsWith("PAGE:") ||
            msg.startsWith("READER:") ||
            msg.startsWith("MB_") ||
            msg.startsWith("CHAPTER_") ||
            msg.startsWith("SCROLL") ||
            msg.startsWith("NEXT_") ||
            msg.startsWith("BALANCE:") ||
            msg.startsWith("AUTH:") ||
            msg.startsWith("BG:") ||
            msg.startsWith("DEBUG") ||
            msg.startsWith("DIAGNOSTICS") ||
            msg.contains("at com.example.") ||
            msg.contains("Exception")
        ) {
            return if (isRunning) "Выполнение..." else "Готов"
        }
        return msg
    }
    fun getSafeCurrentTask(): String = currentTask ?: ""
    fun getSafeActiveMangaUrl(): String = activeMangaUrl ?: ""
    fun getSafeActiveMangaTitle(): String = activeMangaTitle ?: ""
}

enum class TaskType(val title: String) {
    ALL("Все задачи"),
    BATTLE("Карточные бои"),
    QUIZ("Викторина / Квиз"),
    ADS("Просмотр рекламы"),
    MINE("Шахта"),
    READER("Чтение манги"),
    COMMENT("Комментарии"),
    PROMO("Промокод")
}

enum class TaskStatus {
    IDLE, RUNNING, SUCCESS, ERROR
}

data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val username: String = "",
    val component: String = "APP",
    val message: String,
    val isError: Boolean = false
)

data class GlobalSettings(
    val quizMaxClicks: Int = 15,
    val adsCount: Int = 5,
    val mineClicks: Int = 100,
    val mineAutoUpgrade: Boolean = true,
    val mineAutoExchange: Boolean = false,
    val readerChapters: Int = 4,
    val commentCount: Int = 3,
    val battleTargetCount: Int = 20
)
