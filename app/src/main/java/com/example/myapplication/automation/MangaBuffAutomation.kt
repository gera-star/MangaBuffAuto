package com.example.myapplication.automation

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.myapplication.data.GlobalSettings
import com.example.myapplication.data.LogEntry
import com.example.myapplication.data.MangaBuffAccount
import com.example.myapplication.data.DailyStats
import com.example.myapplication.data.currentStatsDay
import com.example.myapplication.data.TaskType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

enum class ReaderScrollMode {
    NATIVE_MANGABUFF,
    BOT_HUMAN
}

sealed class TaskResult {
    data object Success : TaskResult()
    data object Skipped : TaskResult()
    data object Cancelled : TaskResult()
    data class Failed(val reason: String) : TaskResult()
    data class Timeout(val reason: String) : TaskResult()
}

sealed interface ReaderResult {
    data class ChapterRead(
        val gifts: Int,
        val chapterUrl: String,
        val chapterId: String,
        val nextChapterUrl: String = ""
    ) : ReaderResult

    data class MangaCompleted(
        val mangaUrl: String,
        val title: String
    ) : ReaderResult

    data class MangaSkipped(
        val mangaUrl: String,
        val title: String
    ) : ReaderResult

    data object MangaAlreadyCompleted : ReaderResult
    data object NoMangaAvailable : ReaderResult
    data class Failed(val reason: String) : ReaderResult
    data object Cancelled : ReaderResult
}

data class ChapterContext(
    val accountId: String,
    val runId: String,
    val taskRunId: String,
    val chapterRunId: String,
    val documentId: String,
    val mangaId: String = "",
    val chapterId: String = "",
    val chapterNumber: String?,
    val volume: String = "1",
    val mangaSlug: String,
    val mangaTitle: String?,
    val mangaUrl: String,
    val actualChapterUrl: String,
    /** Server reading-quest value captured before this chapter started. */
    val readQuestBefore: String = "0/75",
    val startedAt: Long = SystemClock.elapsedRealtime(),
    var completionProcessed: Boolean = false
)

class MangaBuffAutomation(
    private val context: Context,
    private val onLog: (LogEntry) -> Unit,
    private val onAccountStatusUpdate: (
        accountId: String,
        statusMessage: String,
        isRunning: Boolean,
        currentTask: String,
        progress: Float
    ) -> Unit,
    private val onMangaActiveUrlUpdate: (
        accountId: String,
        url: String,
        title: String
    ) -> Unit = { _, _, _ -> },
    private val onAccountStatsUpdate: (
        accountId: String,
        diamonds: String,
        cardDrop: String,
        chapters: String,
        comments: String
    ) -> Unit = { _, _, _, _, _ -> },
    private val onDailyStatsUpdate: (accountId: String, stats: DailyStats) -> Unit = { _, _ -> }
) {

    companion object {
        private const val READER_END_STABLE_MS = 3_000L

        private const val QUIZ_QUESTION_RESULT_DELAY_MS = 1500L
        private const val QUIZ_NEXT_QUESTION_DELAY_MS = 3500L

        private const val ADS_NEXT_DELAY_MS = 4000L
        private const val MINE_COMPLETION_DELAY_MS = 2000L

        private const val COMMENT_DELAY_MS = 4000L

        private const val BATTLE_COOLDOWN_MS = 2000L

        private const val BALANCE_WATCHDOG_MS = 15_000L
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Performs a short synthetic finger gesture and then uses WebView's native
     * fling path. This follows Android's touch/velocity model instead of
     * scheduling a long series of delayed MotionEvents.
     *
     * WebView.flingScroll() must run on the WebView's creation thread (main).
     */
    /**
     * Real WebView tap fallback for fullscreen ads whose close control is
     * hidden from page JavaScript by a cross-origin iframe or closed shadow root.
     */
    private fun dispatchNativeTap(
        accountUsername: String,
        webView: WebView,
        x: Float,
        y: Float
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { dispatchNativeTap(accountUsername, webView, x, y) }
            return
        }
        if (!webView.isAttachedToWindow) return

        val density = webView.resources.displayMetrics.density.coerceAtLeast(1f)
        /*
         * JS coordinates are CSS pixels. Convert first, then clamp to the real
         * attached WebView bounds so density differences cannot move the tap
         * outside the actual WebView.
         */
        val maxX = (webView.width - 2).coerceAtLeast(1).toFloat()
        val maxY = (webView.height - 2).coerceAtLeast(1).toFloat()
        val px = (x * density).coerceIn(1f, maxX)
        val py = (y * density).coerceIn(1f, maxY)
        log(
            accountUsername,
            "ADS: NATIVE_CLOSE_TAP_DISPATCH px=" + px + " py=" + py +
                " webView=" + webView.width + "x" + webView.height + " density=" + density
        )
        val downTime = SystemClock.uptimeMillis()

        // Do not send DOWN+UP back-to-back. Chromium/WebView can drop a synthetic
        // tap when both events are dispatched in the same main-thread turn. Keep
        // the full motion set alive for a short, realistic finger press.
        val downEvent = MotionEvent.obtain(
            downTime,
            downTime,
            MotionEvent.ACTION_DOWN,
            px,
            py,
            0
        ).apply {
            source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        }

        val downConsumed = try {
            webView.dispatchTouchEvent(downEvent)
        } finally {
            downEvent.recycle()
        }

        log(
            accountUsername,
            "ADS: NATIVE_CLOSE_DOWN consumed=$downConsumed source=TOUCHSCREEN"
        )

        mainHandler.postDelayed({
            if (!webView.isAttachedToWindow) {
                log(accountUsername, "ADS: NATIVE_CLOSE_UP_SKIPPED webview_detached", true)
                return@postDelayed
            }

            val moveTime = SystemClock.uptimeMillis()
            val moveEvent = MotionEvent.obtain(
                downTime,
                moveTime,
                MotionEvent.ACTION_MOVE,
                px,
                py,
                0
            ).apply {
                source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            }
            try {
                webView.dispatchTouchEvent(moveEvent)
            } finally {
                moveEvent.recycle()
            }

            val upTime = SystemClock.uptimeMillis()
            val upEvent = MotionEvent.obtain(
                downTime,
                upTime,
                MotionEvent.ACTION_UP,
                px,
                py,
                0
            ).apply {
                source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            }
            val upConsumed = try {
                webView.dispatchTouchEvent(upEvent)
            } finally {
                upEvent.recycle()
            }

            log(
                accountUsername,
                "ADS: NATIVE_CLOSE_UP consumed=$upConsumed elapsed=" +
                    (upTime - downTime) + "ms source=TOUCHSCREEN"
            )
        }, 120L)
    }

    private fun dispatchNativeSwipe(
        webView: WebView,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post {
                dispatchNativeSwipe(webView, x1, y1, x2, y2, durationMs)
            }
            return
        }

        if (!webView.isAttachedToWindow) return

        val density = webView.resources.displayMetrics.density.coerceAtLeast(1f)
        val startX = x1 * density
        val startY = y1 * density
        val endX = x2 * density
        val endY = y2 * density

        // The JS side describes the intended finger travel. Keep the actual
        // touch gesture short; the remaining motion is produced by flingScroll.
        val safeDuration = durationMs.coerceIn(180L, 360L)
        val downTime = SystemClock.uptimeMillis()
        val tracker = VelocityTracker.obtain()
        val steps = 6

        fun send(action: Int, eventTime: Long, x: Float, y: Float) {
            MotionEvent.obtain(
                downTime,
                eventTime,
                action,
                x,
                y,
                0
            ).also { event ->
                try {
                    tracker.addMovement(event)
                    webView.dispatchTouchEvent(event)
                } finally {
                    event.recycle()
                }
            }
        }

        try {
            send(MotionEvent.ACTION_DOWN, downTime, startX, startY)

            for (i in 1 until steps) {
                val fraction = i.toFloat() / steps.toFloat()
                // Slight ease-out, matching a finger that accelerates then
                // releases into the native fling.
                val eased = 1f - ((1f - fraction) * (1f - fraction))
                val eventTime = downTime + (safeDuration * fraction).toLong()
                val currentX = startX + ((endX - startX) * eased)
                val currentY = startY + ((endY - startY) * eased)
                send(MotionEvent.ACTION_MOVE, eventTime, currentX, currentY)
            }

            val upTime = downTime + safeDuration
            send(MotionEvent.ACTION_UP, upTime, endX, endY)

            tracker.computeCurrentVelocity(1000)
            val velocityY = tracker.yVelocity
            val minFlingVelocity = ViewConfiguration.get(webView.context).scaledMinimumFlingVelocity
            val maxFlingVelocity = ViewConfiguration.get(webView.context).scaledMaximumFlingVelocity
            val clampedVelocityY = velocityY.coerceIn(
                -maxFlingVelocity.toFloat(),
                maxFlingVelocity.toFloat()
            )

            if (kotlin.math.abs(clampedVelocityY) >= minFlingVelocity) {
                // Android's ScrollView/WebView convention uses the finger's
                // velocity sign: upward finger motion is negative and scrolls
                // the content downward.
                webView.flingScroll(0, clampedVelocityY.toInt())
            } else {
                // Keep very short/slow gestures moving even if they do not
                // reach Android's minimum fling threshold.
                webView.scrollBy(0, (endY - startY).toInt())
            }
        } catch (e: Exception) {
            throw e
        } finally {
            tracker.recycle()
        }
    }

    private var dailyStats = DailyStats(day = currentStatsDay())

    private fun initDailyStats(account: MangaBuffAccount) {
        dailyStats = if (account.dailyStatsDay == currentStatsDay()) {
            DailyStats(
                day = account.dailyStatsDay,
                battles = account.dailyBattles,
                quiz = account.dailyQuiz,
                ads = account.dailyAds,
                mineOre = account.dailyMineOre,
                mineExchangeOre = account.dailyMineExchangeOre,
                mineDiamonds = account.dailyMineDiamonds,
                readerChapters = account.dailyReaderChapters,
                comments = account.dailyComments
            )
        } else {
            DailyStats(day = currentStatsDay())
        }
        onDailyStatsUpdate(account.id, dailyStats)
    }

    private fun publishDailyStats(account: MangaBuffAccount) {
        dailyStats = dailyStats.copy(day = currentStatsDay())
        onDailyStatsUpdate(account.id, dailyStats)
    }

    private fun addDaily(account: MangaBuffAccount, update: (DailyStats) -> DailyStats) {
        dailyStats = update(dailyStats).copy(day = currentStatsDay())
        publishDailyStats(account)
    }

    private val skippedMangaUrls = mutableSetOf<String>()
    private val completedMangaIds = mutableSetOf<String>()
    private val completedChapterIds = mutableSetOf<String>()
    private val readChapterUrlsInRun = mutableSetOf<String>()

    @Volatile
    private var activeChapterContext: ChapterContext? = null

    @Volatile
    private var activeReaderSkip: (() -> Unit)? = null
    @Volatile
    private var activeReaderMarkRead: (() -> Unit)? = null

    /**
     * Hard navigation lock for fullscreen ad sessions.
     * While an ad is running the balance page must not be reloaded/navigated:
     * doing so destroys the Yandex fullscreen session and the reward can be lost.
     */
    @Volatile
    private var adSessionActive = false

    /**
     * Hard wall during the actual fullscreen viewing window.
     * Until this timestamp no page refresh/navigation is allowed, because any
     * reload destroys the Yandex fullscreen ad and can cancel the reward.
     */
    @Volatile
    private var adViewingLockedUntilElapsed = 0L

    private fun isAdViewingLocked(): Boolean =
        adViewingLockedUntilElapsed > SystemClock.elapsedRealtime()

    private fun clearAdViewingLock() {
        adViewingLockedUntilElapsed = 0L
    }

    @Volatile
    private var lastBalanceSummary = "💎 0  🃏 0/10  📖 0/75  💬 0/13"

    private val commentPhrases = listOf(
        "Спасибо за главу! Было интересно читать.",
        "Спасибо за перевод и новую главу!",
        "Хорошая глава, жду продолжения.",
        "Спасибо команде за проделанную работу!",
        "Интересное продолжение, спасибо за релиз.",
        "Глава получилась отличная. Спасибо!",
        "Спасибо за перевод, продолжение жду с интересом.",
        "Хорошая глава, особенно понравился этот момент.",
        "Спасибо за вашу работу над этой мангой!",
        "Наконец-то продолжение! Спасибо за главу.",
        "Интересно, что будет дальше. Спасибо за перевод!",
        "Спасибо за новую главу и вашу работу.",
        "Отличное продолжение истории, жду следующую главу.",
        "Спасибо переводчикам и всем, кто работает над релизом.",
        "Глава прочитана с удовольствием. Спасибо!",
        "Хорошее продолжение, спасибо за новую главу.",
        "Спасибо за оперативный перевод!",
        "Интересная глава, буду ждать продолжения.",
        "Спасибо за релиз и качественный перевод!",
        "Как всегда интересно. Спасибо за новую главу!"
    )

    private val recentCommentIndexes = mutableListOf<Int>()

    private var currentMangaUrl = ""
    private var lastFinishedChapterUrl = ""
    private var lastFinishedChapterNumber = ""
    private var lastFinishedChapterId = ""
    private var lastFinishedMangaTitle = ""
    private var totalMangaChapters = 0
    private var giftsFound = 0
    private var pendingMangaMarkAsRead = false
    private var currentSessionChaptersRead = 0
    private var currentSessionTarget = 0
    private var lastKnownReadQuest = "0/75"

    // MangaBuff increments the reading quest while the NEXT chapter is being
    // read, not necessarily when the current chapter reaches 100%.
    private var pendingReadQuestBefore: String? = null
    private var pendingReadQuestConfirmed = false
    private var pendingReadQuestConfirmedValue = ""
    private var pendingReadChapterId = ""
    private var pendingReadChapterUrl = ""

    private var lastPoolSize = 0
    private var lastItemCount = 0

    /** Chapters actually included in successful /addHistory 2xx batches. */
    private val historyServerAcceptedChapterIds = mutableSetOf<String>()
    private val currentHistoryPostChapterIds = mutableSetOf<String>()

    fun getCurrentMangaUrl(): String = currentMangaUrl
    fun getLastFinishedChapterId(): String = lastFinishedChapterId
    fun getLastFinishedChapterNumber(): String = lastFinishedChapterNumber

    fun skipCurrentManga(): Boolean {
        if (activeReaderSkip == null) {
            log("SYSTEM", "READER: SKIP_MANGA_NOT_AVAILABLE")
            return false
        }
        log("SYSTEM", "READER: SKIP_MANGA_REQUESTED title='$lastFinishedMangaTitle' url='$currentMangaUrl'")
        mainHandler.post {
            try { activeReaderSkip?.invoke() }
            catch (e: Exception) { log("SYSTEM", "READER: SKIP_MANGA_ERROR error=" + e.message, true) }
        }
        return true
    }

    fun markCurrentMangaAsRead(): Boolean {
        if (activeReaderMarkRead == null) {
            log("SYSTEM", "READER: MARK_READ_NOT_AVAILABLE")
            return false
        }
        log("SYSTEM", "READER: MARK_READ_REQUESTED title='$lastFinishedMangaTitle' url='$currentMangaUrl'")
        mainHandler.post {
            try { activeReaderMarkRead?.invoke() }
            catch (e: Exception) { log("SYSTEM", "READER: MARK_READ_ERROR error=" + e.message, true) }
        }
        return true
    }

    private fun updateReaderStatus(account: MangaBuffAccount) {
        val totalStr = if (totalMangaChapters > 0) totalMangaChapters.toString() else "?"
        val countStr = currentSessionChaptersRead.toString()
        updateStatus(
            account,
            "Чтение: $countStr / $totalStr",
            true,
            "Чтение",
            if (currentSessionTarget > 0) (currentSessionChaptersRead.toFloat() / currentSessionTarget).coerceAtMost(1f) else 1f
        )
        if (totalMangaChapters > 0) {
            log(account.username, "READER: PROGRESS count=$currentSessionChaptersRead total=$totalMangaChapters")
        } else {
            log(account.username, "READER: TOTAL_CHAPTERS_UNKNOWN")
        }
    }

    private fun log(
        username: String,
        message: String,
        isError: Boolean = false
    ) {
        val upper = message.trim()
        val component = when {
            upper.startsWith("READER:") || upper.startsWith("READ:") || upper.startsWith("CHAPTER_") || upper.startsWith("SCROLL") || upper.startsWith("PAGE:") -> "READER"
            upper.startsWith("HISTORY:") || upper.startsWith("MB_HISTORY") || upper.startsWith("ADD_HISTORY") -> "HISTORY"
            upper.startsWith("BALANCE:") || upper.startsWith("STAT:") -> "BALANCE"
            upper.startsWith("AUTH:") -> "AUTH"
            upper.startsWith("TASK:") -> "TASK"
            upper.startsWith("BATTLE:") -> "BATTLE"
            upper.startsWith("QUIZ:") -> "QUIZ"
            upper.startsWith("ADS:") -> "ADS"
            upper.startsWith("MINE:") -> "MINE"
            upper.startsWith("COMMENT:") -> "COMMENT"
            upper.startsWith("PROFILE:") -> "PROFILE"
            upper.startsWith("MULTI:") || upper.startsWith("ENGINE:") -> "ENGINE"
            upper.startsWith("JOB:") -> "JOB"
            upper.startsWith("BG:") -> "BG"
            else -> "READER"
        }
        val cleanMessage = if (upper.contains(":") && !upper.startsWith("http")) {
            val parts = upper.split(":", limit = 2)
            if (parts.size > 1) parts[1].trim() else upper
        } else {
            upper
        }
        onLog(
            LogEntry(
                username = username,
                component = component,
                message = cleanMessage,
                isError = isError
            )
        )
    }

    private fun updateStatus(
        account: MangaBuffAccount,
        statusMessage: String,
        isRunning: Boolean,
        currentTask: String = "",
        progress: Float = 0f
    ) {
        onAccountStatusUpdate(
            account.id,
            statusMessage,
            isRunning,
            currentTask,
            progress
        )
    }

    private fun cleanMangaTitle(rawTitle: String): String {
        if (rawTitle.isEmpty()) return "Манга"
        return rawTitle
            .substringBefore(" / ")
            .substringBefore(" - Манхва")
            .substringBefore(" — Манхва")
            .substringBefore(" - Манга")
            .substringBefore(" — Манга")
            .substringBefore(" | MangaBuff")
            .trim()
    }

    private fun getBaseHeaders(account: MangaBuffAccount): Headers {
        val builder = Headers.Builder()
            .add("User-Agent", account.getSafeUserAgent())
            .add("Accept", "*/*")
            .add("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
            .add("Origin", "https://mangabuff.ru")
            .add("Referer", "https://mangabuff.ru")
            .add("X-Requested-With", "XMLHttpRequest")

        val cookies = account.getSafeCookiesJson()
        if (cookies.isNotEmpty()) {
            builder.add("Cookie", cookies)
        }

        val csrf = account.getSafeCsrfToken()
        if (csrf.isNotEmpty()) {
            builder.add("X-CSRF-TOKEN", csrf)
        }

        return builder.build()
    }

    // =========================================================
    // BALANCE / STATISTICS
    // =========================================================

    suspend fun fetchAndLogBalanceInfo(
        account: MangaBuffAccount,
        webView: WebView
    ): String = suspendCancellableCoroutine { continuation ->

        var watchdog: Runnable? = null

        mainHandler.post {

            var resumed = false

            fun removeWatchdog() {
                watchdog?.let {
                    mainHandler.removeCallbacks(it)
                }
                watchdog = null
            }

            fun safeResume(result: String) {
                if (!resumed && continuation.isActive) {
                    resumed = true
                    removeWatchdog()
                    continuation.resume(result)
                }
            }

            if (adSessionActive || isAdViewingLocked()) {
                log(
                    account.username,
                    "STAT: REFRESH_BLOCKED_DURING_AD url=" + webView.url.orEmpty(),
                    true
                )
                safeResume(lastBalanceSummary)
                return@post
            }

            log(account.username, "STAT: REFRESH_START")

            class BalanceBridge {

                @JavascriptInterface
                fun onBalanceInfoParsed(
                    diamonds: String,
                    dropRaw: String,
                    chapters: String,
                    comments: String
                ) {
                    if (resumed) return
                    val before = lastKnownReadQuest
                    lastKnownReadQuest = chapters
                    log(account.username, "[READQUEST_DIAG] SERVER_QUEST_READ stage=FINAL_BALANCE value=$chapters")
                    if (before != chapters) {
                        val beforeNum = before.substringBefore('/').toIntOrNull() ?: 0
                        val afterNum = chapters.substringBefore('/').toIntOrNull() ?: 0
                        val delta = afterNum - beforeNum
                        log(account.username, "[READQUEST_DIAG] CHANGE before=$before after=$chapters delta=${if (delta >= 0) "+$delta" else "$delta"}")
                    } else {
                        log(account.username, "[READQUEST_DIAG] CHANGE before=$before after=$chapters delta=+0")
                    }

                    log(account.username, "STAT: BALANCE_PARSED diamonds=$diamonds cards=$dropRaw chapters=$chapters comments=$comments")

                    val dropMatch = Regex("(\\d+)\\s+из\\s+(\\d+)").find(dropRaw)
                    val cardDropShort = if (dropMatch != null) {
                        "${dropMatch.groupValues[1]}/${dropMatch.groupValues[2]}"
                    } else {
                        val slashMatch = Regex("(\\d+)\\s*/\\s*(\\d+)").find(dropRaw)
                        if (slashMatch != null) {
                            "${slashMatch.groupValues[1]}/${slashMatch.groupValues[2]}"
                        } else {
                            "0/10"
                        }
                    }

                    log(account.username, "STAT: DIAMONDS=$diamonds")
                    log(account.username, "STAT: CARDS=$cardDropShort")
                    log(account.username, "STAT: READ_QUEST=$chapters")
                    log(account.username, "STAT: COMMENT_QUEST=$comments")
                    log(account.username, "STAT: DOM_SCAN_SUCCESS")

                    val summaryStr = "💎 $diamonds  🃏 $cardDropShort  📖 $chapters  💬 $comments"
                    lastBalanceSummary = summaryStr
                    log(account.username, "STAT: $summaryStr")

                    try {
                        onAccountStatsUpdate(
                            account.id,
                            diamonds,
                            cardDropShort,
                            chapters,
                            comments
                        )
                        log(account.username, "STAT: BALANCE_CALLBACK_INVOKED")
                    } catch (e: Exception) {
                        log(account.username, "STAT: STATE_CALLBACK_ERROR error=${e.message}", true)
                    }

                    log(account.username, "STAT: BALANCE_COROUTINE_RESUMED")
                    safeResume(summaryStr)
                }

                @JavascriptInterface
                fun onAuthExpired(msg: String) {
                    if (resumed) return
                    log(account.username, "AUTH: EXPIRED_OR_LOGGED_OUT ($msg)", true)
                    updateStatus(account, "Требуется повторный вход", false, "", 0f)
                    safeResume("💎 0  🃏 0/10  📖 0/75  💬 0/13")
                }

                @JavascriptInterface
                fun onBalanceError(msg: String) {
                    if (resumed) return
                    log(account.username, "STAT: DOM_SCAN_FAILED reason=$msg", true)
                    safeResume("💎 0  🃏 0/10  📖 0/75  💬 0/13")
                }

                @JavascriptInterface
                fun onBalanceDiag(msg: String) {
                    log(account.username, msg)
                }
            }

            try {
                webView.removeJavascriptInterface("AndroidBalanceBridge")
            } catch (_: Exception) {}

            webView.addJavascriptInterface(BalanceBridge(), "AndroidBalanceBridge")

            webView.webViewClient = object : WebViewClient() {

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    val currentUrl = url ?: ""
                    if (currentUrl.contains("/balance")) {
                        log(account.username, "STAT: BALANCE_PAGE_STARTED url=$currentUrl")
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (resumed) return

                    val currentUrl = url ?: ""

                    if (currentUrl.contains("/login")) {
                        log(account.username, "AUTH: EXPIRED_OR_LOGGED_OUT (Redirected to /login)", true)
                        updateStatus(account, "Требуется повторный вход", false, "", 0f)
                        safeResume("💎 0  🃏 0/10  📖 0/75  💬 0/13")
                        return
                    }

                    if (!currentUrl.contains("/balance")) {
                        return
                    }

                    log(account.username, "STAT: BALANCE_PAGE_READY url=$currentUrl")
                    log(account.username, "STAT: BALANCE_JS_INJECTED")
                    log(account.username, "STAT: BALANCE_DOM_POLL_START")

                    val script = """
                        (function() {
                            try {
                                var startedAt = Date.now();
                                var maxWait = 10000;

                                function textOf(element) {
                                    if (!element) return '';
                                    return (element.innerText || element.textContent || '')
                                        .replace(/\s+/g, ' ')
                                        .trim();
                                }

                                function scan() {
                                    try {
                                        if (
                                            (window.isAuth !== 1 && window.isAuth !== true) ||
                                            window.location.pathname.indexOf('/login') !== -1
                                        ) {
                                            AndroidBalanceBridge.onAuthExpired('Сессия истекла или выполнен выход');
                                            return;
                                        }

                                        var wallet = document.querySelector('.wallet-panel');
                                        var elapsed = Date.now() - startedAt;

                                        if (!wallet) {
                                            if (elapsed < maxWait) {
                                                setTimeout(scan, 300);
                                            } else {
                                                AndroidBalanceBridge.onBalanceError('WAIT_WALLET_PANEL_TIMEOUT elapsed=' + elapsed + 'ms');
                                            }
                                            return;
                                        }

                                        var diamond = wallet.querySelector('.wallet-panel__amount');
                                        var diamondVal = textOf(diamond);
                                        if (!diamondVal) diamondVal = '0';

                                        var drop = wallet.querySelector('.wallet-panel__drop-text');
                                        var dropVal = textOf(drop);
                                        if (!dropVal) dropVal = '0 из 10 карт';

                                        var chaptersVal = '';
                                        var commentsVal = '';

                                        var statHeads = Array.from(wallet.querySelectorAll('.wallet-panel__stat-head'));

                                        statHeads.forEach(function(head) {
                                            var fullText = textOf(head);
                                            var span = head.querySelector('span');
                                            var b = head.querySelector('b');

                                            var label = span ? textOf(span) : fullText;
                                            var value = b ? textOf(b) : '';

                                            var normalized = label.toLowerCase();

                                            if (normalized.indexOf('глав') !== -1 || normalized.indexOf('чита') !== -1) {
                                                if (value) chaptersVal = value;
                                            }

                                            if (normalized.indexOf('комментар') !== -1) {
                                                if (value) commentsVal = value;
                                            }
                                        });

                                        if (!chaptersVal || !commentsVal) {
                                            statHeads.forEach(function(head) {
                                                var fullText = textOf(head);
                                                var valueMatch = fullText.match(/(\d+)\s*\/\s*(\d+)/);
                                                if (!valueMatch) return;

                                                var lower = fullText.toLowerCase();

                                                if (!chaptersVal && (lower.indexOf('глав') !== -1 || lower.indexOf('чита') !== -1)) {
                                                    chaptersVal = valueMatch[1] + '/' + valueMatch[2];
                                                }

                                                if (!commentsVal && lower.indexOf('комментар') !== -1) {
                                                    commentsVal = valueMatch[1] + '/' + valueMatch[2];
                                                }
                                            });
                                        }

                                        if (!chaptersVal || !commentsVal) {
                                            if (elapsed < maxWait) {
                                                setTimeout(scan, 300);
                                                return;
                                            }
                                        }

                                        if (!chaptersVal) chaptersVal = '0/75';
                                        if (!commentsVal) commentsVal = '0/13';

                                        AndroidBalanceBridge.onBalanceInfoParsed(
                                            diamondVal,
                                            dropVal,
                                            chaptersVal,
                                            commentsVal
                                        );

                                    } catch (e) {
                                        AndroidBalanceBridge.onBalanceError(
                                            e && e.message ? e.message : String(e)
                                        );
                                    }
                                }

                                scan();

                            } catch (e) {
                                AndroidBalanceBridge.onBalanceError(
                                    e && e.message ? e.message : String(e)
                                );
                            }
                        })();
                    """.trimIndent()

                    view?.evaluateJavascript(script, null)
                    view?.evaluateJavascript("""
                        (function() {
                            try {
                                function runDiag(stage) {
                                    try {
                                        var statHeads = Array.from(document.querySelectorAll('.wallet-panel__stat-head'));
                                        var foundHead = null;
                                        var foundSelector = '';
                                        var foundText = '';
                                        
                                        statHeads.forEach(function(head, idx) {
                                            var text = (head.innerText || head.textContent || '').replace(/\s+/g, ' ').trim();
                                            var lower = text.toLowerCase();
                                            if (lower.indexOf('глав') !== -1 || lower.indexOf('чита') !== -1 || text.indexOf('/75') !== -1) {
                                                foundHead = head;
                                                foundSelector = '.wallet-panel__stat-head:nth-child(' + (idx + 1) + ')';
                                                foundText = text;
                                            }
                                        });

                                        if (foundHead) {
                                            var outer = foundHead.outerHTML || '';
                                            var parentOuter = (foundHead.parentElement ? foundHead.parentElement.outerHTML : '').slice(0, 1500);
                                            AndroidBalanceBridge.onBalanceDiag('BALANCE_DIAG: READ_QUEST_SELECTOR stage=' + stage + ' selector=' + foundSelector);
                                            AndroidBalanceBridge.onBalanceDiag('BALANCE_DIAG: READ_QUEST_TEXT stage=' + stage + ' text=' + foundText);
                                            AndroidBalanceBridge.onBalanceDiag('BALANCE_DIAG: READ_QUEST_OUTER_HTML stage=' + stage + ' html=' + outer.slice(0, 1000));
                                            AndroidBalanceBridge.onBalanceDiag('BALANCE_DIAG: READ_QUEST_PARENT_HTML stage=' + stage + ' parent=' + parentOuter);
                                        } else {
                                            AndroidBalanceBridge.onBalanceDiag('BALANCE_DIAG: READ_QUEST_SELECTOR stage=' + stage + ' selector=NOT_FOUND');
                                        }

                                        var allElems = document.querySelectorAll('*');
                                        var matches = [];
                                        for (var i = 0; i < allElems.length; i++) {
                                            var el = allElems[i];
                                            if (el.children.length === 0) {
                                                var t = (el.innerText || el.textContent || '').trim();
                                                if (t.indexOf('/75') !== -1 || t.toLowerCase().indexOf('глав') !== -1 || t.toLowerCase().indexOf('чтен') !== -1) {
                                                    matches.push(el.tagName + '.' + (el.className || '') + ': "' + t + '"');
                                                    if (matches.length >= 20) break;
                                                }
                                            }
                                        }
                                        AndroidBalanceBridge.onBalanceDiag('BALANCE_DIAG: READ_QUEST_MATCHES stage=' + stage + ' matches=' + JSON.stringify(matches));

                                        var globals = [];
                                        var keys = Object.keys(window);
                                        for (var k = 0; k < keys.length; k++) {
                                            var key = keys[k];
                                            var lowerKey = key.toLowerCase();
                                            if (lowerKey.indexOf('quest') !== -1 || lowerKey.indexOf('stat') !== -1 || lowerKey.indexOf('user') !== -1 || lowerKey.indexOf('balance') !== -1 || lowerKey.indexOf('chapter') !== -1) {
                                                globals.push(key);
                                            }
                                        }
                                        AndroidBalanceBridge.onBalanceDiag('BALANCE_DIAG: READ_QUEST_GLOBALS stage=' + stage + ' globals=' + JSON.stringify(globals.slice(0, 20)));

                                        if (stage === 'INITIAL' || stage === 'AFTER_3S') {
                                            var resources = performance.getEntriesByType('resource');
                                            resources.forEach(function(res) {
                                                var name = res.name || '';
                                                if (name.indexOf('/api') !== -1 || name.indexOf('/balance') !== -1 || name.indexOf('/quest') !== -1 || name.indexOf('/user') !== -1 || name.indexOf('/stats') !== -1 || name.indexOf('ajax') !== -1) {
                                                    AndroidBalanceBridge.onBalanceDiag('BALANCE_DIAG: BALANCE_RESOURCE name=' + name + ' type=' + res.initiatorType);
                                                }
                                            });
                                        }

                                        if (stage === 'INITIAL') {
                                            setTimeout(function() { runDiag('AFTER_1S'); }, 1000);
                                            setTimeout(function() { runDiag('AFTER_3S'); }, 3000);
                                        }

                                    } catch (err) {
                                        AndroidBalanceBridge.onBalanceError('DIAG_ERROR: ' + (err && err.message ? err.message : String(err)));
                                    }
                                }
                                runDiag('INITIAL');
                            } catch(e) {}
                        })();
                    """.trimIndent(), null)
                }
            }

            watchdog = Runnable {
                if (!resumed && continuation.isActive) {
                    log(account.username, "STAT: BALANCE_TIMEOUT after=15000ms", true)
                    safeResume("💎 0  🃏 0/10  📖 0/75  💬 0/13")
                }
            }

            mainHandler.postDelayed(watchdog!!, BALANCE_WATCHDOG_MS)

            try {
                webView.settings.javaScriptEnabled = true
            } catch (e: Exception) {
                log(account.username, "STAT: WEBVIEW_JS_ENABLE_ERROR error=${e.message}", true)
            }

            webView.loadUrl("https://mangabuff.ru/balance")
        }

        continuation.invokeOnCancellation {
            mainHandler.post {
                watchdog?.let { mainHandler.removeCallbacks(it) }
                watchdog = null
            }
        }
    }


    // =========================================================
    // CSRF
    // =========================================================

    suspend fun refreshCsrfToken(
        account: MangaBuffAccount
    ): String = withContext(Dispatchers.IO) {
        try {
            log(account.username, "AUTH: REFRESH_CSRF_START")
            val request = Request.Builder()
                .url("https://mangabuff.ru")
                .headers(getBaseHeaders(account))
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            val html = response.body?.string() ?: ""
            val doc = Jsoup.parse(html, "https://mangabuff.ru")
            val csrfMeta = doc.select("meta[name=csrf-token]").first()
            val token = csrfMeta?.attr("content") ?: ""

            if (token.isNotEmpty()) {
                log(account.username, "AUTH: REFRESH_CSRF_PASS token_len=${token.length}")
            } else {
                log(account.username, "AUTH: REFRESH_CSRF_EMPTY", true)
            }
            token
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log(account.username, "AUTH: REFRESH_CSRF_FAIL error=${e.message}", true)
            ""
        }
    }

    // =========================================================
    // ACCOUNT TASKS
    // =========================================================

    suspend fun runAccountTasks(
        account: MangaBuffAccount,
        settings: GlobalSettings,
        taskType: TaskType = TaskType.ALL,
        webView: WebView
    ) {
        coroutineContext.ensureActive()

        initDailyStats(account)
        log(account.username, "TASK: ACCOUNT_START type=${taskType.title}")
        updateStatus(account, "Обновление статистики...", true, taskType.title, 0.05f)

        refreshCsrfToken(account)

        log(account.username, "STAT: REFRESH_BEFORE_START (Обновление баланса и статистики)")
        val balanceResult = fetchAndLogBalanceInfo(account, webView)

        if (balanceResult.contains("Требуется повторный вход") ||
            account.getSafeStatusMessage().contains("Требуется повторный вход")
        ) {
            log(account.username, "AUTH: ABORTING_TASKS due to expired session")
            return
        }

        if (taskType == TaskType.ALL || taskType == TaskType.BATTLE) {
            coroutineContext.ensureActive()
            if (account.battleEnabled || taskType == TaskType.BATTLE) {
                log(account.username, "TASK: BATTLE_START")
                val start = SystemClock.elapsedRealtime()
                val result = runBattleTask(account, settings, webView)
                val elapsed = SystemClock.elapsedRealtime() - start

                when (result) {
                    is TaskResult.Success -> {
                        addDaily(account) { it.copy(battles = it.battles + settings.battleTargetCount.coerceAtLeast(0)) }
                        log(account.username, "BATTLE: TASK_SUCCESS elapsed=${elapsed}ms")
                    }
                    is TaskResult.Failed -> log(account.username, "BATTLE: TASK_FAILED reason=${result.reason}", true)
                    is TaskResult.Cancelled -> log(account.username, "BATTLE: TASK_CANCELLED")
                    else -> log(account.username, "BATTLE: TASK_FINISHED elapsed=${elapsed}ms")
                }
                fetchAndLogBalanceInfo(account, webView)
            } else {
                log(account.username, "BATTLE: SKIPPED_DISABLED")
            }
        }

        if (taskType == TaskType.ALL || taskType == TaskType.QUIZ) {
            coroutineContext.ensureActive()
            if (account.quizEnabled || taskType == TaskType.QUIZ) {
                log(account.username, "TASK: QUIZ_START")
                val start = SystemClock.elapsedRealtime()
                runQuizTask(account, settings, webView)
                log(account.username, "TASK: QUIZ_END elapsed=${SystemClock.elapsedRealtime() - start}ms")
            }
        }

        if (taskType == TaskType.ALL || taskType == TaskType.ADS) {
            coroutineContext.ensureActive()
            if (account.advEnabled || taskType == TaskType.ADS) {
                log(account.username, "TASK: ADS_START")
                val start = SystemClock.elapsedRealtime()
                val adsSuccess = runAdsTask(account, settings, webView)
                log(account.username, "TASK: ADS_END elapsed=" + (SystemClock.elapsedRealtime() - start) + "ms success=" + adsSuccess)
                fetchAndLogBalanceInfo(account, webView)
                if (!adsSuccess) {
                    updateStatus(account, "❌ Реклама не подтверждена", false, "Реклама", 0f)
                    log(account.username, "TASK: ACCOUNT_ABORTED reason=ADS_FAILED", true)
                    return
                }
            }
        }

        if (taskType == TaskType.ALL || taskType == TaskType.MINE) {
            coroutineContext.ensureActive()
            if (account.mineEnabled || taskType == TaskType.MINE) {
                log(account.username, "TASK: MINE_START")
                val start = SystemClock.elapsedRealtime()
                runMineTask(account, settings, webView)
                log(account.username, "TASK: MINE_END elapsed=${SystemClock.elapsedRealtime() - start}ms")
            }
        }

        if (taskType == TaskType.ALL || taskType == TaskType.READER) {
            coroutineContext.ensureActive()
            if (account.readerEnabled || taskType == TaskType.READER) {
                log(account.username, "TASK: READER_START")
                val start = SystemClock.elapsedRealtime()
                runReaderTask(account, settings, webView)
                log(account.username, "TASK: READER_END elapsed=${SystemClock.elapsedRealtime() - start}ms")
                fetchAndLogBalanceInfo(account, webView)
            }
        }

        if (taskType == TaskType.ALL || taskType == TaskType.COMMENT) {
            coroutineContext.ensureActive()
            if (account.commentEnabled || taskType == TaskType.COMMENT) {
                log(account.username, "TASK: COMMENT_START")
                val start = SystemClock.elapsedRealtime()
                runCommentTask(account, settings, webView)
                log(account.username, "TASK: COMMENT_END elapsed=${SystemClock.elapsedRealtime() - start}ms")
                fetchAndLogBalanceInfo(account, webView)
            }
        }

        val finalSummary = fetchAndLogBalanceInfo(account, webView)
        updateStatus(account, finalSummary, false, "", 1.0f)
        log(account.username, "TASK: ACCOUNT_FINISHED type=${taskType.title}")
    }

    // =========================================================
    // BATTLE
    // =========================================================

    private suspend fun runBattleTask(
        account: MangaBuffAccount,
        settings: GlobalSettings,
        webView: WebView
    ): TaskResult {
        val battleTarget = settings.battleTargetCount
        log(account.username, "BATTLE: START target=$battleTarget current=0")
        var battleCount = 0

        updateStatus(account, "⚔️ Бои $battleCount/$battleTarget", true, "Бои", 0f)

        while (battleCount < battleTarget) {
            coroutineContext.ensureActive()
            val progress = if (battleTarget > 0) battleCount.toFloat() / battleTarget else 1f
            updateStatus(account, "⚔️ Бои $battleCount/$battleTarget", true, "Бои", progress)

            when (val roundResult = runSingleBattleRound(account, webView, battleCount + 1, battleTarget)) {
                is TaskResult.Success -> {
                    battleCount++
                    log(account.username, "BATTLE: COMPLETED count=$battleCount/$battleTarget")
                    updateStatus(account, "⚔️ Бои $battleCount/$battleTarget", true, "Бои", if (battleTarget > 0) battleCount.toFloat() / battleTarget else 1f)

                    if (battleCount < battleTarget) {
                        log(account.username, "BATTLE: COOLDOWN_START duration=2000")
                        delay(BATTLE_COOLDOWN_MS)
                        log(account.username, "BATTLE: COOLDOWN_DONE")
                    }
                }
                is TaskResult.Cancelled -> return TaskResult.Cancelled
                is TaskResult.Failed -> return roundResult
                else -> return TaskResult.Failed("unknown_error")
            }
        }

        log(account.username, "BATTLE: TARGET_REACHED $battleCount/$battleTarget")
        log(account.username, "BATTLE: REWARDS_START")
        claimBattleRewards(account, webView)
        log(account.username, "BATTLE: REWARDS_COMPLETED")

        return TaskResult.Success
    }

    private suspend fun runSingleBattleRound(
        account: MangaBuffAccount,
        webView: WebView,
        roundNum: Int,
        totalTarget: Int
    ): TaskResult = suspendCancellableCoroutine { continuation ->
        val battleRunId = UUID.randomUUID().toString()
        var resumed = false

        fun safeResume(result: TaskResult) {
            if (!resumed && continuation.isActive) {
                resumed = true
                continuation.resume(result)
            }
        }

        continuation.invokeOnCancellation {
            mainHandler.post {
                try { webView.stopLoading() } catch (_: Exception) {}
            }
        }

        mainHandler.post {
            class BattleBridge {
                @JavascriptInterface
                fun onStateLog(state: String, details: String) {
                    log(account.username, "BATTLE: $state $details")
                }

                @JavascriptInterface
                fun onHomeReady() {
                    log(account.username, "BATTLE: HOME_READY")
                    mainHandler.postDelayed({
                        if (!continuation.isActive) return@postDelayed

                        val script = """
                            (function() {
                                try {
                                    var startBtn = document.querySelector('button[data-battle-start]');
                                    if (!startBtn) {
                                        AndroidBattleBridge.onRoundFailed('battle_start_button_not_found');
                                        return;
                                    }
                                    AndroidBattleBridge.onStateLog('START_FOUND', 'Кнопка Найти бой найдена');
                                    startBtn.click();
                                    AndroidBattleBridge.onStateLog('START_CLICKED', 'Клик по Найти бой');
                                } catch(e) {
                                    AndroidBattleBridge.onRoundFailed('exception=' + e.message);
                                }
                            })();
                        """.trimIndent()
                        webView.evaluateJavascript(script, null)
                        log(account.username, "BATTLE: FIGHT_STARTED runId=$battleRunId")
                    }, BATTLE_COOLDOWN_MS)
                }

                @JavascriptInterface
                fun onSkipClicked() {
                    // The JS bridge callback runs off the WebView UI thread.
                    // Return the completed round to runBattleTask on the main thread.
                    // The next round will open /battle itself.
                    log(account.username, "BATTLE: RETURN_TO_BATTLE_REQUEST")

                    mainHandler.post {
                        if (!continuation.isActive) return@post

                        try {
                            log(account.username, "BATTLE: RETURN_TO_BATTLE")
                            webView.loadUrl("https://mangabuff.ru/battle")

                            // IMPORTANT:
                            // This callback means the current battle has reached
                            // "К итогам". The round is finished here. Without
                            // resuming the continuation, runBattleTask stays at
                            // 0/20 forever and never increments battleCount.
                            safeResume(TaskResult.Success)
                        } catch (e: Exception) {
                            log(
                                account.username,
                                "BATTLE: RETURN_TO_BATTLE_ERROR error=" + e.javaClass.simpleName + ": " + e.message,
                                true
                            )
                            safeResume(
                                TaskResult.Failed(
                                    "return_to_battle_exception=" + e.javaClass.simpleName + ":" + e.message
                                )
                            )
                        }
                    }
                }

                @JavascriptInterface
                fun onRoundFailed(reason: String) {
                    safeResume(TaskResult.Failed(reason))
                }
            }

            try { webView.removeJavascriptInterface("AndroidBattleBridge") } catch (_: Exception) {}
            webView.addJavascriptInterface(BattleBridge(), "AndroidBattleBridge")

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url?.contains("/battle") != true) return

                    val script = """
                        (function() {
                            try {
                                var isFight = document.body.classList.contains('card-battle-page') ||
                                    document.querySelector('[data-battle-log-json]') !== null;

                                if (isFight) {
                                    var skip = Array.from(document.querySelectorAll('button.battle-control__button--skip'))
                                        .find(function(btn) {
                                            return (btn.textContent || '').trim() === 'К итогам' && !btn.disabled;
                                        });

                                    if (skip) {
                                        AndroidBattleBridge.onStateLog('RESULTS_BUTTON_FOUND', 'Кнопка К итогам найдена');
                                        skip.click();
                                        AndroidBattleBridge.onSkipClicked();
                                        return;
                                    }

                                    var elapsed = 0;
                                    var timer = setInterval(function() {
                                        elapsed += 500;
                                        var btn = Array.from(document.querySelectorAll('button.battle-control__button--skip'))
                                            .find(function(x) {
                                                return (x.textContent || '').trim() === 'К итогам' && !x.disabled;
                                            });

                                        if (btn) {
                                            clearInterval(timer);
                                            btn.click();
                                            AndroidBattleBridge.onSkipClicked();
                                        } else if (elapsed >= 30000) {
                                            clearInterval(timer);
                                            AndroidBattleBridge.onRoundFailed('skip_button_timeout');
                                        }
                                    }, 500);
                                    return;
                                }

                                var start = document.querySelector('button[data-battle-start]');
                                if (start) {
                                    AndroidBattleBridge.onHomeReady();
                                    return;
                                }

                                var elapsedHome = 0;
                                var homeTimer = setInterval(function() {
                                    elapsedHome += 500;
                                    var btn = document.querySelector('button[data-battle-start]');
                                    if (btn) {
                                        clearInterval(homeTimer);
                                        AndroidBattleBridge.onHomeReady();
                                    } else if (elapsedHome >= 15000) {
                                        clearInterval(homeTimer);
                                        AndroidBattleBridge.onRoundFailed('home_start_button_timeout');
                                    }
                                }, 500);

                            } catch(e) {
                                AndroidBattleBridge.onRoundFailed('exception=' + e.message);
                            }
                        })();
                    """.trimIndent()

                    view?.evaluateJavascript(script, null)
                }
            }

            webView.loadUrl("https://mangabuff.ru/battle")
        }
    }

    // =========================================================
    // QUIZ
    // =========================================================

    private suspend fun runQuizTask(
        account: MangaBuffAccount,
        settings: GlobalSettings,
        webView: WebView
    ) {
        var clicksDone = 0
        log(account.username, "QUIZ: START max_questions=${settings.quizMaxClicks}")

        while (clicksDone < settings.quizMaxClicks) {
            coroutineContext.ensureActive()

            updateStatus(
                account,
                "Квиз ($clicksDone/${settings.quizMaxClicks})",
                true,
                "Квиз",
                if (settings.quizMaxClicks > 0) clicksDone.toFloat() / settings.quizMaxClicks else 1f
            )

            val success = runQuizSingleQuestion(account, webView)
            if (!success) break

            clicksDone++
            addDaily(account) { it.copy(quiz = it.quiz + 1) }
            log(account.username, "QUIZ: ANSWERED question=$clicksDone/${settings.quizMaxClicks}")
            delay(QUIZ_NEXT_QUESTION_DELAY_MS)
        }
    }

    private suspend fun runQuizSingleQuestion(
        account: MangaBuffAccount,
        webView: WebView
    ): Boolean = suspendCancellableCoroutine { continuation ->
        var resumed = false

        fun safeResume(result: Boolean) {
            if (!resumed && continuation.isActive) {
                resumed = true
                continuation.resume(result)
            }
        }

        mainHandler.post {
            class QuizBridge {
                @JavascriptInterface
                fun onQuestionAnswered(success: Boolean) {
                    safeResume(success)
                }
            }

            try { webView.removeJavascriptInterface("AndroidQuiz") } catch (_: Exception) {}
            webView.addJavascriptInterface(QuizBridge(), "AndroidQuiz")

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    val script = """
                        (function() {
                            try {
                                var answers = document.querySelectorAll('.quiz__answer-item.button');
                                if (answers && answers.length > 0) {
                                    var index = Math.floor(Math.random() * answers.length);
                                    answers[index].click();
                                    setTimeout(function() {
                                        AndroidQuiz.onQuestionAnswered(true);
                                    }, $QUIZ_QUESTION_RESULT_DELAY_MS);
                                } else {
                                    AndroidQuiz.onQuestionAnswered(false);
                                }
                            } catch(e) {
                                AndroidQuiz.onQuestionAnswered(false);
                            }
                        })();
                    """.trimIndent()
                    view?.evaluateJavascript(script, null)
                }
            }

            webView.loadUrl("https://mangabuff.ru/quiz")
        }
    }

    // =========================================================
    // ADS
    // =========================================================

    private suspend fun runAdsTask(
        account: MangaBuffAccount,
        settings: GlobalSettings,
        webView: WebView
    ): Boolean {
        /*
         * MangaBuff currently pays 3 ad rewards per day. Keep the user setting,
         * but never drive the site past its real daily limit.
         */
        val target = settings.adsCount.coerceAtLeast(0).coerceAtMost(3)
        var adsDone = 0

        log(account.username, "TASK: ADS_START requested=${settings.adsCount} target=$target dailyLimit=3")
        updateStatus(account, "📺 Реклама (0/$target): подготовка", true, "Реклама", 0f)

        /*
         * The ad session owns the WebView navigation until every requested ad
         * has either completed or the ad runner has definitively failed.
         *
         * In particular, fetchAndLogBalanceInfo() is not allowed to call
         * loadUrl()/reload() while this flag is true. A balance refresh during
         * the 30-second fullscreen viewing window destroys the ad.
         */
        adSessionActive = true
        log(account.username, "ADS: NAVIGATION_LOCK_ACQUIRED")

        try {
            while (adsDone < target) {
                coroutineContext.ensureActive()

                val success = watchSingleAd(account, webView) { step ->
                    updateStatus(
                        account,
                        "📺 Реклама ($adsDone/$target): $step",
                        true,
                        "Реклама",
                        if (target > 0) adsDone.toFloat() / target else 1f
                    )
                }

                if (!success) break

                adsDone++
                addDaily(account) { it.copy(ads = it.ads + 1) }
                log(account.username, "TASK: ADS_COMPLETED_COUNT ad=$adsDone/$target")
                if (adsDone < target) {
                    delay(ADS_NEXT_DELAY_MS)
                }
            }
        } finally {
            clearAdViewingLock()
            adSessionActive = false
            log(
                account.username,
                "ADS: NAVIGATION_LOCK_RELEASED successCount=$adsDone target=$target"
            )
        }

        log(account.username, "TASK: ADS_END successCount=$adsDone target=$target")
        return adsDone >= target
    }

    private suspend fun watchSingleAd(
        account: MangaBuffAccount,
        webView: WebView,
        onStep: (String) -> Unit
    ): Boolean = suspendCancellableCoroutine { continuation ->
        var resumed = false

        fun safeResume(result: Boolean) {
            if (!resumed && continuation.isActive) {
                resumed = true
                continuation.resume(result)
            }
        }

        mainHandler.post {
            class AdsBridge {
                @JavascriptInterface
                fun onStateLog(state: String, details: String) {
                    log(account.username, "ADS: $state $details")

                    if (state == "WATCH_CLICKED") {
                        // The fullscreen viewing window starts only after the ad
                        // button was actually clicked. From this moment until the
                        // 32s deadline the WebView must remain untouched.
                        adViewingLockedUntilElapsed =
                            SystemClock.elapsedRealtime() + 32_000L
                        log(account.username, "ADS: VIEWING_LOCK_ACQUIRED until=32s")
                    }

                    onStep(details)
                }

                @JavascriptInterface
                fun onAdSuccess() {
                    clearAdViewingLock()
                    log(account.username, "ADS: REWARD_CONFIRMED")
                    safeResume(true)
                }

                @JavascriptInterface
                fun onAdFailed(reason: String) {
                    clearAdViewingLock()
                    log(account.username, "ADS: FAILED reason=$reason", true)
                    safeResume(false)
                }

                @JavascriptInterface
                fun onDailyLimit() {
                    clearAdViewingLock()
                    log(account.username, "ADS: DAILY_LIMIT_REACHED")
                    safeResume(false)
                }

                @JavascriptInterface
                fun onCloseTapRequested(x: Float, y: Float, source: String) {
                    log(
                        account.username,
                        "ADS: NATIVE_CLOSE_TAP_REQUEST x=$x y=$y source=$source"
                    )
                    mainHandler.post {
                        try {
                            dispatchNativeTap(account.username, webView, x, y)
                        } catch (e: Exception) {
                            log(
                                account.username,
                                "ADS: NATIVE_CLOSE_TAP_ERROR error=" + e.message,
                                true
                            )
                        }
                    }
                }
            }

            // Keep the bridge registered across the balance navigation.
            webView.addJavascriptInterface(AdsBridge(), "AndroidAds")

            val script = """
                        (function() {
                            try {
                                var existingRunner = !!window.__mbAdsRunnerActive;
                                var runnerStartedAt = Number(window.__mbAdsRunnerStartedAt || 0);
                                var runnerAge = runnerStartedAt > 0 ? (Date.now() - runnerStartedAt) : Number.MAX_SAFE_INTEGER;

                                if (existingRunner && runnerAge < 15000) {
                                    try {
                                        AndroidAds.onStateLog(
                                            "RUNNER_ALREADY_ACTIVE",
                                            "ageMs=" + runnerAge
                                        );
                                    } catch (e) {}
                                    return;
                                }

                                // A previous WebView injection can leave the guard set after
                                // the native coroutine has already timed out. Treat such a
                                // stale runner as dead and allow a fresh ad session.
                                window.__mbAdsRunnerActive = true;
                                window.__mbAdsRunnerStartedAt = Date.now();

                                // This flag belongs to the current ad session only.
                                // A stale value from a previous injected runner must never
                                // suppress the native close request for the next ad.
                                window.__mbAdsNativeCloseRequested = false;

                                var finished = false;
                                var buttonPollStarted = Date.now();
                                var watchTimer = null;
                                var rewardPoll = null;

                                function textOf(el) {
                                    return (el && (el.innerText || el.textContent) || "")
                                        .replace(/\s+/g, " ")
                                        .trim();
                                }

                                function findWatchButton() {
                                    var direct = document.querySelector(
                                        ".wallet-panel__action.user-quest__watch-ads-btn"
                                    );
                                    if (direct) return direct;

                                    var candidates = Array.from(
                                        document.querySelectorAll(
                                            "button, a, [role=\"button\"], .wallet-panel__action"
                                        )
                                    );

                                    return candidates.find(function(el) {
                                        var t = textOf(el).toLowerCase();
                                        var cls = String(el.className || "").toLowerCase();
                                        return (
                                            t.indexOf("реклам") !== -1 ||
                                            t.indexOf("advert") !== -1 ||
                                            cls.indexOf("watch-ads") !== -1 ||
                                            cls.indexOf("watch_ads") !== -1 ||
                                            cls.indexOf("advert") !== -1
                                        );
                                    }) || null;
                                }

                                function parseNumber(value) {
                                    if (value === null || value === undefined) return null;
                                    var m = String(value).replace(/[^0-9]/g, "");
                                    if (!m) return null;
                                    var n = parseInt(m, 10);
                                    return isNaN(n) ? null : n;
                                }

                                function readDiamondBalance(root) {
                                    var scope = root || document;
                                    var heads = Array.from(
                                        scope.querySelectorAll(".wallet-panel__stat-head")
                                    );

                                    for (var i = 0; i < heads.length; i++) {
                                        var label = textOf(heads[i].querySelector("span")).toLowerCase();
                                        var value = textOf(heads[i].querySelector("b"));
                                        if (label.indexOf("алмаз") !== -1) return parseNumber(value);
                                    }

                                    var body = textOf(scope.body || scope.documentElement);
                                    var match = body.match(/алмаз[^0-9]{0,80}([0-9][0-9\s]*)/i);
                                    return match ? parseNumber(match[1]) : null;
                                }

                                function readButtonCount(btn) {
                                    if (!btn) return null;
                                    var raw = btn.getAttribute("data-count");
                                    if (raw === null || raw === undefined || raw === "") return null;
                                    var n = parseInt(String(raw), 10);
                                    return isNaN(n) ? null : n;
                                }

                                function isDailyLimitText(text) {
                                    var t = String(text || "").toLowerCase();
                                    return t.indexOf("лимит") !== -1 ||
                                           (t.indexOf("дост") !== -1 && t.indexOf("заверш") !== -1);
                                }

                                /*
                                 * MangaBuff invokes Yandex Rewarded through
                                 * Ya.Context.AdvManager.render(). The Rewarded contract
                                 * exposes onRewarded(true) after the reward condition is met.
                                 * Hook it as an additional completion signal and diagnostic.
                                 * The MangaBuff server balance remains authoritative.
                                 */
                                function installYandexRewardHook() {
                                    try {
                                        if (window.__mbYandexRewardHookInstalled) return true;

                                        var adv = window.Ya &&
                                            window.Ya.Context &&
                                            window.Ya.Context.AdvManager;

                                        if (!adv || typeof adv.render !== "function") {
                                            AndroidAds.onStateLog("YANDEX_REWARD_HOOK", "unavailable");
                                            return false;
                                        }

                                        var originalRender = adv.render;
                                        adv.render = function(options) {
                                            try {
                                                if (options && typeof options.onRewarded === "function") {
                                                    var originalOnRewarded = options.onRewarded;
                                                    var wrappedOptions = Object.assign({}, options);

                                                    wrappedOptions.onRewarded = function(isRewarded) {
                                                        AndroidAds.onStateLog(
                                                            "YANDEX_REWARDED_CALLBACK",
                                                            "isRewarded=" + !!isRewarded
                                                        );

                                                        try {
                                                            return originalOnRewarded.apply(this, arguments);
                                                        } finally {
                                                            if (isRewarded) {
                                                                setTimeout(function() {
                                                                    try {
                                                                        verifyReward(0);
                                                                    } catch (e) {
                                                                        AndroidAds.onStateLog(
                                                                            "YANDEX_REWARD_VERIFY_ERROR",
                                                                            "error=" + (e && e.message ? e.message : String(e))
                                                                        );
                                                                    }
                                                                }, 250);
                                                            }
                                                        }
                                                    };

                                                    return originalRender.call(this, wrappedOptions);
                                                }
                                            } catch (e) {
                                                AndroidAds.onStateLog(
                                                    "YANDEX_REWARD_HOOK_ERROR",
                                                    "error=" + (e && e.message ? e.message : String(e))
                                                );
                                            }

                                            return originalRender.apply(this, arguments);
                                        };

                                        window.__mbYandexRewardHookInstalled = true;
                                        AndroidAds.onStateLog("YANDEX_REWARD_HOOK", "installed");
                                        return true;
                                    } catch (e) {
                                        AndroidAds.onStateLog(
                                            "YANDEX_REWARD_HOOK_ERROR",
                                            "error=" + (e && e.message ? e.message : String(e))
                                        );
                                        return false;
                                    }
                                }

                                var initialButton = findWatchButton();
                                var initialDiamond = readDiamondBalance(document);
                                var initialButtonCount = readButtonCount(initialButton);

                                AndroidAds.onStateLog(
                                    "BALANCE_READY",
                                    "button=" + !!initialButton +
                                    " diamonds=" + (initialDiamond === null ? "?" : initialDiamond) +
                                    " count=" + (initialButtonCount === null ? "?" : initialButtonCount)
                                );

                                // Yandex fullscreen ads can render the controls inside an open
                                // ShadowRoot (and sometimes inside a same-origin iframe). A plain
                                // document.querySelector() misses those nodes, which produced the
                                // endless AD_TIMER visible=false loop even though the timer was on screen.
                                function deepQuery(selector, root, depth) {
                                    root = root || document;
                                    depth = depth || 0;
                                    if (depth > 8) return null;

                                    try {
                                        var direct = root.querySelector(selector);
                                        if (direct) return direct;
                                    } catch (e) {}

                                    var nodes = [];
                                    try {
                                        nodes = Array.from(root.querySelectorAll("*"));
                                    } catch (e) {
                                        return null;
                                    }

                                    for (var i = 0; i < nodes.length; i++) {
                                        var node = nodes[i];

                                        // Open shadow DOM is accessible to the page script.
                                        if (node.shadowRoot) {
                                            var shadowFound = deepQuery(selector, node.shadowRoot, depth + 1);
                                            if (shadowFound) return shadowFound;
                                        }

                                        // Same-origin iframe/document fallback. Cross-origin
                                        // Yandex frames remain inaccessible by browser security.
                                        if (node.tagName === "IFRAME") {
                                            try {
                                                if (node.contentDocument) {
                                                    var frameFound = deepQuery(
                                                        selector,
                                                        node.contentDocument,
                                                        depth + 1
                                                    );
                                                    if (frameFound) return frameFound;
                                                }
                                            } catch (e) {}
                                        }
                                    }

                                    return null;
                                }

                                function isVisibleElement(el) {
                                    if (!el || !el.getBoundingClientRect) return false;

                                    try {
                                        var rect = el.getBoundingClientRect();
                                        var style = getComputedStyle(el);
                                        return (
                                            rect.width > 0 &&
                                            rect.height > 0 &&
                                            style.display !== "none" &&
                                            style.visibility !== "hidden" &&
                                            style.opacity !== "0"
                                        );
                                    } catch (e) {
                                        return false;
                                    }
                                }

                                function findTimer() {
                                    // Current Yandex fullscreen markup:
                                    // <div data-fullscreen-element="timer">...</div>
                                    return deepQuery(
                                        "[data-fullscreen-element=\"timer\"]," +
                                        "[data-fullscreen-element-name=\"timer\"]," +
                                        "[class*=\"timer\"]"
                                    );
                                }

                                function findCloseButton() {
                                    /*
                                     * Exact live Yandex X:
                                     * path d starts with M32.012 10.345a1.667a1.667
                                     * and sits below [data-survey-fullscreen-control].
                                     */
                                    var closePath = deepQuery(
                                        "path[d^=\"M32.012 10.345a1.667a1.667\"]"
                                    );

                                    if (closePath && isVisibleElement(closePath)) {
                                        try {
                                            var control = closePath.closest(
                                                "[data-survey-fullscreen-control]"
                                            );
                                            if (control && isVisibleElement(control)) return control;
                                        } catch (e) {}

                                        try {
                                            var fullscreenClose = closePath.closest(
                                                "[data-fullscreen-element=\"close\"]"
                                            );
                                            if (fullscreenClose && isVisibleElement(fullscreenClose)) {
                                                return fullscreenClose;
                                            }
                                        } catch (e) {}

                                        return closePath;
                                    }

                                    var selectors = [
                                        "[data-fullscreen-element=\"close\"] [data-survey-fullscreen-control]",
                                        "[data-fullscreen-element=\"close\"]",
                                        "[data-fullscreen-element-name=\"close\"]",
                                        "[data-fullscreen-element=\"close-btn\"]",
                                        "[data-fullscreen-element-name=\"close-btn\"]",
                                        ".close-btn",
                                        "button[class*=\"close\"]",
                                        "[aria-label*=\"close\" i]",
                                        "[aria-label*=\"закры\" i]"
                                    ];

                                    for (var i = 0; i < selectors.length; i++) {
                                        var el = deepQuery(selectors[i]);
                                        if (el && !el.disabled && isVisibleElement(el)) return el;
                                    }

                                    return null;
                                }

                                /*
                                 * Yandex may render the X in an iframe or an open shadow root.
                                 * IMPORTANT: getBoundingClientRect() is relative to the viewport
                                 * of the document that owns the element. If the close control is
                                 * inside an iframe, those coordinates must be translated through
                                 * every frameElement before they are sent to the Android WebView.
                                 *
                                 * The supplied markup is a 40x40 control:
                                 *   [data-fullscreen-element="close"]
                                 *     [data-survey-fullscreen-control]
                                 *       <svg width="40" height="40">...</svg>
                                 *
                                 * We therefore prefer the real element center, translated to the
                                 * top-level WebView viewport, and only use the known top-right
                                 * fallback when the element is inaccessible.
                                 */
                                function getTopViewportRect(el) {
                                    if (!el || !el.getBoundingClientRect) return null;

                                    var rect = el.getBoundingClientRect();
                                    var left = rect.left;
                                    var top = rect.top;
                                    var width = rect.width;
                                    var height = rect.height;
                                    var win = el.ownerDocument ? el.ownerDocument.defaultView : window;
                                    var guard = 0;

                                    while (win && win !== window && guard < 8) {
                                        var frame = null;
                                        try {
                                            frame = win.frameElement;
                                        } catch (e) {
                                            break;
                                        }

                                        if (!frame || !frame.getBoundingClientRect) break;

                                        var frameRect = frame.getBoundingClientRect();
                                        left += frameRect.left;
                                        top += frameRect.top;
                                        win = frame.ownerDocument
                                            ? frame.ownerDocument.defaultView
                                            : window;
                                        guard++;
                                    }

                                    return {
                                        left: left,
                                        top: top,
                                        width: width,
                                        height: height
                                    };
                                }

                                function requestNativeCloseTap(closeElement) {
                                    var width = window.innerWidth || document.documentElement.clientWidth || 0;
                                    var height = window.innerHeight || document.documentElement.clientHeight || 0;

                                    if (width <= 0 || height <= 0) {
                                        AndroidAds.onStateLog("NATIVE_CLOSE_SKIP", "invalid_viewport");
                                        return false;
                                    }

                                    var tapX = null;
                                    var tapY = null;

                                    try {
                                        if (closeElement && closeElement.getBoundingClientRect) {
                                            var closeRect = getTopViewportRect(closeElement);
                                            if (closeRect && closeRect.width > 0 && closeRect.height > 0) {
                                                tapX = closeRect.left + closeRect.width / 2;
                                                tapY = closeRect.top + closeRect.height / 2;
                                                AndroidAds.onStateLog(
                                                    "NATIVE_CLOSE_EXACT",
                                                    "x=" + tapX + " y=" + tapY +
                                                    " rect=" + closeRect.width + "x" + closeRect.height
                                                );
                                            }
                                        }
                                    } catch (e) {
                                        AndroidAds.onStateLog(
                                            "NATIVE_CLOSE_RECT_ERROR",
                                            "error=" + (e && e.message ? e.message : String(e))
                                        );
                                    }

                                    var fullscreenVisible = false;

                                    try {
                                        var markers = Array.from(
                                            document.querySelectorAll(
                                                "[data-fullscreen-element], [data-fullscreen-element-name]"
                                            )
                                        );
                                        fullscreenVisible = markers.some(isVisibleElement);
                                    } catch (e) {}

                                    try {
                                        var frames = Array.from(document.querySelectorAll("iframe"));
                                        fullscreenVisible = fullscreenVisible || frames.some(function(frame) {
                                            try {
                                                var r = frame.getBoundingClientRect();
                                                return r.width >= width * 0.8 && r.height >= height * 0.8;
                                            } catch (e) {
                                                return false;
                                            }
                                        });
                                    } catch (e) {}

                                    /*
                                     * The ad is already known to be active because this code runs
                                     * from the dedicated ad runner after the hard 32s viewing time.
                                     * Do not require a DOM fullscreen marker here: Yandex can put
                                     * the fullscreen layer in a cross-origin iframe / closed shadow
                                     * root, which makes the marker invisible to page JS.
                                     */
                                    if (!fullscreenVisible) {
                                        AndroidAds.onStateLog(
                                            "NATIVE_CLOSE_FALLBACK",
                                            "fullscreen_marker_not_visible; tapping known top-right X area anyway viewport=" +
                                                width + "x" + height
                                        );
                                    }

                                    var x = tapX !== null ? tapX : Math.max(1, width - 20);
                                    var y = tapY !== null ? tapY : Math.max(1, 20);
                                    var source = tapX !== null
                                        ? "fullscreen_close_element"
                                        : "fullscreen_top_right_fallback";

                                    AndroidAds.onStateLog(
                                        "NATIVE_CLOSE_TAP",
                                        "x=" + x + " y=" + y +
                                        " viewport=" + width + "x" + height +
                                        " source=" + source
                                    );
                                    AndroidAds.onCloseTapRequested(x, y, source);
                                    return true;
                                }

                                function verifyReward(attempt) {
                                    if (finished) return;

                                    var diamondNow = readDiamondBalance(document);
                                    var btnNow = findWatchButton();
                                    var buttonCountNow = readButtonCount(btnNow);
                                    var diamondConfirmed = initialDiamond !== null &&
                                        diamondNow !== null && diamondNow >= initialDiamond + 7;
                                    var buttonCountChanged = initialButtonCount !== null &&
                                        buttonCountNow !== null &&
                                        buttonCountNow !== initialButtonCount;

                                    if (diamondConfirmed || buttonCountChanged) {
                                        finished = true;
                                        window.__mbAdsRunnerActive = false;
                                        AndroidAds.onStateLog(
                                            "REWARD_VERIFIED_LOCAL",
                                            "diamonds=" + (diamondNow === null ? "?" : diamondNow) +
                                            " before=" + (initialDiamond === null ? "?" : initialDiamond) +
                                            " count=" + (buttonCountNow === null ? "?" : buttonCountNow) +
                                            " initialCount=" + (initialButtonCount === null ? "?" : initialButtonCount) +
                                            " attempt=" + attempt
                                        );
                                        AndroidAds.onAdSuccess();
                                        return;
                                    }

                                    /*
                                     * Yandex fullscreen can finish before MangaBuff's reward
                                     * request reaches the server. Ask /balance directly instead
                                     * of waiting for the old DOM to refresh itself.
                                     */
                                    AndroidAds.onStateLog(
                                        "REWARD_SERVER_CHECK",
                                        "attempt=" + attempt +
                                        " localDiamonds=" + (diamondNow === null ? "?" : diamondNow) +
                                        " before=" + (initialDiamond === null ? "?" : initialDiamond)
                                    );

                                    fetch("/balance", {
                                        method: "GET",
                                        credentials: "include",
                                        cache: "no-store",
                                        headers: {
                                            "Accept": "text/html,application/xhtml+xml"
                                        }
                                    }).then(function(response) {
                                        if (!response.ok) throw new Error("balance_http_" + response.status);
                                        return response.text();
                                    }).then(function(html) {
                                        if (finished) return;

                                        try {
                                            var doc = new DOMParser().parseFromString(html, "text/html");
                                            var serverDiamond = readDiamondBalance(doc);
                                            var serverConfirmed = initialDiamond !== null &&
                                                serverDiamond !== null &&
                                                serverDiamond >= initialDiamond + 7;

                                            AndroidAds.onStateLog(
                                                "REWARD_SERVER_RESULT",
                                                "diamonds=" + (serverDiamond === null ? "?" : serverDiamond) +
                                                " before=" + (initialDiamond === null ? "?" : initialDiamond) +
                                                " confirmed=" + serverConfirmed
                                            );

                                            if (serverConfirmed) {
                                                finished = true;
                                                window.__mbAdsRunnerActive = false;
                                                AndroidAds.onStateLog(
                                                    "REWARD_VERIFIED",
                                                    "source=SERVER_BALANCE diamonds=" + serverDiamond +
                                                    " before=" + initialDiamond +
                                                    " attempt=" + attempt
                                                );
                                                AndroidAds.onAdSuccess();
                                                return;
                                            }

                                            if (attempt >= 18) {
                                                finished = true;
                                                window.__mbAdsRunnerActive = false;
                                                AndroidAds.onAdFailed(
                                                    "reward_not_confirmed serverDiamonds=" +
                                                    (serverDiamond === null ? "?" : serverDiamond) +
                                                    " before=" +
                                                    (initialDiamond === null ? "?" : initialDiamond)
                                                );
                                                return;
                                            }

                                            rewardPoll = setTimeout(function() {
                                                verifyReward(attempt + 1);
                                            }, 1000);
                                        } catch (e) {
                                            if (attempt >= 18) {
                                                finished = true;
                                                window.__mbAdsRunnerActive = false;
                                                AndroidAds.onAdFailed(
                                                    "reward_parse_error=" + (e.message || String(e))
                                                );
                                                return;
                                            }
                                            rewardPoll = setTimeout(function() {
                                                verifyReward(attempt + 1);
                                            }, 1000);
                                        }
                                    }).catch(function(error) {
                                        AndroidAds.onStateLog(
                                            "REWARD_SERVER_ERROR",
                                            "attempt=" + attempt +
                                            " error=" + (error && error.message ? error.message : String(error))
                                        );

                                        if (attempt >= 18) {
                                            finished = true;
                                            window.__mbAdsRunnerActive = false;
                                            AndroidAds.onAdFailed(
                                                "reward_server_check_failed=" +
                                                (error && error.message ? error.message : String(error))
                                            );
                                            return;
                                        }

                                        rewardPoll = setTimeout(function() {
                                            verifyReward(attempt + 1);
                                        }, 1000);
                                    });
                                }

                                function startAdMonitoring() {
                                    /*
                                     * Do not depend on Yandex's internal countdown.
                                     * MangaBuff/Yandex markup changes frequently, while
                                     * the required viewing window is 30 seconds.
                                     *
                                     * Our flow is deliberately simple:
                                     *   WATCH_CLICKED
                                     *       -> own 30s timer
                                     *       -> find close button
                                     *       -> click close
                                     *       -> verify reward
                                     *
                                     * The Yandex timer is only diagnostic now.
                                     */
                                    var adStartedAt = Date.now();
                                    var ownWatchDurationMs = 32000;
                                    var hardTimeoutMs = 65000;
                                    var lastSecondLogged = -1;

                                    AndroidAds.onStateLog(
                                        "OUR_TIMER_STARTED",
                                        "duration=32s"
                                    );

                                    watchTimer = setInterval(function() {
                                        if (finished) {
                                            clearInterval(watchTimer);
                                            return;
                                        }

                                        var elapsed = Date.now() - adStartedAt;
                                        var remainingMs = Math.max(0, ownWatchDurationMs - elapsed);
                                        var remainingSec = Math.ceil(remainingMs / 1000);

                                        // Log only when the displayed second changes.
                                        if (remainingSec !== lastSecondLogged) {
                                            lastSecondLogged = remainingSec;

                                            if (remainingSec > 0) {
                                                AndroidAds.onStateLog(
                                                    "OUR_TIMER",
                                                    "remaining=" + remainingSec + "s"
                                                );
                                            } else {
                                                AndroidAds.onStateLog(
                                                    "OUR_TIMER",
                                                    "remaining=0s close_search=true"
                                                );
                                            }
                                        }

                                        /*
                                         * Before 30 seconds we intentionally do not click
                                         * anything, even if Yandex already exposes the close
                                         * control. This is our hard minimum viewing period.
                                         */
                                        if (elapsed < ownWatchDurationMs) {
                                            return;
                                        }

                                        var close = findCloseButton();
                                        var closeVisible = isVisibleElement(close);

                                        AndroidAds.onStateLog(
                                            "CLOSE_SEARCH",
                                            "elapsed=" + Math.floor(elapsed / 1000) +
                                            "s found=" + !!close +
                                            " visible=" + closeVisible
                                        );

                                        if (closeVisible) {
                                            clearInterval(watchTimer);

                                            var nativeRequested = requestNativeCloseTap(close);

                                            AndroidAds.onStateLog(
                                                "AD_32S_FINISHED",
                                                "elapsed=" + Math.floor(elapsed / 1000) +
                                                "s source=" + (nativeRequested ? "NATIVE_EXACT" : "DOM")
                                            );

                                            setTimeout(function() {
                                                if (finished) return;

                                                var stillClose = findCloseButton();
                                                if (isVisibleElement(stillClose)) {
                                                    try {
                                                        stillClose.click();
                                                        AndroidAds.onStateLog(
                                                            "CLOSE_CLICKED",
                                                            "fallback_dom_after_native"
                                                        );
                                                    } catch (e) {
                                                        AndroidAds.onStateLog(
                                                            "CLOSE_DOM_CLICK_ERROR",
                                                            "error=" + (e && e.message ? e.message : String(e))
                                                        );
                                                    }
                                                }

                                                verifyReward(0);
                                            }, nativeRequested ? 500 : 0);
                                            return;
                                        }

                                        /*
                                         * DOM is invisible to us, but the ad fullscreen is
                                         * present. Use a real WebView tap on the known X area.
                                         * Request only once so we never spam the fullscreen.
                                         */
                                        if (!window.__mbAdsNativeCloseRequested) {
                                            window.__mbAdsNativeCloseRequested = true;

                                            if (requestNativeCloseTap(null)) {
                                                clearInterval(watchTimer);

                                                AndroidAds.onStateLog(
                                                    "CLOSE_NATIVE_REQUESTED",
                                                    "elapsed=" + Math.floor(elapsed / 1000) + "s"
                                                );

                                                setTimeout(function() {
                                                    if (finished) return;

                                                    var stillClose = findCloseButton();
                                                    AndroidAds.onStateLog(
                                                        "CLOSE_NATIVE_RETRY_CHECK",
                                                        "visible=" + isVisibleElement(stillClose)
                                                    );

                                                    verifyReward(0);
                                                }, 2500);
                                                return;
                                            }
                                        }

                                        /*
                                         * If the close control is temporarily inaccessible,
                                         * first give the Rewarded callback/automatic close a
                                         * few seconds to finish. At 32s Yandex may already have
                                         * completed the fullscreen session even though the open
                                         * shadow DOM is gone from the page.
                                         */
                                        if (elapsed < 38000) {
                                            AndroidAds.onStateLog(
                                                "CLOSE_NOT_FOUND_WAIT_REWARD",
                                                "elapsed=" + Math.floor(elapsed / 1000) +
                                                "s waiting_for_yandex_callback"
                                            );
                                            return;
                                        }

                                        /*
                                         * The ad may still be closing/rendering its control.
                                         * Keep searching after 38 seconds, but never restart
                                         * the 30-second viewing timer.
                                         */
                                        if (elapsed >= hardTimeoutMs) {
                                            clearInterval(watchTimer);
                                            finished = true;
                                            window.__mbAdsRunnerActive = false;
                                            AndroidAds.onAdFailed("close_button_timeout_60s");
                                        }
                                    }, 250);
                                }

                                function tryFindAndClick() {
                                    if (finished) return;

                                    var btn = findWatchButton();
                                    if (!btn) {
                                        if (Date.now() - buttonPollStarted >= 20000) {
                                            finished = true;
                                            AndroidAds.onAdFailed("watch_button_timeout");
                                            return;
                                        }
                                        AndroidAds.onStateLog(
                                            "WATCH_BUTTON_WAIT",
                                            "elapsed=" + Math.floor((Date.now() - buttonPollStarted) / 1000) + "s"
                                        );
                                        setTimeout(tryFindAndClick, 500);
                                        return;
                                    }

                                    var disabled = !!btn.disabled;
                                    var label = textOf(btn);
                                    var rawCount = btn.getAttribute("data-count");

                                    AndroidAds.onStateLog(
                                        "WATCH_BUTTON_FOUND",
                                        "count=" + (rawCount || "?") +
                                        " disabled=" + disabled +
                                        " text=" + label
                                    );

                                    if (disabled && isDailyLimitText(label)) {
                                        finished = true;
                                        window.__mbAdsRunnerActive = false;
                                        AndroidAds.onDailyLimit();
                                        return;
                                    }

                                    if (disabled) {
                                        if (Date.now() - buttonPollStarted >= 20000) {
                                            finished = true;
                                            AndroidAds.onAdFailed("watch_button_disabled_timeout");
                                            return;
                                        }
                                        setTimeout(tryFindAndClick, 500);
                                        return;
                                    }

                                    initialButton = btn;
                                    initialButtonCount = readButtonCount(btn);
                                    initialDiamond = readDiamondBalance(document);

                                    installYandexRewardHook();

                                    try {
                                        btn.click();
                                    } catch (e) {
                                        finished = true;
                                        AndroidAds.onAdFailed("watch_click_exception=" + (e.message || e));
                                        return;
                                    }

                                    AndroidAds.onStateLog("WATCH_CLICKED", "Клик по кнопке рекламы");
                                    startAdMonitoring();
                                }

                                tryFindAndClick();

                            } catch(e) {
                                window.__mbAdsRunnerActive = false;
                                window.__mbAdsRunnerStartedAt = 0;

                                var errorText = String(e && (e.stack || e.message) || e);

                                // Never let a missing Android bridge hide the real JS error.
                                try {
                                    if (typeof AndroidAds !== "undefined" && AndroidAds.onStateLog) {
                                        AndroidAds.onStateLog("RUNNER_EXCEPTION", errorText);
                                    }
                                    if (typeof AndroidAds !== "undefined" && AndroidAds.onAdFailed) {
                                        AndroidAds.onAdFailed("script_exception=" + errorText);
                                    }
                                } catch (bridgeError) {
                                    try {
                                        window.__mbAdsLastError = errorText +
                                            " | bridgeError=" +
                                            String(bridgeError && (bridgeError.message || bridgeError));
                                    } catch (_) {}
                                }
                            }
                        })();
                    """.trimIndent()

            var pageFinishedSeen = false
            var recoveryReloadUsed = false

            webView.webViewClient = object : WebViewClient() {

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    if (isAdViewingLocked()) {
                        log(
                            account.username,
                            "ADS: NAVIGATION_BLOCKED_DURING_VIEW url=" +
                                (request?.url?.toString().orEmpty()),
                            true
                        )
                        return true
                    }
                    return super.shouldOverrideUrlLoading(view, request)
                }

                override fun onPageStarted(
                    view: WebView?,
                    url: String?,
                    favicon: Bitmap?
                ) {
                    super.onPageStarted(view, url, favicon)

                    if (isAdViewingLocked()) {
                        log(
                            account.username,
                            "ADS: PAGE_LOAD_BLOCKED_DURING_VIEW url=" + url.orEmpty(),
                            true
                        )
                        view?.stopLoading()
                        return
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (isAdViewingLocked()) {
                        log(
                            account.username,
                            "ADS: PAGE_FINISHED_IGNORED_DURING_VIEW url=" + url.orEmpty(),
                            true
                        )
                        return
                    }

                    if (url?.contains("/balance") != true) return
                    pageFinishedSeen = true
                    log(account.username, "ADS: BALANCE_READY url=$url")

                    view?.evaluateJavascript("typeof AndroidAds") { bridgeType ->
                        log(account.username, "ADS: BRIDGE_CHECK type=$bridgeType")
                        if (bridgeType == "\"object\"" || bridgeType == "\"function\"") {
                            view.evaluateJavascript(script) { value ->
                                log(account.username, "ADS: RUNNER_INJECTED result=$value")
                            }
                        } else if (!recoveryReloadUsed) {
                            recoveryReloadUsed = true
                            log(account.username, "ADS: BRIDGE_MISSING -> RELOAD", true)
                            view.reload()
                        } else {
                            log(account.username, "ADS: BRIDGE_MISSING_AFTER_RELOAD", true)
                            safeResume(false)
                        }
                    }
                }
            }

            val balanceAlreadyOpen = webView.url?.contains("/balance") == true
            log(account.username, "ADS: NAVIGATE_BALANCE alreadyOpen=$balanceAlreadyOpen bridgeInstalled=true")
            if (balanceAlreadyOpen) webView.reload()
            else webView.loadUrl("https://mangabuff.ru/balance")

            mainHandler.postDelayed({
                if (continuation.isActive &&
                    !pageFinishedSeen &&
                    webView.url?.contains("/balance") == true &&
                    !recoveryReloadUsed
                ) {
                    recoveryReloadUsed = true
                    log(account.username, "ADS: PAGE_FALLBACK_RELOAD")
                    webView.reload()
                }
            }, 2500L)

            // This is an ad-session watchdog, not an injection watchdog.
            // The runner intentionally waits 30s before closing the fullscreen ad,
            // then can spend up to ~18s confirming the reward on /balance.
            // A 20s timeout used to abort the coroutine while OUR_TIMER was still
            // counting down, which navigated away and physically interrupted the ad.
            mainHandler.postDelayed({
                if (continuation.isActive) {
                    log(account.username, "ADS: SESSION_TIMEOUT url=${webView.url}", true)
                    try {
                        webView.evaluateJavascript("window.__mbAdsRunnerActive=false;", null)
                    } catch (_: Exception) {}
                    safeResume(false)
                }
            }, 90_000L)
        }
    }

    // =========================================================
    // MINE
    // =========================================================

    private suspend fun runMineTask(
        account: MangaBuffAccount,
        settings: GlobalSettings,
        webView: WebView
    ) {
        log(account.username, "MINE: START")
        updateStatus(account, "⛏️ Шахта: подготовка", true, "Шахта", 0f)
        val success = executeMiningInWebView(account, settings, webView)
        if (success) {
            log(account.username, "MINE: COMPLETED")
        } else {
            log(account.username, "MINE: FAILED", true)
            updateStatus(account, "❌ Шахта: не запущена", true, "Шахта", 0f)
        }
    }

    private suspend fun executeMiningInWebView(
        account: MangaBuffAccount,
        settings: GlobalSettings,
        webView: WebView
    ): Boolean = suspendCancellableCoroutine { continuation ->
        var resumed = false

        fun safeResume(result: Boolean) {
            if (!resumed && continuation.isActive) {
                resumed = true
                continuation.resume(result)
            }
        }

        mainHandler.post {
            class MineBridge {
                @JavascriptInterface
                fun onMineProgress(clicks: Int, total: Int, ore: Int) {
                    updateStatus(account, "⛏️ Шахта ($clicks/$total) • 🪨 $ore", true, "Шахта",
                        if (total > 0) clicks.toFloat() / total else 1f)
                }

                @JavascriptInterface
                fun onMineMined(oreMined: Int) {
                    if (oreMined > 0) addDaily(account) { it.copy(mineOre = it.mineOre + oreMined) }
                }

                @JavascriptInterface
                fun onMineExchange(oreMined: Int, oreExchanged: Int, diamondsReceived: Int, oreRemaining: Int) {
                    addDaily(account) { it.copy(mineExchangeOre = it.mineExchangeOre + oreExchanged, mineDiamonds = it.mineDiamonds + diamondsReceived) }
                    log(account.username, "MINE: EXCHANGE_SUCCESS mined=$oreMined exchanged=$oreExchanged diamonds=+$diamondsReceived remainingOre=$oreRemaining")
                    updateStatus(account, "⛏️ Шахта • 🪨 $oreMined → 💎+$diamondsReceived", true, "Шахта", 1f)
                }

                @JavascriptInterface
                fun onMineLog(msg: String) { log(account.username, "MINE: $msg") }

                @JavascriptInterface
                fun onMineComplete() { safeResume(true) }
            }

            // Keep AndroidMine registered across the /mine navigation.
            webView.addJavascriptInterface(MineBridge(), "AndroidMine")

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url?.contains("/mine") != true) return
                    if (view == null) { safeResume(false); return }
                    log(account.username, "MINE: PAGE_READY url=$url")

                    view.evaluateJavascript("typeof AndroidMine") { bridgeType ->
                        log(account.username, "MINE: BRIDGE_CHECK type=$bridgeType")
                        if (bridgeType != "\"object\"" && bridgeType != "\"function\"") {
                            log(account.username, "MINE: BRIDGE_MISSING", true)
                            safeResume(false)
                            return@evaluateJavascript
                        }

                    val script = """
                        (function() {
                            try {
                                var startedAt = Date.now();
                                var maxRuntime = 180000;
                                var clicks = 0;
                                var root = document.querySelector('.main-mine');
                                var initialOre = root ? (parseInt(root.getAttribute('data-ore') || '0', 10) || 0) : 0;
                                var hits = document.querySelector('.main-mine__game-hits-left');
                                var initialHits = hits ? parseInt((hits.innerText || '').trim(), 10) || 0 : 0;

                                function numText(el) {
                                    if (!el) return 0;
                                    var n = parseInt((el.innerText || el.textContent || '').replace(/\s/g, ''), 10);
                                    return isFinite(n) ? n : 0;
                                }

                                function currentOre() {
                                    var shopOre = document.querySelector('#modal-mine-shop .mine-shop__ore-count');
                                    if (shopOre) return numText(shopOre);
                                    var mine = document.querySelector('.main-mine');
                                    if (mine) {
                                        var value = parseInt(mine.getAttribute('data-ore') || '', 10);
                                        if (isFinite(value)) return value;
                                    }
                                    return 0;
                                }

                                function waitForShop(done) {
                                    var started = Date.now();
                                    function poll() {
                                        var modal = document.querySelector('#modal-mine-shop');
                                        var exchange = document.querySelector('#modal-mine-shop .mine-shop__ore-change-btn');
                                        if (modal && exchange) { done(); return; }
                                        if (Date.now() - started >= 10000) {
                                            AndroidMine.onMineLog('SHOP_NOT_OPEN');
                                            AndroidMine.onMineComplete();
                                            return;
                                        }
                                        setTimeout(poll, 200);
                                    }
                                    poll();
                                }

                                function waitForOreChange(beforeOre, done) {
                                    var started = Date.now();
                                    function poll() {
                                        var now = currentOre();
                                        if (now !== beforeOre || Date.now() - started >= 5000) { done(now); return; }
                                        setTimeout(poll, 200);
                                    }
                                    poll();
                                }

                                function finish() {
                                    if (Date.now() - startedAt > maxRuntime) {
                                        AndroidMine.onMineLog('TIMEOUT');
                                        AndroidMine.onMineComplete();
                                        return;
                                    }

                                    var minedOre = Math.max(0, currentOre() - initialOre);
                                    AndroidMine.onMineMined(minedOre);
                                    var header = document.querySelector('.main-mine__header_score');
                                    if (!header) {
                                        AndroidMine.onMineLog('CRYSTAL_HEADER_NOT_FOUND');
                                        AndroidMine.onMineComplete();
                                        return;
                                    }

                                    header.click();
                                    waitForShop(function() { runShop(minedOre); });
                                }

                                function runShop(minedOre) {
                                    var beforeOre = currentOre();
                                    var beforeDiamonds = numText(document.querySelector('.main-mine__header_score-count, .js-score'));

                                    function exchange() {
                                        var slider = document.querySelector('#modal-mine-shop .mine-shop__exchange-slider');
                                        var exchangeButton = document.querySelector('#modal-mine-shop .mine-shop__ore-change-btn');

                                        if (!slider || !exchangeButton) {
                                            AndroidMine.onMineLog('EXCHANGE_CONTROLS_NOT_FOUND');
                                            AndroidMine.onMineComplete();
                                            return;
                                        }

                                        var max = parseInt(slider.getAttribute('max') || '0', 10) || 0;
                                        if (max <= 0) {
                                            AndroidMine.onMineLog('NO_EXCHANGE_AVAILABLE');
                                            AndroidMine.onMineComplete();
                                            return;
                                        }

                                        slider.value = String(max);
                                        slider.dispatchEvent(new Event('input', { bubbles: true }));
                                        slider.dispatchEvent(new Event('change', { bubbles: true }));

                                        setTimeout(function() {
                                            var diamonds = numText(document.querySelector('#modal-mine-shop .mine-shop__exchange-diamonds'));
                                            var oreCost = numText(document.querySelector('#modal-mine-shop .mine-shop__exchange-ore'));

                                            if (diamonds <= 0 || oreCost <= 0 || oreCost !== diamonds * 100) {
                                                AndroidMine.onMineLog('EXCHANGE_VALIDATION_FAILED diamonds=' + diamonds + ' ore=' + oreCost);
                                                AndroidMine.onMineComplete();
                                                return;
                                            }

                                            exchangeButton.click();

                                            var started = Date.now();
                                            function verify() {
                                                var afterOre = currentOre();
                                                var afterDiamonds = numText(document.querySelector('.main-mine__header_score-count, .js-score'));

                                                if (afterOre !== beforeOre || afterDiamonds !== beforeDiamonds) {
                                                    var exchanged = Math.max(0, beforeOre - afterOre);
                                                    var received = Math.max(0, afterDiamonds - beforeDiamonds);
                                                    if (exchanged === oreCost && received === diamonds) {
                                                        AndroidMine.onMineExchange(minedOre, exchanged, received, afterOre);
                                                        AndroidMine.onMineComplete();
                                                        return;
                                                    }
                                                }

                                                if (Date.now() - started >= 8000) {
                                                    AndroidMine.onMineLog('EXCHANGE_VERIFY_TIMEOUT beforeOre=' + beforeOre +
                                                        ' afterOre=' + afterOre + ' beforeDiamonds=' + beforeDiamonds +
                                                        ' afterDiamonds=' + afterDiamonds);
                                                    AndroidMine.onMineComplete();
                                                    return;
                                                }
                                                setTimeout(verify, 250);
                                            }
                                            verify();
                                        }, 250);
                                    }

                                    if (\${settings.mineAutoUpgrade}) {
                                        var upgrade = document.querySelector('#modal-mine-shop .mine-shop__upgrade-btn');
                                        var priceText = upgrade && upgrade.parentElement ? upgrade.parentElement.innerText : '';
                                        var priceMatch = priceText.match(/Цена:\s*([0-9\s]+)\s*руды/i);
                                        var upgradePrice = priceMatch ? parseInt(priceMatch[1].replace(/\s/g, ''), 10) || 0 : 0;

                                        if (upgrade && upgradePrice > 0 && beforeOre >= upgradePrice) {
                                            AndroidMine.onMineLog('UPGRADE_START price=' + upgradePrice + ' ore=' + beforeOre);
                                            upgrade.click();
                                            waitForOreChange(beforeOre, function(afterUpgradeOre) {
                                                AndroidMine.onMineLog('UPGRADE_RESULT ore=' + afterUpgradeOre);
                                                exchange();
                                            });
                                            return;
                                        }
                                    }

                                    exchange();
                                }

                                function tap() {
                                    if (Date.now() - startedAt > maxRuntime) {
                                        AndroidMine.onMineLog('TIMEOUT');
                                        AndroidMine.onMineComplete();
                                        return;
                                    }

                                    var current = document.querySelector('.main-mine__game-hits-left');
                                    var left = current ? parseInt((current.innerText || '').trim(), 10) || 0
                                        : Math.max(0, initialHits - clicks);
                                    var button = document.querySelector('button.main-mine__game-tap');

                                    if (button && left > 0) {
                                        button.click();
                                        clicks++;
                                        if (clicks % 5 === 0 || left <= 1) {
                                            AndroidMine.onMineProgress(clicks, initialHits, currentOre());
                                        }
                                        setTimeout(tap, 1000 + Math.floor(Math.random() * 500));
                                    } else {
                                        AndroidMine.onMineProgress(clicks, initialHits, currentOre());
                                        finish();
                                    }
                                }

                                if (initialHits <= 0) finish(); else tap();
                            } catch(e) {
                                AndroidMine.onMineLog('SCRIPT_ERROR ' + (e && e.message ? e.message : e));
                                AndroidMine.onMineComplete();
                            }
                        })();
                    """.trimIndent()

                        view.evaluateJavascript(script, null)
                    }
                }
            }

            val mineAlreadyOpen = webView.url?.contains("/mine") == true
            log(account.username, "MINE: NAVIGATE_MINE alreadyOpen=$mineAlreadyOpen bridgeInstalled=true")
            if (mineAlreadyOpen) {
                webView.reload()
            } else {
                webView.loadUrl("https://mangabuff.ru/mine")
            }

            mainHandler.postDelayed({
                if (continuation.isActive) {
                    log(account.username, "MINE: WATCHDOG_TIMEOUT url=${webView.url}", true)
                    safeResume(false)
                }
            }, 190_000L)
        }
    }

    // =========================================================
    // READER HELPERS
    // =========================================================

    private fun isReaderChapterUrl(url: String): Pair<Boolean, String> {
        val clean = url.substringBefore('?').substringBefore('#')
        val parts = clean
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("mangabuff.ru")
            .split("/")
            .filter { it.isNotEmpty() }

        if (
            parts.size >= 4 &&
            parts[0] == "manga" &&
            parts[parts.size - 2].all { it.isDigit() } &&
            parts.last().all { it.isDigit() }
        ) {
            return Pair(true, "MANGA_PREFIX")
        }

        return Pair(false, "NONE")
    }

    private fun isMangaInfoUrl(url: String): Boolean {
        val clean = url.substringBefore('?').substringBefore('#')
        val parts = clean
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("mangabuff.ru")
            .split("/")
            .filter { it.isNotEmpty() }

        if (parts.size == 2 && parts[0] == "manga") {
            return true
        }

        return false
    }

    private fun ensureCanonicalMangaUrl(url: String): String {
        val clean = url.substringBefore('?').substringBefore('#')
        val parts = clean
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("mangabuff.ru")
            .split("/")
            .filter { it.isNotEmpty() }

        if (parts.size == 1) {
            return "https://mangabuff.ru/manga/${parts[0]}"
        }
        if (parts.size >= 2 && parts[0] == "manga") {
            return "https://mangabuff.ru/manga/${parts[1]}"
        }

        return clean
    }

    // =========================================================
    // READER
    // =========================================================

    private suspend fun runReaderTask(
        account: MangaBuffAccount,
        settings: GlobalSettings,
        webView: WebView
    ) {
        log(account.username, "READER: OPEN")
        log(account.username, "READER: START targetChapters=${settings.readerChapters}")

        currentMangaUrl = ensureCanonicalMangaUrl(account.getSafeActiveMangaUrl())

        skippedMangaUrls.clear()
        completedMangaIds.clear()
        completedChapterIds.clear()
        readChapterUrlsInRun.clear()
        historyServerAcceptedChapterIds.clear()
        currentHistoryPostChapterIds.clear()

        activeChapterContext = null

        lastFinishedChapterUrl = ""
        lastFinishedChapterNumber = ""
        lastFinishedChapterId = ""
        lastFinishedMangaTitle = ""
        totalMangaChapters = 0

        pendingReadQuestBefore = null
        pendingReadQuestConfirmed = false
        pendingReadQuestConfirmedValue = ""
        pendingReadChapterId = ""
        pendingReadChapterUrl = ""

        var chaptersReadCount = 0
        var chaptersSinceComment = 0
        var nextCommentAfter = (5..15).random()

        var dailyCommentCount = 0
        var nextChapterUrlToOpen = ""

        val target = settings.readerChapters
        currentSessionTarget = target
        currentSessionChaptersRead = 0

        while (chaptersReadCount < target) {
            coroutineContext.ensureActive()

            currentSessionChaptersRead = chaptersReadCount
            updateReaderStatus(account)

            log(account.username, "READER: CHAPTER_LOOP readCount=$chaptersReadCount target=$target")

            when (
                val result = executeMangaSession(
                    account,
                    webView,
                    chaptersReadCount + 1,
                    target,
                    {},
                    nextChapterUrlToOpen
                )
            ) {
                is ReaderResult.ChapterRead -> {
                    val chId = result.chapterId
                    val chUrl = result.chapterUrl
                    nextChapterUrlToOpen = result.nextChapterUrl

                    val duplicate = (chId.isNotBlank() && chId in completedChapterIds) ||
                            (chUrl.isNotBlank() && chUrl in readChapterUrlsInRun)

                    if (duplicate) {
                        log(account.username, "READER: DUPLICATE_CHAPTER_SKIPPED chapterId=$chId", true)
                        break
                    }

                    if (chId.isNotBlank()) completedChapterIds.add(chId)
                    if (chUrl.isNotBlank()) readChapterUrlsInRun.add(chUrl)

                    chaptersReadCount++
                    currentSessionChaptersRead = chaptersReadCount
                    updateReaderStatus(account)

                    log(account.username, "READER: CHAPTER_READ count=" + chaptersReadCount +
                        "/" + target + " id=" + chId + " serverQuestGate=DEFERRED_CCL_ACCOUNTING")

                    chaptersSinceComment++

                    // MangaBuff's reading quest uses batched/CCL accounting: the
                    // visible "Главы X/75" value may advance only after several
                    // chapters. Refresh it every 5 completed chapters for UI/
                    // diagnostics, but NEVER make this refresh a reader gate.
                    if (chaptersReadCount % 5 == 0) {
                        log(account.username,
                            "READER: PERIODIC_BALANCE_REFRESH chaptersRead=" + chaptersReadCount +
                                " reason=5_CHAPTERS")
                        try {
                            val refreshed = withTimeoutOrNull(12_000L) {
                                fetchAndLogBalanceInfo(account, webView)
                            }
                            if (refreshed == null) {
                                log(
                                    account.username,
                                    "READER: PERIODIC_BALANCE_REFRESH_TIMEOUT ignored=true chaptersRead=$chaptersReadCount"
                                )
                            } else {
                                log(
                                    account.username,
                                    "READER: PERIODIC_BALANCE_REFRESH_DONE chaptersRead=$chaptersReadCount"
                                )
                            }
                        } catch (e: Exception) {
                            // Statistics refresh is diagnostic only. It must never
                            // become a reader/account failure.
                            log(
                                account.username,
                                "READER: PERIODIC_BALANCE_REFRESH_FAILED ignored=true error=" + e.message
                            )
                        }
                    }

                    log(account.username, "COMMENT: DECISION chaptersSinceComment=" +
                        chaptersSinceComment + " nextCommentAfter=" + nextCommentAfter)

                    if (account.commentEnabled &&
                        chaptersSinceComment >= nextCommentAfter &&
                        dailyCommentCount < settings.commentCount
                    ) {
                        val targetCommentUrl = chUrl.ifBlank {
                            activeChapterContext?.actualChapterUrl?.ifBlank { null }
                                ?: lastFinishedChapterUrl.ifBlank { null }
                                ?: currentMangaUrl.ifBlank { null }
                        }

                        if (!targetCommentUrl.isNullOrBlank()) {
                            val success = runCommentOnCurrentChapter(account, webView, targetCommentUrl)
                            if (success) {
                                dailyCommentCount++
                                addDaily(account) { it.copy(comments = it.comments + 1) }
                                chaptersSinceComment = 0
                                nextCommentAfter = (5..15).random()
                                log(account.username, "COMMENT: SUCCESS dailyCount=" +
                                    dailyCommentCount + "/" + settings.commentCount +
                                    " nextThreshold=" + nextCommentAfter)
                                delay(COMMENT_DELAY_MS)
                            }
                        }
                    }

                    if (nextChapterUrlToOpen.isBlank()) {
                        log(account.username, "READER: NEXT_CHAPTER_UNKNOWN_AFTER_READ", true)
                        break
                    }

                    delay(3000L)
                }

                is ReaderResult.MangaSkipped -> {
                    val skipUrl = ensureCanonicalMangaUrl(result.mangaUrl.ifBlank { currentMangaUrl })
                    log(account.username, "READER: MANGA_SKIPPED title='" + result.title + "' url=" + skipUrl)
                    if (skipUrl.isNotBlank()) skippedMangaUrls.add(skipUrl)
                    currentMangaUrl = ""
                    nextChapterUrlToOpen = ""
                    totalMangaChapters = 0
                    currentSessionChaptersRead = chaptersReadCount
                    activeChapterContext = null
                    onMangaActiveUrlUpdate(account.id, "", "")
                    delay(500L)
                }

                is ReaderResult.MangaCompleted -> {
                    log(account.username, "READER: MANGA_COMPLETED title='" + result.title + "'")

                    if (lastFinishedChapterId.isNotBlank()) completedChapterIds.add(lastFinishedChapterId)
                    if (lastFinishedChapterUrl.isNotBlank()) readChapterUrlsInRun.add(lastFinishedChapterUrl)

                    if (currentMangaUrl.isNotBlank()) skippedMangaUrls.add(currentMangaUrl)

                    currentMangaUrl = ""
                    nextChapterUrlToOpen = ""
                    onMangaActiveUrlUpdate(account.id, "", "")
                    delay(1000L)
                }

                is ReaderResult.MangaAlreadyCompleted -> {
                    log(account.username, "READER: MANGA_ALREADY_COMPLETED -> SKIP_MANGA")

                    if (currentMangaUrl.isNotBlank()) {
                        skippedMangaUrls.add(currentMangaUrl)
                    }

                    currentMangaUrl = ""
                    nextChapterUrlToOpen = ""
                    onMangaActiveUrlUpdate(account.id, "", "")
                }

                is ReaderResult.NoMangaAvailable -> break
                is ReaderResult.Failed -> {
                    log(account.username, "READER: FAILED reason=${result.reason}", true)
                    break
                }
                is ReaderResult.Cancelled -> break
            }
        }

        activeChapterContext = null
        activeReaderSkip = null
        log(account.username, "READER: STOP_CLEANUP_COMPLETE")
        log(account.username, "READER: FINISHED totalRead=$chaptersReadCount/$target")
    }

    // =========================================================
    // READER SESSION
    // =========================================================

    private suspend fun executeMangaSession(
        account: MangaBuffAccount,
        webView: WebView,
        chapterIndex: Int,
        targetChapters: Int,
        onStepInfo: (String) -> Unit,
        startUrl: String = ""
    ): ReaderResult = suspendCancellableCoroutine { continuation ->

        var resumed = false

        fun safeResume(result: ReaderResult) {
            if (!resumed && continuation.isActive) {
                resumed = true
                activeReaderSkip = null
                activeReaderMarkRead = null
                continuation.resume(result)
            }
        }

        continuation.invokeOnCancellation {
            activeReaderSkip = null
            activeReaderMarkRead = null
            mainHandler.post {
                try { webView.stopLoading() } catch (_: Exception) {}
                try {
                    webView.evaluateJavascript(
                        "try{window.__mbNativeFingerRunning=false;if(window.__mbNativeFingerTimer){clearTimeout(window.__mbNativeFingerTimer);window.__mbNativeFingerTimer=null;}}catch(e){}",
                        null
                    )
                } catch (_: Exception) {}
            }
        }

        activeReaderSkip = {
            if (!resumed && continuation.isActive) {
                log(account.username, "READER: SKIP_MANGA_EXECUTED title='" + cleanMangaTitle(lastFinishedMangaTitle) + "' url='" + currentMangaUrl + "'")
                try {
                    webView.evaluateJavascript(
                        "try{window.__mbNativeFingerRunning=false;if(window.__mbNativeFingerTimer){clearTimeout(window.__mbNativeFingerTimer);window.__mbNativeFingerTimer=null;}}catch(e){}",
                        null
                    )
                } catch (_: Exception) {}
                safeResume(
                    ReaderResult.MangaSkipped(
                        mangaUrl = currentMangaUrl,
                        title = cleanMangaTitle(lastFinishedMangaTitle)
                    )
                )
            }
        }

        activeReaderMarkRead = {
            if (!resumed && continuation.isActive) {
                log(account.username, "READER: MARK_READ_EXECUTED title='" + cleanMangaTitle(lastFinishedMangaTitle) + "' url='" + currentMangaUrl + "'")
                try {
                    webView.evaluateJavascript(
                        "try{window.__mbNativeFingerRunning=false;if(window.__mbNativeFingerTimer){clearTimeout(window.__mbNativeFingerTimer);window.__mbNativeFingerTimer=null;}}catch(e){}",
                        null
                    )
                } catch (_: Exception) {}

                pendingMangaMarkAsRead = true
                val markUrl = ensureCanonicalMangaUrl(
                    currentMangaUrl.ifBlank { activeChapterContext?.mangaUrl ?: "" }
                )
                if (markUrl.isBlank()) {
                    log(account.username, "READER: MARK_READ_NO_MANGA_URL", true)
                    pendingMangaMarkAsRead = false
                } else {
                    currentMangaUrl = markUrl
                    log(account.username, "READER: OPEN_MANGA_INFO_FOR_MANUAL_READ_MARK url=" + markUrl)
                    mainHandler.post {
                        try { webView.loadUrl(markUrl) }
                        catch (e: Exception) {
                            pendingMangaMarkAsRead = false
                            log(account.username, "READER: MARK_READ_NAVIGATION_ERROR error=" + (e.message ?: "unknown"), true)
                        }
                    }
                }
            }
        }

        giftsFound = 0
        pendingMangaMarkAsRead = false

        mainHandler.post {

            class ReaderBridge {

                @JavascriptInterface
                fun onLogStep(msg: String) {
                    log(account.username, "[Читалка] $msg")
                    onStepInfo(msg)
                }

                @JavascriptInterface
                fun nativeSwipe(
                    x1: Float,
                    y1: Float,
                    x2: Float,
                    y2: Float,
                    durationMs: Long
                ) {
                    mainHandler.post {
                        try {
                            if (!webView.isAttachedToWindow) return@post
                            dispatchNativeSwipe(webView, x1, y1, x2, y2, durationMs)
                        } catch (e: Exception) {
                            log(account.username, "READER: NATIVE_TOUCH_SWIPE_EXCEPTION " + (e.message ?: "unknown"), true)
                        }
                    }
                }

                @JavascriptInterface
                fun nativeRecoveryScroll(deltaPx: Int) {
                    mainHandler.post {
                        try {
                            if (!webView.isAttachedToWindow) return@post
                            val safeDelta = deltaPx.coerceIn(240, 1400)

                            // Emergency recovery only: first use the native WebView page
                            // scrolling API. If the page is still in a transient rendering
                            // state, pageDown() gives Chromium another native scroll request.
                            val beforeY = webView.scrollY
                            webView.scrollBy(0, safeDelta)
                            val afterY = webView.scrollY

                            if (afterY == beforeY) {
                                webView.pageDown(false)
                                log(
                                    account.username,
                                    "READER: NATIVE_RECOVERY_PAGEDOWN delta=$safeDelta beforeY=$beforeY"
                                )
                            } else {
                                log(
                                    account.username,
                                    "READER: NATIVE_RECOVERY_SCROLLBY delta=$safeDelta beforeY=$beforeY afterY=$afterY"
                                )
                            }
                        } catch (e: Exception) {
                            log(account.username, "READER: NATIVE_RECOVERY_SCROLLBY_ERROR " + (e.message ?: "unknown"), true)
                        }
                    }
                }

                @JavascriptInterface
                fun onCurrentChapterData(
                    mangaIdStr: String,
                    chapterIdStr: String,
                    name: String,
                    slug: String,
                    volume: String,
                    chapter: String
                ) {
                    log(
                        account.username,
                        "READER: CURRENT_CHAPTER id=$chapterIdStr mangaId=$mangaIdStr title='$name' chapter=$chapter"
                    )

                    if (chapter.isNotBlank()) lastFinishedChapterNumber = chapter
                    if (name.isNotBlank()) lastFinishedMangaTitle = cleanMangaTitle(name)
                    if (chapterIdStr.isNotBlank()) lastFinishedChapterId = chapterIdStr

                    activeChapterContext = activeChapterContext?.copy(
                        mangaId = mangaIdStr.ifBlank { activeChapterContext?.mangaId ?: "" },
                        chapterId = chapterIdStr.ifBlank { activeChapterContext?.chapterId ?: "" },
                        mangaTitle = cleanMangaTitle(name.ifBlank { activeChapterContext?.mangaTitle ?: "" }),
                        chapterNumber = chapter.ifBlank { activeChapterContext?.chapterNumber ?: "" }
                    )

                    log(account.username, "CHAPTER_CONTEXT_SAVED id=$chapterIdStr mangaId=$mangaIdStr title='$name' chapter=$chapter")
                }

                @JavascriptInterface
                fun onTotalChaptersFound(total: Int) {
                    if (total > 0) {
                        if (totalMangaChapters <= 0 || total > totalMangaChapters) {
                            totalMangaChapters = total
                            log(account.username, "READER: TOTAL_CHAPTERS_FOUND total=$total")
                            updateReaderStatus(account)
                        }
                    } else {
                        if (totalMangaChapters <= 0) {
                            log(account.username, "READER: TOTAL_CHAPTERS_UNKNOWN")
                        }
                    }
                }

                @JavascriptInterface
                fun onSaveActiveManga(url: String, title: String) {
                    val canonical = ensureCanonicalMangaUrl(url)
                    if (canonical.isNotBlank() && canonical != currentMangaUrl) {
                        currentMangaUrl = canonical
                        totalMangaChapters = 0
                        currentSessionChaptersRead = 0
                        log(account.username, "READER: NEW_MANGA url=$canonical")
                    }
                    if (title.isNotBlank()) {
                        lastFinishedMangaTitle = cleanMangaTitle(title)
                    }

                    log(account.username, "READER: SAVE_ACTIVE_MANGA url=$currentMangaUrl title=$title")
                    log(account.username, "READER_UI: CURRENT_MANGA_CHANGED title='$title' url='$currentMangaUrl'")
                    onMangaActiveUrlUpdate(account.id, currentMangaUrl, title)
                }

                @JavascriptInterface
                fun onCardGiftCollected() {
                    giftsFound++
                    log(account.username, "READ: CARD_GIFT_COLLECTED total=$giftsFound")
                }

                @JavascriptInterface
                fun onReadButtonUrlAudit(locHref: String, baseURI: String, rawHref: String, elemHref: String) {
                    log(account.username, "READ_BUTTON_URL_AUDIT:\nlocation.href=$locHref\ndocument.baseURI=$baseURI\nrawHref=$rawHref\nelement.href=$elemHref")
                }

                @JavascriptInterface
                fun onNextChapterLinkAudit(text: String, rawHref: String, elemHref: String) {
                    log(account.username, "NEXT_CHAPTER_LINK_AUDIT:\ntext=$text\nrawHref=$rawHref\nelement.href=$elemHref")
                }

                @JavascriptInterface
                fun onNextChapterDomAudit(tag: String, classes: String, outerHtml: String) {
                    val limited = if (outerHtml.length > 1800) outerHtml.take(1800) + "...[TRUNCATED]" else outerHtml
                    log(account.username, "NEXT_CHAPTER_DOM_AUDIT:\ntag=$tag\nclass=$classes\nouterHTML=$limited")
                }

                @JavascriptInterface
                fun onReadButtonFound(text: String, href: String, slug: String) {
                    log(account.username, "READER: READ_BUTTON_FOUND text='$text' rawHref='$href' slug='$slug'")
                    if (text.contains("Продолжить с")) {
                        val number = Regex("\\d+").find(text)?.value ?: "1"
                        log(account.username, "READER: READ_BUTTON_CONTINUE chapter=$number")
                    }
                }

                @JavascriptInterface
                fun onReadButtonNotFound() {
                    log(account.username, "READER: READ_BUTTON_NOT_FOUND")
                }

                @JavascriptInterface
                fun onChapterListCheck(attempt: Int, itemCount: Int) {
                    if (itemCount > 0) {
                        if (totalMangaChapters <= 0 || itemCount > totalMangaChapters) {
                            totalMangaChapters = itemCount
                            log(account.username, "READER: TOTAL_CHAPTERS_FOUND total=$itemCount")
                            updateReaderStatus(account)
                        }
                    }
                }

                @JavascriptInterface
                fun onChapterListEmpty() {
                    log(account.username, "READER: CHAPTER_LIST_EMPTY")
                }

                @JavascriptInterface
                fun onMangaAlreadyCompleted(title: String) {
                    safeResume(ReaderResult.MangaAlreadyCompleted)
                }

                @JavascriptInterface
                fun onMangaMarkedRead(title: String, found: Boolean = true) {
                    if (!found) {
                        log(account.username, "READER: READ_ACTION_NOT_CONFIRMED folder-id=3", true)
                        return
                    }
                    log(account.username, "READER: READ_ACTION_CONFIRMED folder-id=3 (Прочитано)")
                    pendingMangaMarkAsRead = false
                    safeResume(ReaderResult.MangaCompleted("", title))
                }

                @JavascriptInterface
                fun onMangaCompleted(title: String) {
                    safeResume(ReaderResult.MangaCompleted("", title))
                }

                @JavascriptInterface
                fun onNextChapterUnknown() {
                    log(account.username, "READER: NEXT_CHAPTER_UNKNOWN", true)
                    safeResume(ReaderResult.Failed("NEXT_CHAPTER_UNKNOWN"))
                }

                @JavascriptInterface
                fun onHistoryPoolBefore(size: Int) {
                    lastPoolSize = size
                    log(account.username, "[READQUEST_DIAG] HISTORY_POOL_BEFORE size=$size")
                }

                @JavascriptInterface
                fun onHistoryPoolItem(chapterId: String) {
                    log(account.username, "[READQUEST_DIAG] HISTORY_POOL_ITEM chapterId=$chapterId")
                }

                @JavascriptInterface
                fun onHistoryPoolAfter(size: Int) {
                    lastPoolSize = size
                    log(account.username, "[READQUEST_DIAG] HISTORY_POOL_AFTER size=$size")
                }

                @JavascriptInterface
                fun onHistoryPostStarted(url: String, currentChapterId: String) {
                    currentHistoryPostChapterIds.clear()
                    log(account.username, "READER: MB_HISTORY_POST_STARTED")
                    log(account.username, "url=$url method=POST currentChapterId=$currentChapterId")
                    log(account.username, "[READQUEST_DIAG] POST_PREPARE quest=$lastKnownReadQuest poolSize=$lastPoolSize")
                }

                @JavascriptInterface
                fun onHistoryPostItems(count: Int) {
                    lastItemCount = count
                    log(account.username, "READER: MB_HISTORY_POST_ITEMS count=$count")
                }

                @JavascriptInterface
                fun onHistoryPostItem(index: Int, mangaId: String, chapterId: String) {
                    if (chapterId.isNotBlank()) currentHistoryPostChapterIds.add(chapterId)
                    log(account.username, "READER: MB_HISTORY_POST_ITEM index=$index manga_id=$mangaId chapter_id=$chapterId")
                    log(account.username, "[READQUEST_DIAG] ITEM index=$index mangaId=$mangaId chapterId=$chapterId")
                }

                @JavascriptInterface
                fun onHistoryPostStatus(status: Int) {
                    log(account.username, "READER: MB_HISTORY_POST_STATUS=$status")
                }

                @JavascriptInterface
                fun onHistoryPostResponse(response: String) {
                    val sanitized = if (response.length > 2048) response.take(2048) + "...[TRUNCATED]" else response
                    log(account.username, "READER: MB_HISTORY_POST_RESPONSE=$sanitized")
                }

                @JavascriptInterface
                fun onHistoryPostFinished(status: Int, url: String) {
                    val ok = status in 200..299
                    val batchIds = currentHistoryPostChapterIds.toList()
                    log(account.username, "READER: MB_HISTORY_POST_FINISHED status=$status url=$url")
                    log(account.username, "[READQUEST_DIAG] POST_RESULT status=$status")
                    if (ok) {
                        historyServerAcceptedChapterIds.addAll(batchIds)
                        log(account.username, "[READQUEST_DIAG] POST_HTTP_SUCCESS status=$status items=${batchIds.joinToString(",").ifBlank { "none" }}")
                    } else {
                        log(account.username, "[READQUEST_DIAG] POST_HTTP_FAILED status=$status", true)
                    }
                    currentHistoryPostChapterIds.clear()
                }

                @JavascriptInterface
                fun onPreNextChapterState(
                    isRead: Boolean,
                    readStatusSend: Boolean,
                    ccl: Int,
                    historyPoolSize: Int,
                    currentChapterId: String,
                    itemsJson: String
                ) {
                    log(account.username, "READER: MB_PRE_NEXT_CHAPTER_STATE")
                    log(account.username, "is_read=$isRead")
                    log(account.username, "read_status_send=$readStatusSend")
                    log(account.username, "ccl=$ccl")
                    log(account.username, "history_pool_size=$historyPoolSize")
                    log(account.username, "current_chapter_id=$currentChapterId")

                    try {
                        val type = object : com.google.gson.reflect.TypeToken<List<Map<String, Any>>>() {}.type
                        val items: List<Map<String, Any>> = com.google.gson.Gson().fromJson(itemsJson, type) ?: emptyList()
                        items.forEachIndexed { index, map ->
                            val mId = map["manga_id"]?.toString() ?: ""
                            val cId = map["chapter_id"]?.toString() ?: ""
                            log(account.username, "READER: MB_HISTORY_POOL_ITEM index=$index manga_id=$mId chapter_id=$cId")
                        }
                    } catch (_: Exception) {
                        if (itemsJson.isNotBlank() && itemsJson != "[]") {
                            log(account.username, "history_pool_contents=$itemsJson")
                        }
                    }
                }

                @JavascriptInterface
                fun requestServerReadQuest(progress: Int) {
                    log(account.username, "READER: SERVER_QUEST_NATIVE_REQUEST progress=" + progress + "%")

                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val request = Request.Builder()
                                .url("https://mangabuff.ru/balance")
                                .headers(
                                    getBaseHeaders(account).newBuilder()
                                        .set("Accept", "text/html,application/xhtml+xml")
                                        .build()
                                )
                                .get()
                                .build()

                            val response = httpClient.newCall(request).execute()
                            val html = response.body?.string().orEmpty()
                            val code = response.code

                            var quest = ""
                            if (response.isSuccessful && html.isNotBlank()) {
                                val doc = Jsoup.parse(html)
                                doc.select(".wallet-panel__stat-head").forEach { head ->
                                    if (quest.isNotBlank()) return@forEach
                                    val label = head.selectFirst("span")?.text().orEmpty()
                                        .replace("\\s+".toRegex(), " ")
                                        .trim()
                                        .lowercase()
                                    val value = head.selectFirst("b")?.text().orEmpty()
                                        .replace("\\s+".toRegex(), " ")
                                        .trim()

                                    if ((label.contains("глав") || label.contains("чита")) &&
                                        Regex("\\d+\\s*/\\s*\\d+").matches(value)
                                    ) {
                                        quest = value.replace("\\s+".toRegex(), "")
                                    }
                                }

                                if (quest.isBlank()) {
                                    val bodyText = doc.body()?.text().orEmpty()
                                    val match = Regex("(?:глав|чита)[^\\d]{0,80}(\\d+)\\s*/\\s*(\\d+)", RegexOption.IGNORE_CASE)
                                        .find(bodyText)
                                    if (match != null) {
                                        quest = "${match.groupValues[1]}/${match.groupValues[2]}"
                                    }
                                }
                            }

                            mainHandler.post {
                                if (quest.isNotBlank()) {
                                    log(account.username, "READER: SERVER_QUEST_NATIVE_RESPONSE progress=${progress}% http=${code} quest=${quest}")
                                    onServerReadQuestProbe(quest, progress)
                                } else {
                                    log(account.username, "READER: SERVER_QUEST_NATIVE_NO_VALUE progress=${progress}% http=${code} ignored=true")
                                }
                            }
                        } catch (e: Exception) {
                            mainHandler.post {
                                log(
                                    account.username,
                                    "READER: SERVER_QUEST_NATIVE_ERROR progress=${progress}% error=${e.message}",
                                    true
                                )
                            }
                        }
                    }
                }

                @JavascriptInterface
                fun onServerReadQuestProbe(quest: String, progress: Int) {
                    val before = pendingReadQuestBefore ?: return
                    val beforeNum = before.substringBefore('/').toIntOrNull() ?: 0
                    val afterNum = quest.substringBefore('/').toIntOrNull() ?: beforeNum

                    log(
                        account.username,
                        "READER: SERVER_QUEST_PROBE progress=" + progress +
                            "% before=" + before + " after=" + quest
                    )
                    lastKnownReadQuest = quest

                    if (afterNum > beforeNum) {
                        pendingReadQuestConfirmed = true
                        pendingReadQuestConfirmedValue = quest
                        log(
                            account.username,
                            "READER: SERVER_QUEST_INCREMENT_CONFIRMED_DURING_NEXT_CHAPTER " +
                                before + "->" + quest + " progress=" + progress + "%"
                        )
                    }
                }

                @JavascriptInterface
                fun onChapterFinishedWithElapsed(
                    chapterUrl: String,
                    number: String,
                    chapterId: String,
                    title: String,
                    elapsedMs: Long,
                    nextChapterUrl: String,
                    isRealLastChapter: Boolean,
                    confirmationSource: String
                ) {
                    val ctx = activeChapterContext
                    if (ctx != null && ctx.completionProcessed) {
                        log(account.username, "READER: COMPLETION_DUPLICATE_IGNORED chapterId=$chapterId")
                        return
                    }
                    ctx?.completionProcessed = true

                    lastFinishedChapterUrl = chapterUrl
                    lastFinishedChapterNumber = number
                    lastFinishedChapterId = chapterId
                    if (title.isNotBlank()) {
                        lastFinishedMangaTitle = cleanMangaTitle(title)
                    }

                    val questBefore = ctx?.readQuestBefore
                        ?.takeIf { it.isNotBlank() }
                        ?: lastKnownReadQuest

                    val confirmation = confirmationSource.ifBlank { "UNKNOWN" }

                    log(account.username, "READ: CHAPTER_READER_FINISHED elapsed=" + elapsedMs + "ms")
                    log(account.username, "READ: CHAPTER_END_REACHED chapter=" + number)
                    log(account.username, "READER: CHAPTER_CONFIRMATION source=" + confirmation)
                    log(account.username, "READER: COMPLETION_ACCEPTED chapterId=" + chapterId)

                    /*
                     * MangaBuff uses ccl=2 in the tested flow. The first chapter
                     * can stay in history_pool while /balance still shows the
                     * old value. The next chapter causes a batched /addHistory
                     * POST, and only then the quest counter catches up.
                     *
                     * Therefore the /balance quest delta is NOT a per-chapter
                     * gate. A missing 4/75 -> 5/75 immediately after a chapter
                     * is expected and must never stop the reader.
                     */
                    val historyAccepted = chapterId.isNotBlank() &&
                        historyServerAcceptedChapterIds.contains(chapterId)

                    if (historyAccepted) {
                        log(account.username, "READER: CHAPTER_HISTORY_SERVER_ACCEPTED chapterId=$chapterId source=ADD_HISTORY_2XX")
                    } else {
                        log(account.username, "READER: CHAPTER_HISTORY_CONFIRMED_LOCAL chapterId=$chapterId source=$confirmation questBefore=$questBefore")
                    }

                    if (isRealLastChapter) {
                        log(account.username, "READER: LAST_CHAPTER_REACHED historyAccepted=$historyAccepted")
                        pendingMangaMarkAsRead = true
                        val chapterCanonical = ensureCanonicalMangaUrl(chapterUrl)
                        val slug = chapterCanonical.substringAfter("/manga/").substringBefore("/")

                        currentMangaUrl = if (slug.isNotBlank()) {
                            "https://mangabuff.ru/manga/$slug"
                        } else {
                            currentMangaUrl
                        }

                        log(account.username, "READER: OPEN_MANGA_INFO_FOR_READ_MARK url=$currentMangaUrl")
                        webView.loadUrl(currentMangaUrl)
                    } else {
                        if (nextChapterUrl.isBlank()) {
                            safeResume(ReaderResult.Failed("NEXT_CHAPTER_UNKNOWN"))
                        } else {
                            log(account.username, "READER: OPEN_NEXT_CHAPTER url=$nextChapterUrl serverQuestGate=DEFERRED_CCL_ACCOUNTING")
                            safeResume(ReaderResult.ChapterRead(giftsFound, chapterUrl, chapterId, nextChapterUrl))
                        }
                    }
                }
            }

            try { webView.removeJavascriptInterface("AndroidReaderBridge") } catch (_: Exception) {}
            webView.addJavascriptInterface(ReaderBridge(), "AndroidReaderBridge")

            webView.webViewClient = object : WebViewClient() {

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val urlStr = request?.url?.toString() ?: ""
                    if (urlStr.contains("/addHistory")) {
                        val method = request?.method ?: "POST"
                        log(account.username, "READER: MB_HISTORY_SHOULD_INTERCEPT url=$urlStr method=$method")
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    log(account.username, "PAGE: PAGE_STARTED url=$url")
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    val currentUrl = url ?: ""
                    log(account.username, "PAGE: PAGE_FINISHED url=$currentUrl")

                    val chapterInfo = isReaderChapterUrl(currentUrl)

                    if (chapterInfo.first) {
                        val clean = currentUrl.substringBefore('?').substringBefore('#')
                        val parts = clean
                            .removePrefix("https://")
                            .removePrefix("http://")
                            .removePrefix("mangabuff.ru")
                            .split("/")
                            .filter { it.isNotEmpty() }

                        val mangaSlug = if (parts.size >= 2) parts[1] else ""
                        val volumeNum = if (parts.size >= 3) parts[2] else "1"
                        val chapterNum = if (parts.size >= 4) parts[3] else "1"
                        val mangaUrl = if (mangaSlug.isNotBlank()) "https://mangabuff.ru/manga/$mangaSlug" else currentMangaUrl
                        // Capture the authoritative server quest baseline BEFORE this chapter
                        // can finish. Do not derive the baseline after completion from the
                        // mutable global lastKnownReadQuest.
                        val chapterReadQuestBefore = lastKnownReadQuest

                        currentMangaUrl = mangaUrl
                        lastFinishedChapterUrl = currentUrl
                        lastFinishedChapterNumber = chapterNum

                        activeChapterContext = ChapterContext(
                            accountId = account.id,
                            runId = UUID.randomUUID().toString(),
                            taskRunId = UUID.randomUUID().toString(),
                            chapterRunId = UUID.randomUUID().toString(),
                            documentId = UUID.randomUUID().toString(),
                            chapterNumber = chapterNum,
                            volume = volumeNum,
                            mangaSlug = mangaSlug,
                            mangaTitle = cleanMangaTitle(lastFinishedMangaTitle),
                            mangaUrl = mangaUrl,
                            actualChapterUrl = currentUrl,
                            readQuestBefore = chapterReadQuestBefore
                        )

                        log(account.username, "READER: CHAPTER_PAGE_READY")
                        log(account.username, "READER: CURRENT_CHAPTER num=$chapterNum url=$currentUrl")
                        log(account.username, "CHAPTER_CONTEXT_SAVED slug=$mangaSlug num=$chapterNum url=$currentUrl")

                        val chId = lastFinishedChapterId
                        val known = chId.isNotBlank() && (chId in completedChapterIds || currentUrl in readChapterUrlsInRun)
                        log(account.username, "[READQUEST_DIAG] CHAPTER_STATE chapterId=$chId known=$known")
                        log(account.username, "[READQUEST_DIAG] NEXT_CHAPTER chapterId=$chId currentQuest=$lastKnownReadQuest")

                        val script = """
                            (function() {
                                try {
                                    (function installNetworkInterceptor() {
                                        if (window.__mbNetworkInterceptorInstalled) return;
                                        window.__mbNetworkInterceptorInstalled = true;

                                        function extractItemsFromPayload(data) {
                                            var items = [];
                                            try {
                                                if (!data) return items;
                                                var pairs = [];
                                                if (typeof data === 'string') {
                                                    pairs = data.split('&');
                                                } else if (window.URLSearchParams && data instanceof URLSearchParams) {
                                                    data.forEach(function(val, key) {
                                                        pairs.push(encodeURIComponent(key) + '=' + encodeURIComponent(val));
                                                    });
                                                } else if (typeof data === 'object') {
                                                    if (Array.isArray(data.items)) return data.items;
                                                }

                                                var itemMap = {};
                                                pairs.forEach(function(pair) {
                                                    var parts = pair.split('=');
                                                    if (parts.length < 2) return;
                                                    var key = decodeURIComponent(parts[0]);
                                                    var val = decodeURIComponent(parts[1] || '');

                                                    var match = key.match(/items\[(\d+)\]\[(manga_id|chapter_id)\]/);
                                                    if (match) {
                                                        var idx = match[1];
                                                        var field = match[2];
                                                        if (!itemMap[idx]) itemMap[idx] = {};
                                                        itemMap[idx][field] = val;
                                                    }
                                                });

                                                Object.keys(itemMap).sort(function(a,b){ return Number(a)-Number(b); }).forEach(function(k) {
                                                    items.push(itemMap[k]);
                                                });
                                            } catch(e) {}
                                            return items;
                                        }

                                        function processAddHistoryRequest(url, method, bodyData) {
                                            var currChId = (typeof resolveCurrentChapterId === 'function') ? resolveCurrentChapterId() : (window.__mbResolvedChapterId ? String(window.__mbResolvedChapterId) : '');
                                            window.__mbHistoryPostStarted = true;
                                            window.__mbHistoryPostStatus = 0;
                                            try {
                                                var poolBefore = [];
                                                try { poolBefore = JSON.parse(localStorage.getItem('history_pool') || '[]'); } catch(e) {}
                                                AndroidReaderBridge.onHistoryPoolBefore(poolBefore.length);
                                                poolBefore.forEach(function(item) {
                                                    if (item && item.chapter_id) {
                                                        AndroidReaderBridge.onHistoryPoolItem(String(item.chapter_id));
                                                    }
                                                });

                                                AndroidReaderBridge.onHistoryPostStarted(url, currChId);
                                                var items = extractItemsFromPayload(bodyData);
                                                if (items && items.length > 0) {
                                                    AndroidReaderBridge.onHistoryPostItems(items.length);
                                                    items.forEach(function(item, idx) {
                                                        var mId = item.manga_id || item.mangaId || '';
                                                        var cId = item.chapter_id || item.chapterId || '';
                                                        AndroidReaderBridge.onHistoryPostItem(idx, String(mId), String(cId));
                                                    });
                                                }
                                            } catch(e) {}
                                        }

                                        var origOpen = XMLHttpRequest.prototype.open;
                                        var origSend = XMLHttpRequest.prototype.send;

                                        XMLHttpRequest.prototype.open = function(method, url) {
                                            this.__mbMethod = method;
                                            this.__mbUrl = url;
                                            return origOpen.apply(this, arguments);
                                        };

                                        XMLHttpRequest.prototype.send = function(body) {
                                            var self = this;
                                            var urlStr = String(self.__mbUrl || '');

                                            if (urlStr.indexOf('/addHistory') !== -1) {
                                                processAddHistoryRequest(urlStr, self.__mbMethod || 'POST', body);

                                                self.addEventListener('readystatechange', function() {
                                                    if (self.readyState === 4) {
                                                        try {
                                                            var status = self.status;
                                                            var responseText = self.responseText || '';
                                                            window.__mbHistoryPostStatus = status;
                                                            var poolAfter = [];
                                                            try { poolAfter = JSON.parse(localStorage.getItem('history_pool') || '[]'); } catch(e) {}
                                                            AndroidReaderBridge.onHistoryPoolAfter(poolAfter.length);
                                                            AndroidReaderBridge.onHistoryPostStatus(status);
                                                            AndroidReaderBridge.onHistoryPostResponse(responseText);
                                                            AndroidReaderBridge.onHistoryPostFinished(status, urlStr);
                                                        } catch(e) {}
                                                    }
                                                });
                                            }

                                            return origSend.apply(this, arguments);
                                        };

                                        if (window.fetch) {
                                            var origFetch = window.fetch;

                                            window.fetch = function(resource, init) {
                                                var urlStr = (typeof resource === 'string')
                                                    ? resource
                                                    : (resource && resource.url ? resource.url : '');

                                                var methodStr = (init && init.method)
                                                    ? init.method
                                                    : 'GET';

                                                if (urlStr.indexOf('/addHistory') !== -1) {
                                                    var bodyData = init ? init.body : null;

                                                    processAddHistoryRequest(
                                                        urlStr,
                                                        methodStr,
                                                        bodyData
                                                    );

                                                    return origFetch.apply(this, arguments)
                                                        .then(function(response) {
                                                            var clone = response.clone();
                                                            var status = response.status;

                                                            window.__mbHistoryPostStatus = status;

                                                            var poolAfter = [];

                                                            try {
                                                                poolAfter = JSON.parse(
                                                                    localStorage.getItem('history_pool') || '[]'
                                                                );
                                                            } catch(e) {}

                                                            AndroidReaderBridge.onHistoryPoolAfter(
                                                                poolAfter.length
                                                            );

                                                            AndroidReaderBridge.onHistoryPostStatus(
                                                                status
                                                            );

                                                            clone.text()
                                                                .then(function(text) {
                                                                    AndroidReaderBridge.onHistoryPostResponse(
                                                                        text || ''
                                                                    );

                                                                    AndroidReaderBridge.onHistoryPostFinished(
                                                                        status,
                                                                        urlStr
                                                                    );
                                                                })
                                                                .catch(function() {
                                                                    AndroidReaderBridge.onHistoryPostFinished(
                                                                        status,
                                                                        urlStr
                                                                    );
                                                                });

                                                            return response;

                                                        })
                                                        .catch(function(err) {
                                                            window.__mbHistoryPostStatus = 0;

                                                            var poolAfter = [];

                                                            try {
                                                                poolAfter = JSON.parse(
                                                                    localStorage.getItem('history_pool') || '[]'
                                                                );
                                                            } catch(e) {}

                                                            AndroidReaderBridge.onHistoryPoolAfter(
                                                                poolAfter.length
                                                            );

                                                            AndroidReaderBridge.onHistoryPostStatus(0);

                                                            AndroidReaderBridge.onHistoryPostFinished(
                                                                0,
                                                                urlStr
                                                            );

                                                            throw err;
                                                        });
                                                }

                                                return origFetch.apply(this, arguments);
                                            };
                                        }
                                    })();


                                    function resolveCurrentChapterId() {
                                        try {
                                            var chapter = window.current_chapter || null;
                                            var chapterId = chapter && chapter.chapter_id
                                                ? String(chapter.chapter_id).trim()
                                                : '';
                                            var id = chapter && chapter.id
                                                ? String(chapter.id).trim()
                                                : '';
                                            var cached = window.__mbResolvedChapterId
                                                ? String(window.__mbResolvedChapterId).trim()
                                                : '';
                                            var resolved = chapterId || id || cached;
                                            if (resolved) {
                                                window.__mbResolvedChapterId = resolved;
                                            }
                                            return resolved;
                                        } catch (e) {
                                            return '';
                                        }
                                    }

                                    function logChapterIdResolve(source) {
                                        try {
                                            var chapter = window.current_chapter || null;
                                            var chapterId = chapter && chapter.chapter_id ? String(chapter.chapter_id) : '';
                                            var id = chapter && chapter.id ? String(chapter.id) : '';
                                            var resolved = resolveCurrentChapterId();
                                            AndroidReaderBridge.onLogStep(
                                                'READER: CHAPTER_ID_RESOLVE source=' + (source || '') +
                                                ' chapter_id=' + chapterId +
                                                ' id=' + id +
                                                ' resolved=' + resolved
                                            );
                                            return resolved;
                                        } catch (e) {
                                            return '';
                                        }
                                    }

                                    var startMs = Date.now();
                                    var chapterDone = false;
                                    var bottomStarted = 0;
                                    var lastProgress = -1;
                                    var unknownFinalStart = 0;
                                    var lastObservedScrollY = -1;
                                    var stagnantChecks = 0;
                                    var completionScheduled = false;
                                    var swipeRecoveryAttempts = 0;

                                    window.__mbHistoryPostStarted = false;
                                    window.__mbHistoryPostStatus = null;

                                    var chapterId = logChapterIdResolve('START');
                                    AndroidReaderBridge.onLogStep('READER: CHAPTER_ID=' + chapterId);
                                    AndroidReaderBridge.onLogStep('READER: CHAPTER_TIMER_STARTED chapterId=' + chapterId);

                                    // MangaBuff can populate window.current_chapter after the
                                    // initial page script. Do not permanently freeze an empty ID.
                                    (function resolveChapterIdLate() {
                                        var attempts = 0;
                                        var maxAttempts = 15;
                                        function poll() {
                                            attempts++;
                                            var resolved = logChapterIdResolve('POLL_' + attempts);
                                            if (resolved) {
                                                chapterId = resolved;
                                                AndroidReaderBridge.onLogStep(
                                                    'READER: CHAPTER_ID_LATE_RESOLVED id=' + chapterId +
                                                    ' attempts=' + attempts
                                                );
                                                return;
                                            }
                                            if (attempts < maxAttempts) {
                                                setTimeout(poll, 300);
                                            } else {
                                                AndroidReaderBridge.onLogStep(
                                                    'READER: CHAPTER_ID_RESOLVE_TIMEOUT attempts=' + attempts
                                                );
                                            }
                                        }
                                        if (!chapterId) setTimeout(poll, 100);
                                    })();
                                    try {
                                        var diagScrollingElement = document.scrollingElement || document.documentElement || document.body;
                                        AndroidReaderBridge.onLogStep(
                                            'READER: VIEWPORT_DIAG width=' + (window.innerWidth || 0) +
                                            ' height=' + (window.innerHeight || 0) +
                                            ' clientHeight=' + (diagScrollingElement ? (diagScrollingElement.clientHeight || 0) : 0) +
                                            ' scrollHeight=' + (diagScrollingElement ? (diagScrollingElement.scrollHeight || 0) : 0)
                                        );
                                    } catch(e) {}
                                    AndroidReaderBridge.onLogStep('READER: SCROLL_MODE=NATIVE_FINGER_SWIPE');

                                    if (window.current_chapter) {
                                        var c = window.current_chapter;
                                        var resolvedChapterId = resolveCurrentChapterId();
                                        AndroidReaderBridge.onCurrentChapterData(
                                            String(c.manga_id || c.mangaId || c.id || ''),
                                            resolvedChapterId,
                                            String(c.name || ''),
                                            String(c.slug || ''),
                                            String(c.volume || '1'),
                                            String(c.chapter || '$chapterNum')
                                        );
                                    }

                                    function visible(el) {
                                        if (!el) return false;
                                        var s = getComputedStyle(el);
                                        return (s.display !== 'none' && s.visibility !== 'hidden' && (el.offsetWidth > 0 || el.offsetHeight > 0));
                                    }

                                    function text(el) {
                                        return (el && (el.innerText || el.textContent || '')).replace(/\s+/g, ' ').trim();
                                    }

                                    function isNextText(value) {
                                        var t = (value || '').replace(/\s+/g, ' ').trim().toLowerCase();
                                        return /^след\.\s*глава\b/.test(t) || /^следующая\s+глава\b/.test(t) || /^глава\s+\d+/.test(t);
                                    }

                                    function getHref(el) {
                                        if (!el) return '';
                                        var raw = el.getAttribute ? (el.getAttribute('href') || '') : '';
                                        if (raw) {
                                            try { return new URL(raw, window.location.href).href; } catch(e) {}
                                        }
                                        if (el.href && typeof el.href === 'string') return el.href;
                                        return '';
                                    }

                                    function isValidNextUrl(value) {
                                        if (!value) return false;
                                        try {
                                            var current = new URL(window.location.href);
                                            var next = new URL(value, window.location.href);
                                            if (current.origin !== next.origin) return false;

                                            var c = current.pathname.split('/').filter(Boolean);
                                            var n = next.pathname.split('/').filter(Boolean);

                                            if (n.length !== 4 || n[0] !== 'manga') return false;
                                            if (!/^\d+$/.test(n[2]) || !/^\d+$/.test(n[3])) return false;

                                            if (c.length < 4 || c[0] !== 'manga' || c[1] !== n[1]) return false;

                                            var currentNum = parseInt(c[3], 10);
                                            var nextNum = parseInt(n[3], 10);
                                            if (isNaN(currentNum) || isNaN(nextNum) || nextNum <= currentNum) return false;

                                            return true;
                                        } catch(e) {
                                            return false;
                                        }
                                    }

                                    function findNextChapter() {
                                        var direct = Array.from(document.querySelectorAll('a, button, [role="button"], [role="link"]'));
                                        for (var i = 0; i < direct.length; i++) {
                                            var el = direct[i];
                                            if (!visible(el)) continue;
                                            if (el.classList && el.classList.contains('notify-new-chapter')) continue;

                                            var ownText = text(el);
                                            if (isNextText(ownText)) {
                                                var href = getHref(el);
                                                if (isValidNextUrl(href)) {
                                                    AndroidReaderBridge.onLogStep('NEXT_CHAPTER_FOUND_DOM tag=' + el.tagName + ' text="' + ownText + '" url=' + href);
                                                    return el;
                                                }
                                            }
                                        }

                                        var explicit = Array.from(document.querySelectorAll('.reader-header__nav-btn--next, .reader__next, [data-next-chapter]'));
                                        for (var j = 0; j < explicit.length; j++) {
                                            var ex = explicit[j];
                                            if (!visible(ex)) continue;
                                            if (ex.classList && ex.classList.contains('notify-new-chapter')) continue;

                                            var exHref = getHref(ex);
                                            if (isValidNextUrl(exHref)) {
                                                AndroidReaderBridge.onLogStep('NEXT_CHAPTER_FOUND_DOM tag=' + ex.tagName + ' text="' + text(ex) + '" url=' + exHref);
                                                return ex;
                                            }
                                        }

                                        var generic = Array.from(document.querySelectorAll('div, span'))
                                            .filter(function(el) {
                                                if (!visible(el)) return false;
                                                if (el.classList && el.classList.contains('notify-new-chapter')) return false;
                                                var t = text(el);
                                                return isNextText(t) && t.length <= 120;
                                            })
                                            .sort(function(a, b) { return text(a).length - text(b).length; });

                                        for (var k = 0; k < generic.length; k++) {
                                            var g = generic[k];
                                            var parentA = g.closest ? g.closest('a, button, [role="button"], [role="link"]') : null;
                                            if (parentA && parentA !== g) {
                                                var parentHref = getHref(parentA);
                                                if (isValidNextUrl(parentHref)) {
                                                    AndroidReaderBridge.onLogStep('NEXT_CHAPTER_FOUND_DOM tag=' + parentA.tagName + ' text="' + text(parentA) + '" url=' + parentHref);
                                                    return parentA;
                                                } else {
                                                    AndroidReaderBridge.onLogStep('NEXT_CHAPTER_WRAPPER_REJECTED parentText="' + text(parentA) + '" href="' + parentHref + '"');
                                                }
                                            }
                                        }

                                        return null;
                                    }

                                    function buildCandidateNextUrl() {
                                        try {
                                            var loc = new URL(window.location.href);
                                            var parts = loc.pathname.split('/').filter(Boolean);
                                            if (parts.length >= 4 && parts[0] === 'manga') {
                                                var slug = parts[1];
                                                var vol = parts[2];
                                                var chNum = parseInt(parts[3], 10);
                                                if (!isNaN(chNum)) {
                                                    var nextChNum = chNum + 1;
                                                    return loc.origin + '/manga/' + slug + '/' + vol + '/' + nextChNum;
                                                }
                                            }
                                        } catch(e) {}
                                        return '';
                                    }

                                    function isLastChapter() {
                                        var notify = document.querySelector('.notify-new-chapter');
                                        if (!notify || !visible(notify)) return false;

                                        var id = (notify.getAttribute('data-id') || '').trim();
                                        if (!id) return true;

                                        var known = [];
                                        var fav = document.querySelector('.manga__favourite-btn[data-id], .favourite-send-btn[data-id]');
                                        if (fav) known.push(String(fav.getAttribute('data-id')));
                                        if (window.manga_id) known.push(String(window.manga_id));
                                        if (window.current_manga && window.current_manga.id) known.push(String(window.current_manga.id));

                                        if (known.length === 0) return true;
                                        return known.indexOf(id) !== -1;
                                    }

                                    function stopScroll() {
                                        if (window.__mbScrollTimer) {
                                            clearTimeout(window.__mbScrollTimer);
                                            window.__mbScrollTimer = null;
                                        }

                                        // Stop the native phone-like swipe scheduler as well.
                                        try {
                                            window.__mbNativeFingerRunning = false;
                                            window.__mbNativeFingerBusy = false;
                                            if (window.__mbNativeFingerTimer) {
                                                clearTimeout(window.__mbNativeFingerTimer);
                                                window.__mbNativeFingerTimer = null;
                                            }
                                        } catch(e) {}

                                        // Always stop our custom requestAnimationFrame loop first.
                                        // Without this flag reset, a finished chapter could leave the
                                        // previous reader loop alive until chapterDone changed state.
                                        try {
                                            window.__mbFastScrollRunning = false;
                                            if (window.__mbFastScrollFrame) {
                                                cancelAnimationFrame(window.__mbFastScrollFrame);
                                                window.__mbFastScrollFrame = null;
                                            }
                                        } catch(e) {}

                                        // Stop MangaBuff's own autoscroll if it is running.
                                        try {
                                            var pause = document.querySelector('.reader-autoscroll-icon-pause');
                                            if (pause && visible(pause)) {
                                                pause.click();
                                                AndroidReaderBridge.onLogStep('READER: MANGABUFF_AUTOSCROLL_STOP_CLICK');
                                                return;
                                            }

                                            var play = document.querySelector('.reader-autoscroll-icon-play');
                                            if (play && visible(play) && play.closest('.reader-autoscroll')) {
                                                // Already stopped.
                                                return;
                                            }

                                            var autoIcon = document.querySelector(
                                                '.reader-autoscroll-icon-pause, .reader-autoscroll-icon-play'
                                            );
                                            if (autoIcon && autoIcon.parentElement) {
                                                var cls = String(autoIcon.className || '');
                                                if (cls.indexOf('icon-pause') !== -1) {
                                                    autoIcon.parentElement.click();
                                                    AndroidReaderBridge.onLogStep('READER: MANGABUFF_AUTOSCROLL_STOP_PARENT_CLICK');
                                                }
                                            }
                                        } catch(e) {
                                            AndroidReaderBridge.onLogStep(
                                                'READER: MANGABUFF_AUTOSCROLL_STOP_ERROR ' +
                                                (e.message || String(e))
                                            );
                                        }
                                    }

                                    function findSettingsButton() {
                                        var icon = document.querySelector('.icon.icon-settings');
                                        if (!icon) return null;

                                        var button = icon.closest(
                                            'button, a, [role="button"], .reader-menu__item'
                                        );
                                        return button || icon;
                                    }

                                    function findSettingsPopup() {
                                        var candidates = Array.from(document.querySelectorAll(
                                            '.popup, .reader-settings, .reader-settings__section'
                                        ));

                                        for (var i = 0; i < candidates.length; i++) {
                                            var el = candidates[i];
                                            if (visible(el) && (
                                                el.querySelector('[data-reader-setting="speed"]') ||
                                                el.querySelector('[data-reader-autoscroll-enabled]')
                                            )) {
                                                return el;
                                            }
                                        }

                                        return null;
                                    }

                                    function configureMangaBuffAutoscroll(done) {
                                        var startedAt = Date.now();
                                        var maxWait = 12000;
                                        var settingsOpened = false;

                                        function finishSetup(ok, reason) {
                                            if (ok) {
                                                AndroidReaderBridge.onLogStep(
                                                    'READER: MANGABUFF_AUTOSCROLL_CONFIGURED speed=900 enabled=true'
                                                );
                                            } else {
                                                AndroidReaderBridge.onLogStep(
                                                    'READER: MANGABUFF_AUTOSCROLL_CONFIG_FAILED reason=' + reason
                                                );
                                            }
                                            done(ok);
                                        }

                                        function configure() {
                                            if (chapterDone) return;

                                            var popup = findSettingsPopup();
                                            if (!popup) {
                                                if (!settingsOpened) {
                                                    var settingsButton = findSettingsButton();
                                                    if (!settingsButton) {
                                                        if (Date.now() - startedAt >= maxWait) {
                                                            finishSetup(false, 'SETTINGS_BUTTON_NOT_FOUND');
                                                            return;
                                                        }
                                                        setTimeout(configure, 250);
                                                        return;
                                                    }

                                                    settingsOpened = true;
                                                    AndroidReaderBridge.onLogStep('READER: MANGABUFF_SETTINGS_OPEN_CLICK');
                                                    try {
                                                        settingsButton.click();
                                                    } catch(e) {}

                                                    setTimeout(configure, 300);
                                                    return;
                                                }

                                                if (Date.now() - startedAt >= maxWait) {
                                                    finishSetup(false, 'SETTINGS_POPUP_NOT_FOUND');
                                                    return;
                                                }

                                                setTimeout(configure, 250);
                                                return;
                                            }

                                            var speed = popup.querySelector('[data-reader-setting="speed"]');
                                            if (!speed) {
                                                finishSetup(false, 'SPEED_RANGE_NOT_FOUND');
                                                return;
                                            }

                                            try {
                                                speed.value = '900';
                                                speed.dispatchEvent(new Event('input', { bubbles: true }));
                                                speed.dispatchEvent(new Event('change', { bubbles: true }));
                                            } catch(e) {
                                                finishSetup(false, 'SPEED_SET_EXCEPTION_' + (e.message || String(e)));
                                                return;
                                            }

                                            var valueLabel = popup.querySelector('[data-reader-value="speed"]');
                                            if (valueLabel) valueLabel.textContent = '900';

                                            var toggle = popup.querySelector('[data-reader-autoscroll-enabled]');
                                            if (!toggle) {
                                                finishSetup(false, 'AUTOSCROLL_TOGGLE_NOT_FOUND');
                                                return;
                                            }

                                            var toggleActive =
                                                toggle.classList.contains('is-active') ||
                                                toggle.getAttribute('aria-pressed') === 'true' ||
                                                toggle.getAttribute('aria-checked') === 'true';

                                            if (!toggleActive) {
                                                AndroidReaderBridge.onLogStep('READER: MANGABUFF_AUTOSCROLL_ENABLE_CLICK');
                                                try {
                                                    toggle.click();
                                                } catch(e) {
                                                    finishSetup(false, 'AUTOSCROLL_TOGGLE_CLICK_EXCEPTION');
                                                    return;
                                                }
                                            } else {
                                                AndroidReaderBridge.onLogStep('READER: MANGABUFF_AUTOSCROLL_ALREADY_ENABLED');
                                            }

                                            setTimeout(function() {
                                                var close = popup.querySelector('.popup__close') ||
                                                    document.querySelector('.popup__close');

                                                if (close && visible(close)) {
                                                    AndroidReaderBridge.onLogStep('READER: MANGABUFF_SETTINGS_CLOSE_CLICK');
                                                    try { close.click(); } catch(e) {}
                                                } else {
                                                    AndroidReaderBridge.onLogStep('READER: MANGABUFF_SETTINGS_CLOSE_NOT_FOUND');
                                                }

                                                setTimeout(function() {
                                                    var play = document.querySelector('.reader-autoscroll-icon-play');
                                                    if (!play || !visible(play)) {
                                                        finishSetup(false, 'AUTOSCROLL_PLAY_NOT_FOUND');
                                                        return;
                                                    }

                                                    AndroidReaderBridge.onLogStep('READER: MANGABUFF_AUTOSCROLL_PLAY_CLICK');
                                                    try {
                                                        play.click();
                                                    } catch(e) {
                                                        finishSetup(false, 'AUTOSCROLL_PLAY_CLICK_EXCEPTION');
                                                        return;
                                                    }

                                                    setTimeout(function() {
                                                        var pause = document.querySelector('.reader-autoscroll-icon-pause');
                                                        if (pause && visible(pause)) {
                                                            AndroidReaderBridge.onLogStep(
                                                                'READER: MANGABUFF_AUTOSCROLL_STARTED speed=900'
                                                            );
                                                            finishSetup(true, '');
                                                        } else {
                                                            // Some implementations keep the play icon while
                                                            // the scrolling loop is already running. Verify by
                                                            // observing scroll movement before declaring failure.
                                                            var beforeY = window.scrollY || window.pageYOffset || 0;
                                                            setTimeout(function() {
                                                                var afterY = window.scrollY || window.pageYOffset || 0;
                                                                if (afterY > beforeY + 2) {
                                                                    AndroidReaderBridge.onLogStep(
                                                                        'READER: MANGABUFF_AUTOSCROLL_STARTED_BY_SCROLL speed=900'
                                                                    );
                                                                    finishSetup(true, '');
                                                                } else {
                                                                    finishSetup(false, 'AUTOSCROLL_DID_NOT_START');
                                                                }
                                                            }, 1000);
                                                        }
                                                    }, 300);
                                                }, 350);
                                            }, 350);
                                        }

                                        configure();
                                    }

                                    function logPreNextChapterState() {
                                        try {
                                            var pool = [];
                                            try { pool = JSON.parse(localStorage.getItem('history_pool') || '[]'); } catch(e) {}

                                            var currentChId = resolveCurrentChapterId() || chapterId;

                                            AndroidReaderBridge.onPreNextChapterState(
                                                window.is_read === true,
                                                window.read_status_send === true,
                                                Number(window.ccl || 0),
                                                pool.length,
                                                currentChId,
                                                JSON.stringify(pool)
                                            );
                                        } catch(e) {}
                                    }

                                    function finish(nextElement, isLast, confirmationSource) {
                                        stopScroll();
                                        var elapsed = Date.now() - startMs;
                                        var titleElement = document.querySelector('.reader__controls-name, h1');
                                        var title = titleElement ? text(titleElement) : document.title;
                                        var nextUrl = '';

                                        if (nextElement && !isLast) {
                                            var raw = nextElement.getAttribute ? (nextElement.getAttribute('href') || '') : '';
                                            var href = getHref(nextElement);
                                            AndroidReaderBridge.onNextChapterLinkAudit(text(nextElement), raw, href);

                                            if (isValidNextUrl(href)) {
                                                AndroidReaderBridge.onLogStep('READER: NEXT_CHAPTER_REAL_URL_FOUND url=' + href);
                                                nextUrl = href;
                                            } else {
                                                // The native side refreshes /balance before opening the next chapter.
                                                // Therefore a DOM-only target cannot be deferred across that navigation:
                                                // the chapter DOM is gone after /balance. Prefer a deterministic real URL.
                                                var fallbackUrl = buildCandidateNextUrl();
                                                if (fallbackUrl && isValidNextUrl(fallbackUrl)) {
                                                    AndroidReaderBridge.onLogStep(
                                                        'READER: NEXT_CHAPTER_DOM_NO_HREF_USING_FALLBACK url=' + fallbackUrl
                                                    );
                                                    nextUrl = fallbackUrl;
                                                } else {
                                                    AndroidReaderBridge.onLogStep('READER: NEXT_CHAPTER_DOM_TARGET_UNUSABLE');
                                                }
                                            }
                                        }

                                        AndroidReaderBridge.onLogStep('READER: SCROLL_STOPPED');
                                        AndroidReaderBridge.onLogStep('READER: READER_COMPLETION_DETECTED chapterId=' + chapterId);
                                        AndroidReaderBridge.onLogStep('READER: CHAPTER_COMPLETED chapterId=' + chapterId);

                                        logPreNextChapterState();

                                        AndroidReaderBridge.onChapterFinishedWithElapsed(
                                            window.location.href,
                                            '$chapterNum',
                                            chapterId,
                                            title,
                                            elapsed,
                                            nextUrl,
                                            isLast,
                                            confirmationSource || 'UNKNOWN'
                                        );
                                    }

                                    function finishWithCandidateUrl(candidateUrl, confirmationSource) {
                                        stopScroll();
                                        var elapsed = Date.now() - startMs;
                                        var titleElement = document.querySelector('.reader__controls-name, h1');
                                        var title = titleElement ? text(titleElement) : document.title;

                                        AndroidReaderBridge.onLogStep('READER: SCROLL_STOPPED');
                                        AndroidReaderBridge.onLogStep('READER: CHAPTER_COMPLETED chapterId=' + chapterId);

                                        logPreNextChapterState();

                                        AndroidReaderBridge.onChapterFinishedWithElapsed(
                                            window.location.href,
                                            '$chapterNum',
                                            chapterId,
                                            title,
                                            elapsed,
                                            candidateUrl,
                                            false,
                                            confirmationSource || 'UNKNOWN'
                                        );
                                    }

                                    function checkMangaBuffReadState() {
                                        var pool = [];
                                        try {
                                            pool = JSON.parse(localStorage.getItem('history_pool') || '[]');
                                        } catch (e) {}

                                        var currentChId = resolveCurrentChapterId() || chapterId;

                                        var isRead = (window.is_read === true);
                                        var readSend = (window.read_status_send === true);
                                        var cclVal = Number(window.ccl || 0);

                                        var containsCurrent = pool.some(function(item) {
                                            return item && (String(item.chapter_id) === currentChId || String(item.chapter_id) === chapterId);
                                        });

                                        var postStarted = (window.__mbHistoryPostStarted === true);
                                        var postStatus = (typeof window.__mbHistoryPostStatus === 'number') ? window.__mbHistoryPostStatus : null;

                                        var scrollTop = window.scrollY || window.pageYOffset || 0;
                                        var docHeight = Math.max(
                                            document.body ? document.body.scrollHeight : 0,
                                            document.documentElement ? document.documentElement.scrollHeight : 0
                                        );
                                        var viewHeight = window.innerHeight || 0;
                                        var threshold = Math.max(0, (docHeight - viewHeight) / 2);

                                        var confirmed = false;
                                        var confirmSource = '';
                                        var waitReason = '';
                                        var postOk = postStatus !== null && postStatus >= 200 && postStatus < 300;

                                        // Leave the reader once MangaBuff itself reports the current chapter
                                        // as read and places it in history. The authoritative reward check
                                        // is the forced /balance refresh on the native side.
                                        if (!isRead) {
                                            waitReason = 'PAGE_NOT_READ';
                                        } else if (readSend && containsCurrent) {
                                            confirmed = true;
                                            confirmSource = postOk
                                                ? 'ADD_HISTORY_2XX'
                                                : (postStarted ? 'LOCAL_HISTORY_AFTER_POST' : 'LOCAL_HISTORY_STATE');
                                            if (!postStarted) {
                                                waitReason = 'ADDHISTORY_NOT_INTERCEPTED_BALANCE_WILL_VERIFY';
                                            }
                                        } else if (!postStarted) {
                                            waitReason = readSend
                                                ? 'WAITING_FOR_HISTORY_POOL_CURRENT'
                                                : 'READ_STATUS_NOT_READY';
                                        } else if (postOk) {
                                            confirmed = true;
                                            confirmSource = 'ADD_HISTORY_2XX';
                                        } else if (postStatus !== null && postStatus !== 0) {
                                            waitReason = 'POST_STATUS_' + postStatus;
                                        } else {
                                            waitReason = 'POST_PENDING';
                                        }

                                        return {
                                            isRead: isRead,
                                            readStatusSend: readSend,
                                            ccl: cclVal,
                                            historyPoolSize: pool.length,
                                            containsCurrent: containsCurrent,
                                            postStarted: postStarted,
                                            postStatus: postStatus,
                                            scrollTop: scrollTop,
                                            docHeight: docHeight,
                                            viewHeight: viewHeight,
                                            threshold: threshold,
                                            chapterId: currentChId,
                                            confirmed: confirmed,
                                            confirmSource: confirmSource,
                                            waitReason: waitReason
                                        };
                                    }

                                    function triggerMangaBuffHistoryIfReady(reason) {
                                        try {
                                            var chapter = window.current_chapter || null;
                                            var currentId = resolveCurrentChapterId();
                                            var mangaId = chapter && chapter.id ? String(chapter.id) : '';
                                            var isRead = (window.is_read === true);
                                            var readSend = (window.read_status_send === true);
                                            var pool = [];
                                            try {
                                                pool = JSON.parse(localStorage.getItem('history_pool') || '[]');
                                            } catch(e) {
                                                pool = [];
                                            }

                                            var alreadyInPool = pool.some(function(item) {
                                                return item &&
                                                    String(item.manga_id) === mangaId &&
                                                    String(item.chapter_id) === currentId;
                                            });

                                            AndroidReaderBridge.onLogStep(
                                                'READER: HISTORY_TRIGGER_CHECK reason=' + reason +
                                                ' isRead=' + isRead +
                                                ' readStatusSend=' + readSend +
                                                ' poolSize=' + pool.length +
                                                ' currentInPool=' + alreadyInPool +
                                                ' ccl=' + Number(window.ccl || 0)
                                            );

                                            // This is the site's own mechanism:
                                            // addHistory() -> pushChapterHistory() -> /addHistory?r=702.
                                            // Never fake the POST and never reset read_status_send just to
                                            // force a request. MangaBuff itself owns the CCL=2 batching.
                                            if (isRead && !readSend && typeof window.addHistory === 'function') {
                                                window.addHistory();
                                                AndroidReaderBridge.onLogStep(
                                                    'READER: HISTORY_TRIGGERED_NATIVE_ADDHISTORY chapterId=' + currentId
                                                );
                                            } else if (readSend) {
                                                AndroidReaderBridge.onLogStep(
                                                    'READER: HISTORY_ALREADY_SENT_OR_QUEUED chapterId=' + currentId
                                                );
                                            } else {
                                                AndroidReaderBridge.onLogStep(
                                                    'READER: HISTORY_NOT_TRIGGERED reason=SITE_FUNCTION_UNAVAILABLE_OR_NOT_READ'
                                                );
                                            }
                                        } catch(e) {
                                            // History reporting is non-fatal. Reader must continue.
                                            AndroidReaderBridge.onLogStep(
                                                'READER: HISTORY_TRIGGER_ERROR_IGNORED error=' + (e.message || String(e))
                                            );
                                        }
                                    }

                                    function logReadState(state) {
                                        AndroidReaderBridge.onLogStep('READER: MB_READ_STATE chapterId=' + state.chapterId);
                                        AndroidReaderBridge.onLogStep('READER: MB_IS_READ=' + state.isRead);
                                        AndroidReaderBridge.onLogStep('READER: MB_READ_STATUS_SEND=' + state.readStatusSend);
                                        AndroidReaderBridge.onLogStep('READER: MB_CCL=' + state.ccl);
                                        AndroidReaderBridge.onLogStep('READER: MB_HISTORY_POOL_SIZE=' + state.historyPoolSize);
                                        AndroidReaderBridge.onLogStep('READER: MB_HISTORY_CONTAINS_CURRENT=' + state.containsCurrent);
                                        if (state.postStarted) {
                                            AndroidReaderBridge.onLogStep('READER: MB_HISTORY_POST_STARTED');
                                            if (state.postStatus !== null) {
                                                AndroidReaderBridge.onLogStep('READER: MB_HISTORY_POST_STATUS=' + state.postStatus);
                                            }
                                        }
                                        AndroidReaderBridge.onLogStep('READER: MB_SCROLL=' + Math.floor(state.scrollTop) + ' threshold=' + Math.floor(state.threshold));

                                        if (state.confirmed) {
                                            AndroidReaderBridge.onLogStep('READER: MB_HISTORY_CONFIRMATION_SUCCESS chapterId=' + state.chapterId + ' source=' + state.confirmSource);
                                        } else {
                                            if (state.postStatus && state.postStatus !== 200) {
                                                AndroidReaderBridge.onLogStep('READER: MB_HISTORY_CONFIRMATION_FAILED chapterId=' + state.chapterId + ' reason=' + state.waitReason);
                                            } else {
                                                AndroidReaderBridge.onLogStep('READER: MB_HISTORY_CONFIRMATION_WAIT chapterId=' + state.chapterId + ' reason=' + state.waitReason);
                                            }
                                        }
                                    }

                                    var confirmAttempt = 0;
                                    var maxConfirmAttempts = 40;
                                    var isWaitingConfirmation = false;

                                    function waitForMangaBuffConfirmation(onSuccess) {
                                        confirmAttempt++;

                                        var state = checkMangaBuffReadState();
                                        logReadState(state);

                                        if (state.confirmed) {
                                            AndroidReaderBridge.onLogStep(
                                                'READER: MB_READ_CONFIRMED chapterId=' + state.chapterId +
                                                ' source=' + state.confirmSource
                                            );
                                            onSuccess(state.confirmSource || 'UNKNOWN');
                                            return;
                                        }

                                        // If MangaBuff already marks the chapter as read but the
                                        // history request is still pending, let the site's own
                                        // addHistory routine run. We never synthesize the request.
                                        if (state.isRead && !state.postStarted && !state.containsCurrent) {
                                            triggerMangaBuffHistoryIfReady('CONFIRMATION_RETRY');
                                        } else if (!state.isRead) {
                                            try {
                                                window.dispatchEvent(new Event('scroll'));
                                            } catch(e) {}
                                        }

                                        if (confirmAttempt >= maxConfirmAttempts) {
                                            AndroidReaderBridge.onLogStep(
                                                'READER: MB_READ_CONFIRM_TIMEOUT chapterId=' + state.chapterId +
                                                ' reason=' + state.waitReason
                                            );
                                            chapterDone = true;
                                            AndroidReaderBridge.onNextChapterUnknown();
                                            return;
                                        }

                                        AndroidReaderBridge.onLogStep(
                                            'READER: MB_READ_WAIT attempt=' + confirmAttempt +
                                            ' chapterId=' + state.chapterId +
                                            ' reason=' + state.waitReason
                                        );
                                        setTimeout(function() {
                                            waitForMangaBuffConfirmation(onSuccess);
                                        }, 350);
                                    }

                                    function checkEnd() {
                                        if (chapterDone) return;
                                        if (window.__mbNativeFingerBusy) return;

                                        var scrollingElement = document.scrollingElement || document.documentElement || document.body;
                                        var rawY = Math.max(
                                            window.scrollY || 0,
                                            window.pageYOffset || 0,
                                            scrollingElement ? (scrollingElement.scrollTop || 0) : 0,
                                            document.documentElement ? (document.documentElement.scrollTop || 0) : 0,
                                            document.body ? (document.body.scrollTop || 0) : 0
                                        );
                                        var rawViewport = Math.max(
                                            window.visualViewport && window.visualViewport.height
                                                ? window.visualViewport.height
                                                : 0,
                                            window.innerHeight || 0,
                                            1
                                        );
                                        var rawHeight = Math.max(
                                            scrollingElement ? (scrollingElement.scrollHeight || 0) : 0,
                                            document.documentElement ? (document.documentElement.scrollHeight || 0) : 0,
                                            document.body ? (document.body.scrollHeight || 0) : 0
                                        );

                                        var metrics = {
                                            y: rawY,
                                            viewport: rawViewport,
                                            height: rawHeight
                                        };

                                        if (lastObservedScrollY >= 0) {
                                            if (Math.abs(metrics.y - lastObservedScrollY) < 2) {
                                                stagnantChecks++;
                                            } else {
                                                stagnantChecks = 0;
                                            }
                                        } else {
                                            stagnantChecks = 0;
                                        }
                                        lastObservedScrollY = metrics.y;

                                        var percent = metrics.height > 0
                                            ? Math.min(100, Math.floor(((metrics.y + metrics.viewport) / metrics.height) * 100))
                                            : 100;

                                        if (percent !== lastProgress && (percent === 25 || percent === 50 || percent === 75 || percent === 90 || percent === 100)) {
                                            lastProgress = percent;
                                            AndroidReaderBridge.onLogStep(
                                                'READER: SCROLL_PROGRESS y=' + Math.floor(metrics.y) +
                                                ' height=' + metrics.height +
                                                ' viewport=' + Math.floor(metrics.viewport) +
                                                ' progress=' + percent +
                                                '% elapsed=' + Math.floor((Date.now() - startMs) / 1000) + 's'
                                            );
                                        }

                                        // Do not poll /balance while scrolling.
                                        // MangaBuff may batch the reading quest counter (CCL),
                                        // so per-page probes only created false ERROR logs and
                                        // were never a valid per-chapter completion signal.
                                        var maxScrollTop = Math.max(0, metrics.height - metrics.viewport);
                                        var distance = Math.max(0, maxScrollTop - metrics.y);
                                        var atAbsoluteBottom = distance <= 8;
                                        var touchStalled = stagnantChecks >= 4;

                                        /*
                                         * IMPORTANT:
                                         * A stalled touch is NOT proof that the chapter is finished.
                                         * WebView/HyperOS can temporarily stop accepting a gesture while
                                         * the page is still thousands of pixels from the real bottom.
                                         *
                                         * Only enter bottom stabilization when we are actually at the
                                         * bottom (or very close to it). If the touch stalls farther away,
                                         * clear the stall counter and let the native swipe loop retry.
                                         */
                                        var nearBottom = distance <= 120;

                                        if (!nearBottom) {
                                            bottomStarted = 0;
                                            unknownFinalStart = 0;

                                            if (touchStalled) {
                                                AndroidReaderBridge.onLogStep(
                                                    'READER: SCROLL_TOUCH_STALLED_RETRY y=' + Math.floor(metrics.y) +
                                                    ' remaining=' + Math.floor(distance) +
                                                    ' checks=' + stagnantChecks
                                                );
                                                stagnantChecks = 0;
                                            }

                                            return;
                                        }

                                        if (touchStalled && !atAbsoluteBottom) {
                                            AndroidReaderBridge.onLogStep(
                                                'READER: SCROLL_TOUCH_STALLED_NEAR_BOTTOM y=' + Math.floor(metrics.y) +
                                                ' remaining=' + Math.floor(distance) +
                                                ' checks=' + stagnantChecks
                                            );
                                        }

                                        if (bottomStarted === 0) {
                                            bottomStarted = Date.now();
                                            AndroidReaderBridge.onLogStep(
                                                'READER: END_CANDIDATE remaining=' + Math.floor(distance) +
                                                ' atAbsoluteBottom=' + atAbsoluteBottom +
                                                ' touchStalled=' + touchStalled
                                            );
                                            AndroidReaderBridge.onLogStep('READER: BOTTOM_STABILIZATION_STARTED');
                                            return;
                                        }

                                        var stable = Date.now() - bottomStarted;
                                        if (stable < $READER_END_STABLE_MS) return;

                                        /*
                                         * Re-read the viewport after stabilization. Never complete a
                                         * chapter just because the scroll position stopped changing:
                                         * the position must still be at/near the real document bottom.
                                         */
                                        var finalScrollTop = Math.max(
                                            window.scrollY || 0,
                                            window.pageYOffset || 0,
                                            scrollingElement ? (scrollingElement.scrollTop || 0) : 0,
                                            document.documentElement ? (document.documentElement.scrollTop || 0) : 0,
                                            document.body ? (document.body.scrollTop || 0) : 0
                                        );
                                        var finalHeight = Math.max(
                                            scrollingElement ? (scrollingElement.scrollHeight || 0) : 0,
                                            document.documentElement ? (document.documentElement.scrollHeight || 0) : 0,
                                            document.body ? (document.body.scrollHeight || 0) : 0
                                        );
                                        var finalViewport = Math.max(
                                            window.visualViewport && window.visualViewport.height
                                                ? window.visualViewport.height
                                                : 0,
                                            window.innerHeight || 0,
                                            1
                                        );
                                        var finalRemaining = Math.max(
                                            0,
                                            finalHeight - finalViewport - finalScrollTop
                                        );

                                        if (finalRemaining > 120) {
                                            AndroidReaderBridge.onLogStep(
                                                'READER: BOTTOM_STABILIZATION_ABORT remaining=' +
                                                Math.floor(finalRemaining) +
                                                ' reason=NOT_AT_BOTTOM'
                                            );
                                            bottomStarted = 0;
                                            unknownFinalStart = 0;
                                            stagnantChecks = 0;
                                            return;
                                        }

                                        if (isWaitingConfirmation || completionScheduled) return;
                                        isWaitingConfirmation = true;
                                        completionScheduled = true;

                                        AndroidReaderBridge.onLogStep('READER: BOTTOM_STABILIZATION_CHECK');
                                        AndroidReaderBridge.onLogStep('READER: BOTTOM_STABLE elapsed=' + stable);
                                        AndroidReaderBridge.onLogStep('READER: FINAL_UI_CHECK');
                                        AndroidReaderBridge.onLogStep('NEXT_CHAPTER_DOM_SCAN');

                                        // MangaBuff may delay the reading-quest counter because
                                        // history is batched (ccl=2). The quest counter is diagnostic
                                        // only and must never stop the reader per chapter.
                                        var stateAtBottom = checkMangaBuffReadState();
                                        logReadState(stateAtBottom);

                                        // Explicitly invoke MangaBuff's own history routine at chapter
                                        // completion. With ccl=2 the site batches chapters in history_pool
                                        // and posts /addHistory?r=702 only when its batch is full.
                                        // This is intentionally NOT a completion gate.
                                        triggerMangaBuffHistoryIfReady('CHAPTER_BOTTOM');
                                        AndroidReaderBridge.onLogStep(
                                            'READER: SERVER_QUEST_DIAGNOSTIC isRead=' +
                                            stateAtBottom.isRead +
                                            ' historyContainsCurrent=' +
                                            stateAtBottom.containsCurrent +
                                            ' postStatus=' + stateAtBottom.postStatus
                                        );

                                        // MangaBuff can update history/read-state asynchronously and
                                        // the server quest counter can lag by 1.5-2 chapters (ccl batching).
                                        // NEVER use the quest counter as a per-chapter stop condition.
                                        // Also do not stop merely because the next-chapter DOM is late.
                                        function resolveNextChapterAfterBottom(attempt) {
                                            if (chapterDone) return;

                                            /*
                                             * A document bottom is only an end candidate.
                                             * Do not advance until MangaBuff confirms the current
                                             * chapter in its own read/history state.
                                             *
                                             * For CCL=2 this is normally immediate because the
                                             * chapter is in history_pool. When the batch is being
                                             * submitted, wait for ADD_HISTORY_2XX instead.
                                             */
                                            waitForMangaBuffConfirmation(function(confirmationSource) {
                                                if (chapterDone) return;

                                                var nextAfterSettle = findNextChapter();
                                                var isLastAfterSettle = isLastChapter();
                                                var candidateAfterSettle =
                                                    (!nextAfterSettle && !isLastAfterSettle)
                                                        ? buildCandidateNextUrl()
                                                        : '';

                                                if (nextAfterSettle) {
                                                    var nextHref = getHref(nextAfterSettle);
                                                    if (isValidNextUrl(nextHref)) {
                                                        AndroidReaderBridge.onLogStep(
                                                            'NEXT_CHAPTER_FOUND_DOM_RETRY attempt=' + attempt +
                                                            ' confirmation=' + (confirmationSource || 'UNKNOWN') +
                                                            ' url=' + nextHref
                                                        );
                                                        chapterDone = true;
                                                        finish(nextAfterSettle, false, confirmationSource);
                                                        return;
                                                    }
                                                }

                                                if (isLastAfterSettle) {
                                                    AndroidReaderBridge.onLogStep(
                                                        'FINAL_UI_DETECTED type=NOTIFY_NEW_CHAPTER confirmation=' +
                                                        (confirmationSource || 'UNKNOWN')
                                                    );
                                                    AndroidReaderBridge.onLogStep('LAST_CHAPTER_CONFIRMED');
                                                    chapterDone = true;
                                                    finish(null, true, confirmationSource);
                                                    return;
                                                }

                                                if (candidateAfterSettle && isValidNextUrl(candidateAfterSettle)) {
                                                    AndroidReaderBridge.onLogStep(
                                                        'NEXT_CHAPTER_URL_FALLBACK_RETRY attempt=' + attempt +
                                                        ' confirmation=' + (confirmationSource || 'UNKNOWN') +
                                                        ' current=' + window.location.href +
                                                        ' candidate=' + candidateAfterSettle
                                                    );
                                                    chapterDone = true;
                                                    finishWithCandidateUrl(candidateAfterSettle, confirmationSource);
                                                    return;
                                                }

                                                if (attempt < 15) {
                                                    AndroidReaderBridge.onLogStep(
                                                        'NEXT_CHAPTER_DISCOVERY_RETRY attempt=' + (attempt + 1) +
                                                        '/15 reason=DOM_NOT_READY_AFTER_MANGABUFF_CONFIRMATION'
                                                    );
                                                    setTimeout(function() {
                                                        resolveNextChapterAfterBottom(attempt + 1);
                                                    }, 1000);
                                                    return;
                                                }

                                                AndroidReaderBridge.onLogStep(
                                                    'NEXT_CHAPTER_DISCOVERY_GIVE_UP reason=NO_VALID_NEXT_URL_AFTER_CONFIRMATION',
                                                    true
                                                );
                                                chapterDone = true;
                                                AndroidReaderBridge.onNextChapterUnknown();
                                            });
                                        }

                                        setTimeout(function() {
                                            resolveNextChapterAfterBottom(1);
                                        }, 1200);
                                    }

                                    function humanScroll() {
                                        if (chapterDone) return;

                                        /*
                                         * Real touch-like reader motion.
                                         *
                                         * A stalled native gesture is NEVER EOF. Every gesture is
                                         * verified by reading scrollTop again after the native fling.
                                         * Only verified movement advances the swipe scheduler.
                                         */
                                        try { stopScroll(); } catch(e) {}

                                        window.__mbNativeFingerRunning = true;
                                        window.__mbNativeFingerTimer = null;
                                        window.__mbNativeFingerBusy = false;

                                        var swipeRecoveryAttempts = 0;
                                        var swipeSequence = 0;

                                        AndroidReaderBridge.onLogStep(
                                            'READER: NATIVE_FINGER_SCROLL_STARTED mode=TOUCH_SWIPE_VERIFIED'
                                        );

                                        function metrics() {
                                            /*
                                             * IMPORTANT:
                                             * Do not use scrollingElement.clientHeight as the viewport.
                                             * MangaBuff's reader CSS can make the document/HTML clientHeight
                                             * grow together with the lazy-loaded image stack. During chapter
                                             * 67 it became 37,000+ px, which made:
                                             *
                                             *   height == viewport
                                             *   remaining == 0
                                             *
                                             * even though the real WebView viewport was only ~850 px.
                                             *
                                             * window.innerHeight is the actual visible CSS viewport of the
                                             * WebView. Keep document scrollHeight as the content height.
                                             */
                                            var viewport = Math.max(
                                                window.visualViewport && window.visualViewport.height
                                                    ? window.visualViewport.height
                                                    : 0,
                                                window.innerHeight || 0,
                                                1
                                            );

                                            var y = Math.max(
                                                window.scrollY || 0,
                                                window.pageYOffset || 0,
                                                document.documentElement ? (document.documentElement.scrollTop || 0) : 0,
                                                document.body ? (document.body.scrollTop || 0) : 0
                                            );

                                            var height = Math.max(
                                                document.documentElement ? (document.documentElement.scrollHeight || 0) : 0,
                                                document.body ? (document.body.scrollHeight || 0) : 0
                                            );

                                            return {
                                                y: y,
                                                viewport: viewport,
                                                height: height,
                                                remaining: Math.max(0, height - viewport - y)
                                            };
                                        }

                                        function scheduleNext(delayMs) {
                                            if (chapterDone || !window.__mbNativeFingerRunning) return;
                                            if (window.__mbNativeFingerTimer) clearTimeout(window.__mbNativeFingerTimer);
                                            window.__mbNativeFingerTimer = setTimeout(nextSwipe, Math.max(40, delayMs));
                                        }

                                        function nextSwipe() {
                                            if (chapterDone || !window.__mbNativeFingerRunning) return;
                                            if (window.__mbNativeFingerBusy) return;

                                            var m = metrics();

                                            // onPageFinished/CHAPTER_PAGE_READY does not guarantee that
                                            // the next rendered frame already contains the final DOM
                                            // layout. WebView can briefly report a zero/viewport-only
                                            // scroll range while the reader images are being laid out.
                                            // Never interpret that transient state as EOF.
                                            if (m.height <= m.viewport + 16) {
                                                AndroidReaderBridge.onLogStep(
                                                    'READER: NATIVE_FINGER_METRICS_NOT_READY height=' +
                                                    Math.floor(m.height) +
                                                    ' viewport=' + Math.floor(m.viewport) +
                                                    ' y=' + Math.floor(m.y)
                                                );
                                                scheduleNext(250);
                                                return;
                                            }

                                            if (m.remaining <= 8) {
                                                AndroidReaderBridge.onLogStep(
                                                    'READER: NATIVE_FINGER_TARGET_BOTTOM remaining=' +
                                                    Math.floor(m.remaining)
                                                );

                                                /*
                                                 * IMPORTANT:
                                                 * The reader uses lazy-loaded images. Reaching the current
                                                 * bottom is not necessarily the final document bottom:
                                                 * after the last swipe, new images can increase scrollHeight.
                                                 * Never leave the native swipe scheduler stopped here.
                                                 * Re-measure after a short settle delay; if the document grew,
                                                 * the next swipe will continue from the new bottom.
                                                 */
                                                scheduleNext(700);
                                                return;
                                            }

                                            var startY = m.viewport * (0.76 + Math.random() * 0.06);
                                            var endY = m.viewport * (0.22 + Math.random() * 0.08);
                                            var distance = startY - endY;
                                            var maxDistance = Math.max(220, m.remaining - 4);

                                            if (distance > maxDistance) {
                                                startY = Math.min(m.viewport * 0.84, endY + maxDistance);
                                                distance = startY - endY;
                                            }

                                            var x = Math.max(
                                                12,
                                                Math.min(
                                                    Math.max(12, (window.innerWidth || 384) - 12),
                                                    (window.innerWidth || 384) * (0.46 + (Math.random() - 0.5) * 0.08)
                                                )
                                            );

                                            var xJitter = (Math.random() - 0.5) * 18;
                                            var x1 = Math.max(8, Math.min((window.innerWidth || 384) - 8, x));
                                            var x2 = Math.max(8, Math.min((window.innerWidth || 384) - 8, x + xJitter));

                                            var duration = 320 + Math.floor(Math.random() * 220);
                                            var pause = 80 + Math.floor(Math.random() * 120);
                                            var beforeY = m.y;
                                            var sequence = ++swipeSequence;

                                            window.__mbNativeFingerBusy = true;

                                            AndroidReaderBridge.onLogStep(
                                                'READER: NATIVE_FINGER_SWIPE seq=' + sequence +
                                                ' distance=' + Math.floor(distance) +
                                                ' duration=' + duration +
                                                ' pause=' + pause +
                                                ' beforeY=' + Math.floor(beforeY) +
                                                ' beforeRemaining=' + Math.floor(m.remaining)
                                            );

                                            try {
                                                AndroidReaderBridge.nativeSwipe(x1, startY, x2, endY, duration);
                                            } catch(e) {
                                                AndroidReaderBridge.onLogStep(
                                                    'READER: NATIVE_FINGER_SWIPE_ERROR seq=' + sequence +
                                                    ' error=' + (e && e.message ? e.message : String(e))
                                                );
                                            }

                                            window.__mbNativeFingerTimer = setTimeout(function() {
                                                if (chapterDone || !window.__mbNativeFingerRunning) {
                                                    window.__mbNativeFingerBusy = false;
                                                    return;
                                                }

                                                var after = metrics();
                                                var deltaY = Math.abs(after.y - beforeY);
                                                var moved = deltaY >= 80;

                                                AndroidReaderBridge.onLogStep(
                                                    'READER: NATIVE_FINGER_SWIPE_RESULT seq=' + sequence +
                                                    ' moved=' + moved +
                                                    ' beforeY=' + Math.floor(beforeY) +
                                                    ' afterY=' + Math.floor(after.y) +
                                                    ' deltaY=' + Math.floor(after.y - beforeY) +
                                                    ' remaining=' + Math.floor(after.remaining)
                                                );

                                                if (after.remaining > 120 && deltaY < 80) {
                                                    swipeRecoveryAttempts++;
                                                    AndroidReaderBridge.onLogStep(
                                                        'READER: NATIVE_FINGER_SWIPE_NO_PROGRESS seq=' + sequence +
                                                        ' remaining=' + Math.floor(after.remaining) +
                                                        ' attempt=' + swipeRecoveryAttempts
                                                    );

                                                    if (swipeRecoveryAttempts < 3) {
                                                        window.__mbNativeFingerBusy = false;
                                                        scheduleNext(120);
                                                        return;
                                                    }

                                                    var recoveryDistance = Math.min(
                                                        Math.max(360, Math.floor(after.viewport * 0.65)),
                                                        Math.max(360, Math.floor(after.remaining - 8))
                                                    );

                                                    AndroidReaderBridge.onLogStep(
                                                        'READER: NATIVE_FINGER_SWIPE_RECOVERY_SCROLLBY seq=' + sequence +
                                                        ' delta=' + recoveryDistance +
                                                        ' remaining=' + Math.floor(after.remaining)
                                                    );

                                                    try {
                                                        AndroidReaderBridge.nativeRecoveryScroll(recoveryDistance);
                                                    } catch(e) {
                                                        AndroidReaderBridge.onLogStep(
                                                            'READER: NATIVE_RECOVERY_SCROLLBY_CALL_ERROR seq=' + sequence +
                                                            ' error=' + (e && e.message ? e.message : String(e))
                                                        );
                                                    }

                                                    swipeRecoveryAttempts = 0;
                                                    window.__mbNativeFingerBusy = false;
                                                    scheduleNext(300);
                                                } else {
                                                    swipeRecoveryAttempts = 0;
                                                    window.__mbNativeFingerBusy = false;
                                                    scheduleNext(pause);
                                                }
                                            }, duration + 450);
                                        }

                                        // Give WebView one rendered frame to settle the reader
                                        // layout before the first synthetic finger gesture. Android
                                        // explicitly notes that onPageFinished() does not guarantee that
                                        // the next frame already reflects the final DOM state.
                                        scheduleNext(250);
                                    }
                                    
                                    var interval = setInterval(function() {
                                        checkEnd();

                                        var gift = document.querySelector('div.card-notification, .reader-gift');
                                        if (gift && !window.__mbGiftCollected) {
                                            window.__mbGiftCollected = true;
                                            try { gift.click(); } catch(e) {}
                                            AndroidReaderBridge.onCardGiftCollected();
                                            setTimeout(function() {
                                                var close = document.querySelector('.modal__close, .manga-cards__close');
                                                if (close) { try { close.click(); } catch(e) {} }
                                            }, 1000);
                                        }
                                    }, 700);

                                    humanScroll();

                                } catch(e) {
                                    AndroidReaderBridge.onLogStep('READER: SCRIPT_EXCEPTION ' + (e.message || String(e)));
                                    AndroidReaderBridge.onNextChapterUnknown();
                                }
                            })();
                        """.trimIndent()

                        view?.evaluateJavascript(script, null)
                        return
                    }

                    // =================================================
                    // CATALOG
                    // =================================================

                    if (
                        currentUrl.contains("/manga?hide_read=1") ||
                        (currentUrl.endsWith("/manga") && !currentUrl.contains("/manga/"))
                    ) {
                        val skipped = skippedMangaUrls.map { ensureCanonicalMangaUrl(it) }

                        val script = """
                            (function() {
                                try {
                                    var checkbox = document.querySelector(
                                        'input[name="hide_read"], input#hide_read, input[type="checkbox"][value="hide_read"]'
                                    );

                                    if (checkbox && !checkbox.checked) {
                                        AndroidReaderBridge.onLogStep('CATALOG: CATALOG_FILTER_HIDE_READ_CLICK');
                                        checkbox.click();

                                        var form = checkbox.closest('form');
                                        var button = form ? form.querySelector('button[type="submit"], button.button--primary, input[type="submit"]') : null;

                                        if (button) {
                                            AndroidReaderBridge.onLogStep('CATALOG: CATALOG_FILTER_APPLY_CLICK');
                                            button.click();
                                            return;
                                        }
                                    } else {
                                        AndroidReaderBridge.onLogStep('CATALOG: CATALOG_FILTER_HIDE_READ_ACTIVE');
                                    }

                                    var skipped = ${com.google.gson.Gson().toJson(skipped)};

                                    var cards = Array.from(document.querySelectorAll('.cards__item, a.cards__item')).filter(function(card) {
                                        if (!card.href) return false;
                                        var href = new URL(card.href, window.location.origin).href;
                                        return (!skipped.includes(href) && href.indexOf('/manga/') !== -1);
                                    });

                                    if (!cards.length) {
                                        AndroidReaderBridge.onLogStep('CATALOG: CATALOG_EMPTY');
                                        AndroidReaderBridge.onChapterListEmpty();
                                        return;
                                    }

                                    var selected = cards[Math.floor(Math.random() * cards.length)];
                                    var href = new URL(selected.href, window.location.origin).href;
                                    AndroidReaderBridge.onLogStep('CATALOG: RANDOM_MANGA_SELECTED url=' + href);
                                    window.location.href = href;

                                } catch(e) {
                                    AndroidReaderBridge.onLogStep('CATALOG: ERROR ' + e.message);
                                    AndroidReaderBridge.onChapterListEmpty();
                                }
                            })();
                        """.trimIndent()

                        view?.evaluateJavascript(script, null)
                        return
                    }

                    // =================================================
                    // MANGA INFO
                    // =================================================

                    if (isMangaInfoUrl(currentUrl)) {
                        if (pendingMangaMarkAsRead) {
                            val script = """
                                (function() {
                                    try {
                                        function visible(el) {
                                            if (!el) return false;
                                            var s = getComputedStyle(el);
                                            return (s.display !== 'none' && s.visibility !== 'hidden' && (el.offsetWidth > 0 || el.offsetHeight > 0));
                                        }

                                        function text(el) {
                                            return (el && (el.innerText || el.textContent || '')).replace(/\s+/g, ' ').trim();
                                        }

                                        function norm(el) {
                                            return text(el).toLowerCase();
                                        }

                                        var title = document.querySelector('h1');
                                        var titleText = title ? text(title) : document.title;
                                        function findBookmarkButton() {
                                            return (
                                                document.querySelector('button.reader-menu__item--bookmark') ||
                                                document.querySelector('.reader-menu__item--bookmark') ||
                                                document.querySelector('.manga__bookmark-btn') ||
                                                Array.from(document.querySelectorAll('button, a, [role="button"]')).find(function(el) {
                                                    var t = norm(el);
                                                    var aria = (el.getAttribute('aria-label') || '').toLowerCase();
                                                    var titleAttr = (el.getAttribute('title') || '').toLowerCase();
                                                    return t.indexOf('в закладки') !== -1 ||
                                                        t.indexOf('прочитано') !== -1 ||
                                                        aria.indexOf('закладки') !== -1 ||
                                                        aria.indexOf('прочитано') !== -1 ||
                                                        titleAttr.indexOf('закладки') !== -1 ||
                                                        titleAttr.indexOf('прочитано') !== -1;
                                                })
                                            );
                                        }

                                        function findReadItem() {
                                            var direct = document.querySelector(
                                                '.menu.menu--bookmark button[data-folder-id="3"], ' +
                                                '.menu--bookmark button[data-folder-id="3"], ' +
                                                '[data-folder-id="3"]'
                                            );
                                            if (direct && visible(direct)) return direct;

                                            return Array.from(document.querySelectorAll(
                                                'button, a, [role="button"], .menu__item, [data-folder-id]'
                                            )).find(function(el) {
                                                if (!visible(el)) return false;
                                                var t = norm(el);
                                                var folder = (el.getAttribute('data-folder-id') || '').trim();
                                                return folder === '3' || t.indexOf('прочитано') !== -1;
                                            }) || null;
                                        }

                                        function isMarkedRead() {
                                            var readItem = findReadItem();
                                            if (readItem && readItem.classList && readItem.classList.contains('menu__item--active')) return true;

                                            var bookmark = findBookmarkButton();
                                            if (!bookmark) return false;
                                            return norm(bookmark).indexOf('прочитано') !== -1;
                                        }

                                        var btn = findBookmarkButton();

                                        if (!btn || !visible(btn)) {
                                            AndroidReaderBridge.onLogStep('READER: READ_ACTION_NOT_FOUND selectors=reader-menu__item--bookmark,manga__bookmark-btn');
                                            AndroidReaderBridge.onMangaMarkedRead(titleText, false);
                                            return;
                                        }

                                        AndroidReaderBridge.onLogStep(
                                            'READER: READ_ACTION_FOUND tag=' + btn.tagName +
                                            ' class="' + (btn.className || '') +
                                            '" text="' + text(btn) + '"'
                                        );

                                        if (isMarkedRead()) {
                                            AndroidReaderBridge.onLogStep('READER: READ_ACTION_CONFIRMED folder-id=3 (Прочитано)');
                                            AndroidReaderBridge.onMangaMarkedRead(titleText, true);
                                            return;
                                        }

                                        try { btn.click(); } catch(e) {}
                                        AndroidReaderBridge.onLogStep('READER: READ_ACTION_CLICKED_BOOKMARK_MENU');

                                        setTimeout(function() {
                                            var item = findReadItem();

                                            if (!item) {
                                                AndroidReaderBridge.onLogStep('READER: READ_ACTION_NOT_CONFIRMED reason=READ_ITEM_NOT_FOUND');
                                                AndroidReaderBridge.onMangaMarkedRead(titleText, false);
                                                return;
                                            }

                                            AndroidReaderBridge.onLogStep(
                                                'READER: READ_DROPDOWN_ITEM_FOUND folder-id=' +
                                                (item.getAttribute('data-folder-id') || '') +
                                                ' text="' + text(item) + '"'
                                            );

                                            try { item.click(); } catch(e) {}
                                            AndroidReaderBridge.onLogStep('READER: READ_ACTION_CLICKED folder-id=3');

                                            setTimeout(function() {
                                                if (isMarkedRead()) {
                                                    AndroidReaderBridge.onLogStep('READER: READ_ACTION_CONFIRMED folder-id=3 (Прочитано)');
                                                    AndroidReaderBridge.onMangaMarkedRead(titleText, true);
                                                } else {
                                                    AndroidReaderBridge.onLogStep('READER: READ_ACTION_NOT_CONFIRMED reason=BUTTON_STATE_NOT_READ');
                                                    AndroidReaderBridge.onMangaMarkedRead(titleText, false);
                                                }
                                            }, 1200);
                                        }, 500);
                                    } catch(e) {
                                        AndroidReaderBridge.onMangaMarkedRead(document.title, false);
                                    }
                                })();
                            """.trimIndent()

                            view?.evaluateJavascript(script, null)
                            return
                        }

                        currentMangaUrl = ensureCanonicalMangaUrl(currentUrl)

                        val script = """
                            (function() {
                                try {
                                    function detectTotalChapters() {
                                        try {
                                            var path = window.location.pathname || "";
                                            var parts = path.split('/').filter(Boolean);
                                            var isMangaPage = (parts.length === 2 && parts[0] === 'manga');
                                            if (!isMangaPage) return 0;

                                            var count1 = document.querySelectorAll('.chapters__item').length;
                                            var count2 = document.querySelectorAll('.chapters__list a').length;
                                            var count3 = document.querySelectorAll('.manga__chapters-list .chapters__item').length;

                                            var total = Math.max(count1, count2, count3);
                                            return total;
                                        } catch(e) {
                                            console.warn("READER: TOTAL_CHAPTERS_SCAN_ERROR", e);
                                        }
                                        return 0;
                                    }

                                    var totalFound = detectTotalChapters();
                                    if (totalFound > 0) {
                                        AndroidReaderBridge.onTotalChaptersFound(totalFound);
                                    }

                                    var readBtn = document.querySelector('a.read-btn');
                                    var bodyText = (document.body && (document.body.innerText || document.body.textContent || '')).replace(/\s+/g, ' ').trim().toLowerCase();

                                    AndroidReaderBridge.onLogStep('MANGA_PAGE_READY');

                                    if (bodyText.indexOf('нет глав') !== -1) {
                                        AndroidReaderBridge.onLogStep('MANGA_NO_CHAPTERS');
                                        window.location.href = window.location.origin + '/manga?hide_read=1';
                                        return;
                                    }

                                    if (readBtn) {
                                        var href = readBtn.href || '';
                                        var raw = readBtn.getAttribute('href') || '';
                                        var text = (readBtn.innerText || readBtn.textContent || '').replace(/\s+/g, ' ').trim();

                                        AndroidReaderBridge.onReadButtonFound(text, raw, readBtn.getAttribute('data-slug') || '');
                                        AndroidReaderBridge.onReadButtonUrlAudit(window.location.href, document.baseURI, raw, href);
                                        AndroidReaderBridge.onLogStep('OPEN_CHAPTER');
                                        AndroidReaderBridge.onSaveActiveManga(window.location.href, document.title || '');

                                        if (href) {
                                            window.location.href = href;
                                        } else {
                                            readBtn.click();
                                        }
                                        return;
                                    }

                                    AndroidReaderBridge.onReadButtonNotFound();

                                    var tabs = document.querySelector('button.tabs__item[data-page="chapters"]');
                                    if (tabs) {
                                        try { tabs.click(); } catch(e) {}
                                    }

                                    setTimeout(function() {
                                        var path = window.location.pathname || "";
                                        var parts = path.split('/').filter(Boolean);
                                        var isMangaPage = (parts.length === 2 && parts[0] === 'manga');
                                        if (isMangaPage) {
                                            var count1 = document.querySelectorAll('.chapters__item').length;
                                            var count2 = document.querySelectorAll('.chapters__list a').length;
                                            var count3 = document.querySelectorAll('.manga__chapters-list .chapters__item').length;
                                            var total = Math.max(count1, count2, count3);
                                            if (total > 0) {
                                                AndroidReaderBridge.onTotalChaptersFound(total);
                                            }
                                        }

                                        var items = document.querySelectorAll('.chapters__item, .chapters__list a, [data-chapter-id], .manga__chapters-list .chapters__item');
                                        if (!items.length) {
                                            AndroidReaderBridge.onLogStep('MANGA_NO_CHAPTERS');
                                            window.location.href = window.location.origin + '/manga?hide_read=1';
                                            return;
                                        }

                                        AndroidReaderBridge.onTotalChaptersFound(items.length);

                                        var first = Array.from(items).find(function(item) {
                                            return !!item.querySelector('a');
                                        });

                                        var a = first ? first.querySelector('a') : null;
                                        if (a && a.href) {
                                            AndroidReaderBridge.onSaveActiveManga(window.location.href, document.title || '');
                                            window.location.href = a.href;
                                        } else {
                                            AndroidReaderBridge.onNextChapterUnknown();
                                        }
                                    }, 1000);

                                } catch(e) {
                                    AndroidReaderBridge.onNextChapterUnknown();
                                }
                            })();
                        """.trimIndent()

                        view?.evaluateJavascript(script, null)
                        return
                    }

                    // Opening candidate URL that turned out NOT to be a chapter page
                    if (startUrl.isNotBlank()) {
                        log(account.username, "NEXT_CHAPTER_CANDIDATE_REJECTED reason=NOT_A_VALID_CHAPTER_PAGE url=$currentUrl")
                        safeResume(ReaderResult.Failed("CANDIDATE_REJECTED"))
                    }
                }
            }

            when {
                startUrl.isNotBlank() -> {
                    val url = startUrl.substringBefore('?').substringBefore('#')
                    val valid = Regex("^https://mangabuff\\.ru/manga/[^/]+/\\d+/\\d+$").matches(url)

                    if (!valid) {
                        log(account.username, "READER: INVALID_NEXT_CHAPTER_URL url=$url", true)
                        safeResume(ReaderResult.Failed("INVALID_NEXT_CHAPTER_URL"))
                        return@post
                    }

                    log(account.username, "READER: OPEN_NEXT_CHAPTER url=$url")
                    webView.loadUrl(url)
                }

                currentMangaUrl.isNotBlank() -> {
                    log(account.username, "READER: OPEN_SAVED_MANGA url=$currentMangaUrl")
                    webView.loadUrl(currentMangaUrl)
                }

                else -> {
                    log(account.username, "CATALOG: CATALOG_SCAN")
                    webView.loadUrl("https://mangabuff.ru/manga?hide_read=1")
                }
            }
        }
    }

    // =========================================================
    // INLINE COMMENT
    // =========================================================

    private suspend fun runCommentOnCurrentChapter(
        account: MangaBuffAccount,
        webView: WebView,
        targetUrl: String
    ): Boolean = suspendCancellableCoroutine { continuation ->

        var resumed = false

        fun safeResume(result: Boolean) {
            if (!resumed && continuation.isActive) {
                resumed = true
                continuation.resume(result)
            }
        }

        mainHandler.post {

            class CommentBridge {

                @JavascriptInterface
                fun onCommentLog(msg: String) {
                    log(account.username, "COMMENT: $msg")
                }

                @JavascriptInterface
                fun onCommentResult(success: Boolean) {
                    safeResume(success)
                }
            }

            try { webView.removeJavascriptInterface("AndroidCommentBridge") } catch (_: Exception) {}
            webView.addJavascriptInterface(CommentBridge(), "AndroidCommentBridge")

            fun injectScript() {
                val available = commentPhrases.indices
                    .filter { it !in recentCommentIndexes }
                    .ifEmpty { commentPhrases.indices.toList() }

                val index = available.random()
                recentCommentIndexes.add(index)
                if (recentCommentIndexes.size > 5) {
                    recentCommentIndexes.removeAt(0)
                }

                val text = commentPhrases[index]
                log(account.username, "COMMENT: TEXT_SELECTED index=$index text=\"$text\"")

                val script = """
                    (function() {
                        try {
                            function visible(el) {
                                if (!el) return false;
                                var s = getComputedStyle(el);
                                return (s.display !== 'none' && s.visibility !== 'hidden' && (el.offsetWidth > 0 || el.offsetHeight > 0));
                            }

                            function findEditor() {
                                var selectors = [
                                    'textarea[name="text"]',
                                    'textarea.comments__input',
                                    'textarea.comments__textarea',
                                    '.comments textarea',
                                    'textarea[placeholder]',
                                    '[contenteditable="true"]',
                                    'input[name="text"]'
                                ];
                                for (var i = 0; i < selectors.length; i++) {
                                    var list = Array.from(document.querySelectorAll(selectors[i]));
                                    var found = list.find(visible);
                                    if (found) return found;
                                }
                                return null;
                            }

                            function sendButton() {
                                var exact = document.querySelector('button.comments__send-btn');
                                if (exact && visible(exact) && !exact.disabled) return exact;
                                return null;
                            }

                            var pagePath = window.location.pathname || '';
                            if (!(new RegExp("^/manga/[^/]+/[0-9]+/[0-9]+$")).test(pagePath)) {
                                AndroidCommentBridge.onCommentLog('COMMENT_SKIP_NON_CHAPTER_URL path=' + pagePath);
                                AndroidCommentBridge.onCommentResult(false);
                                return;
                            }

                            function waitForEditor(done) {
                                var started = Date.now();
                                var maxWait = 15000;

                                function poll() {
                                    var found = findEditor();
                                    if (found) {
                                        done(found);
                                        return;
                                    }

                                    var elapsed = Date.now() - started;
                                    if (elapsed >= maxWait) {
                                        AndroidCommentBridge.onCommentLog('EDITOR_NOT_FOUND_TIMEOUT after=' + elapsed + 'ms');
                                        AndroidCommentBridge.onCommentResult(false);
                                        return;
                                    }

                                    AndroidCommentBridge.onCommentLog('EDITOR_WAIT elapsed=' + elapsed + 'ms');
                                    setTimeout(poll, 500);
                                }

                                poll();
                            }

                            waitForEditor(function(editor) {
                            var value = ${com.google.gson.Gson().toJson(text)};
                            editor.focus();

                            if (editor.isContentEditable) {
                                editor.textContent = value;
                            } else {
                                var descriptor = Object.getOwnPropertyDescriptor(
                                    Object.getPrototypeOf(editor),
                                    'value'
                                );
                                if (descriptor && descriptor.set) {
                                    descriptor.set.call(editor, value);
                                } else {
                                    editor.value = value;
                                }
                            }

                            editor.dispatchEvent(new Event('input', { bubbles: true }));
                            editor.dispatchEvent(new Event('change', { bubbles: true }));

                            AndroidCommentBridge.onCommentLog('COMMENT_TEXT_FILLED');

                            var btn = sendButton();
                            if (!btn) {
                                AndroidCommentBridge.onCommentLog('COMMENT_SEND_BUTTON_NOT_FOUND');
                                AndroidCommentBridge.onCommentResult(false);
                                return;
                            }

                            AndroidCommentBridge.onCommentLog('COMMENT_SEND_BUTTON_FOUND');
                            btn.click();
                            AndroidCommentBridge.onCommentLog('COMMENT_SEND_CLICK');

                            var elapsed = 0;
                            var timer = setInterval(function() {
                                elapsed += 500;
                                var current = findEditor();
                                var empty = current
                                    ? (current.isContentEditable
                                        ? ((current.innerText || '').trim() === '')
                                        : ((current.value || '').trim() === ''))
                                    : false;

                                if (empty) {
                                    clearInterval(timer);
                                    AndroidCommentBridge.onCommentLog('COMMENT_DOM_CONFIRMED');
                                    AndroidCommentBridge.onCommentResult(true);
                                } else if (elapsed >= 12000) {
                                    clearInterval(timer);
                                    AndroidCommentBridge.onCommentLog('COMMENT_VERIFY_TIMEOUT_AFTER_CLICK');
                                    AndroidCommentBridge.onCommentResult(true);
                                }
                            }, 500);


                            });                        } catch(e) {
                            AndroidCommentBridge.onCommentLog('COMMENT_EXCEPTION ' + e.message);
                            AndroidCommentBridge.onCommentResult(false);
                        }
                    })();
                """.trimIndent()

                webView.evaluateJavascript(script, null)
            }

            val currentUrl = webView.url ?: ""
            val cleanTarget = targetUrl.substringBefore('?').substringBefore('#')
            val cleanCurrent = currentUrl.substringBefore('?').substringBefore('#')

            if (cleanCurrent.isNotBlank() && (cleanCurrent == cleanTarget || cleanCurrent.startsWith(cleanTarget))) {
                injectScript()
            } else {
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        mainHandler.postDelayed({
                            if (continuation.isActive) injectScript()
                        }, 1200L)
                    }
                }
                webView.loadUrl(targetUrl)
            }
        }
    }

    // =========================================================
    // SEPARATE COMMENT TASK
    // =========================================================

    private suspend fun runCommentTask(
        account: MangaBuffAccount,
        settings: GlobalSettings,
        webView: WebView
    ) {
        val target = settings.commentCount
        log(account.username, "TASK: COMMENT_START count=$target")

        val targetUrl = activeChapterContext?.actualChapterUrl?.ifBlank { null }
            ?: lastFinishedChapterUrl.ifBlank { null }

        val targetClean = targetUrl?.substringBefore('?')?.substringBefore('#').orEmpty()
        val targetIsChapter = Regex("^https://mangabuff\\.ru/manga/[^/]+/\\d+/\\d+$").matches(targetClean)

        if (!targetIsChapter) {
            val current = webView.url.orEmpty().substringBefore('?').substringBefore('#')
            log(account.username, "COMMENT: SKIP_NO_CHAPTER_PAGE target='$targetClean' current='$current'")
            return
        }

        log(account.username, "COMMENT_TARGET_FOUND url=$targetClean")

        var successCount = 0
        var failedCount = 0

        repeat(target) { index ->
            coroutineContext.ensureActive()

            val result = runCommentOnCurrentChapter(account, webView, targetClean.orEmpty())

            if (result) {
                successCount++
            } else {
                failedCount++
            }

            if (index + 1 < target) {
                delay(COMMENT_DELAY_MS)
            }
        }

        log(account.username, "TASK: COMMENT_END sent=$successCount failed=$failedCount")
    }

    // =========================================================
    // BATTLE REWARDS
    // =========================================================

    private suspend fun claimBattleRewards(
        account: MangaBuffAccount,
        webView: WebView
    ): Boolean = suspendCancellableCoroutine { continuation ->

        var resumed = false

        fun safeResume(result: Boolean) {
            if (!resumed && continuation.isActive) {
                resumed = true
                continuation.resume(result)
            }
        }

        mainHandler.post {

            class RewardsBridge {

                @JavascriptInterface
                fun onRewardsLog(msg: String) {
                    log(account.username, "BATTLE: $msg")
                }

                @JavascriptInterface
                fun onRewardsDone() {
                    safeResume(true)
                }
            }

            try { webView.removeJavascriptInterface("AndroidBattleRewardsBridge") } catch (_: Exception) {}
            webView.addJavascriptInterface(RewardsBridge(), "AndroidBattleRewardsBridge")

            webView.webViewClient = object : WebViewClient() {

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url?.contains("/battle") != true) return

                    val script = """
                        (function() {
                            try {
                                AndroidBattleRewardsBridge.onRewardsLog('BATTLE_REWARDS_SCAN_START');

                                var finished = false;
                                var startedAt = Date.now();
                                var maxWait = 15000;

                                function normalize(text) {
                                    return (text || '')
                                        .replace(/\s+/g, ' ')
                                        .trim()
                                        .toLowerCase();
                                }

                                function finish() {
                                    if (finished) return;
                                    finished = true;
                                    AndroidBattleRewardsBridge.onRewardsLog('BATTLE_REWARDS_DONE');
                                    AndroidBattleRewardsBridge.onRewardsDone();
                                }

                                function findClaimableQuest() {
                                    var articles = Array.from(
                                        document.querySelectorAll('article[data-daily-quest].battle-home__quest--completed')
                                    );

                                    for (var i = 0; i < articles.length; i++) {
                                        var article = articles[i];

                                        var titleEl = article.querySelector(
                                            '.battle-home__quest-info b, .battle-home__quest-title, h3, h4, .quest-title'
                                        );

                                        var titleText = titleEl
                                            ? (titleEl.textContent || '').replace(/\s+/g, ' ').trim()
                                            : '';

                                        // Do NOT process the reroll quest.
                                        if (normalize(titleText).indexOf('сделать 1 реролл') !== -1) {
                                            AndroidBattleRewardsBridge.onRewardsLog(
                                                'QUEST_SKIP_REROLL title="' + titleText + '"'
                                            );
                                            continue;
                                        }

                                        var button = article.querySelector('button[data-daily-claim]');
                                        if (!button) {
                                            // Fallback selectors for older markup.
                                            button = article.querySelector(
                                                'button.battle-quests__claim, button.user-quest__claim'
                                            );
                                        }

                                        if (!button || button.disabled) continue;

                                        var buttonText = normalize(button.textContent);
                                        if (buttonText.indexOf('получить') === -1) continue;

                                        return {
                                            article: article,
                                            button: button,
                                            title: titleText,
                                            questId: article.getAttribute('data-daily-quest-id') || ''
                                        };
                                    }

                                    return null;
                                }

                                function claimNext() {
                                    if (finished) return;

                                    var item = findClaimableQuest();

                                    if (!item) {
                                        if (Date.now() - startedAt >= maxWait) {
                                            AndroidBattleRewardsBridge.onRewardsLog(
                                                'QUEST_SCAN_FINISHED no_more_claimable'
                                            );
                                            finish();
                                            return;
                                        }

                                        setTimeout(claimNext, 500);
                                        return;
                                    }

                                    AndroidBattleRewardsBridge.onRewardsLog(
                                        'QUEST_READY title="' + item.title +
                                        '" id="' + item.questId + '"'
                                    );

                                    var button = item.button;

                                    try {
                                        button.click();

                                        AndroidBattleRewardsBridge.onRewardsLog(
                                            'QUEST_CLAIM_CLICKED title="' + item.title +
                                            '" id="' + item.questId + '"'
                                        );
                                    } catch (e) {
                                        AndroidBattleRewardsBridge.onRewardsLog(
                                            'QUEST_CLAIM_EXCEPTION error=' +
                                            (e && e.message ? e.message : String(e))
                                        );
                                        setTimeout(claimNext, 500);
                                        return;
                                    }

                                    var article = item.article;
                                    var clickedAt = Date.now();

                                    function waitClaimResult() {
                                        if (finished) return;

                                        var stillThere = document.contains(article);
                                        var currentButton = stillThere
                                            ? article.querySelector('button[data-daily-claim]')
                                            : null;

                                        var currentButtonText = currentButton
                                            ? normalize(currentButton.textContent)
                                            : '';

                                        var claimed =
                                            !stillThere ||
                                            !currentButton ||
                                            currentButton.disabled ||
                                            currentButtonText.indexOf('получить') === -1;

                                        if (claimed) {
                                            AndroidBattleRewardsBridge.onRewardsLog(
                                                'QUEST_CLAIM_CONFIRMED title="' + item.title +
                                                '" id="' + item.questId + '"'
                                            );

                                            setTimeout(claimNext, 500);
                                            return;
                                        }

                                        if (Date.now() - clickedAt >= 8000) {
                                            AndroidBattleRewardsBridge.onRewardsLog(
                                                'QUEST_CLAIM_WAIT_TIMEOUT title="' + item.title +
                                                '" id="' + item.questId + '"'
                                            );
                                            setTimeout(claimNext, 500);
                                            return;
                                        }

                                        setTimeout(waitClaimResult, 300);
                                    }

                                    setTimeout(waitClaimResult, 300);
                                }

                                claimNext();

                            } catch(e) {
                                AndroidBattleRewardsBridge.onRewardsLog(
                                    'QUEST_SCAN_EXCEPTION error=' +
                                    (e && e.message ? e.message : String(e))
                                );
                                AndroidBattleRewardsBridge.onRewardsDone();
                            }
                        })();
                    """.trimIndent()

                    view?.evaluateJavascript(script, null)
                }
            }

            webView.loadUrl("https://mangabuff.ru/battle")
        }
    }
    }