package com.example.myapplication.automation

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.myapplication.data.GlobalSettings
import com.example.myapplication.data.LogEntry
import com.example.myapplication.data.MangaBuffAccount
import com.example.myapplication.data.TaskType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
    ) -> Unit = { _, _, _, _, _ -> }
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

    /** Sends a native touch swipe through the WebView View layer without blocking the UI thread. */
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
        val safeDuration = durationMs.coerceIn(650L, 1400L)
        val downTime = SystemClock.uptimeMillis()
        val steps = 18
        val stepDelay = (safeDuration / steps).coerceAtLeast(1L)

        MotionEvent.obtain(
            downTime,
            downTime,
            MotionEvent.ACTION_DOWN,
            startX,
            startY,
            0
        ).also { event ->
            try {
                webView.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }

        for (i in 1..steps) {
            val fraction = i.toFloat() / steps
            val currentX = startX + (endX - startX) * fraction
            val currentY = startY + (endY - startY) * fraction
            val eventTime = downTime + (safeDuration * fraction).toLong()
            val delayFromNow = (stepDelay * i).coerceAtLeast(1L)

            mainHandler.postDelayed({
                if (!webView.isAttachedToWindow) return@postDelayed

                MotionEvent.obtain(
                    downTime,
                    eventTime,
                    MotionEvent.ACTION_MOVE,
                    currentX,
                    currentY,
                    0
                ).also { event ->
                    try {
                        webView.dispatchTouchEvent(event)
                    } finally {
                        event.recycle()
                    }
                }

                if (i == steps) {
                    val upTime = SystemClock.uptimeMillis()
                    MotionEvent.obtain(
                        downTime,
                        upTime,
                        MotionEvent.ACTION_UP,
                        endX,
                        endY,
                        0
                    ).also { event ->
                        try {
                            webView.dispatchTouchEvent(event)
                        } finally {
                            event.recycle()
                        }
                    }
                }
            }, delayFromNow)
        }
    }
    private val skippedMangaUrls = mutableSetOf<String>()
    private val completedMangaIds = mutableSetOf<String>()
    private val completedChapterIds = mutableSetOf<String>()
    private val readChapterUrlsInRun = mutableSetOf<String>()

    @Volatile
    private var activeChapterContext: ChapterContext? = null

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

    fun getCurrentMangaUrl(): String = currentMangaUrl
    fun getLastFinishedChapterId(): String = lastFinishedChapterId
    fun getLastFinishedChapterNumber(): String = lastFinishedChapterNumber

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


    /**
     * Reload /balance after a chapter and use the server-side reading quest as
     * the source of truth. Local is_read/history_pool state is not enough.
     */
    private suspend fun verifyServerReadQuestIncrement(
        account: MangaBuffAccount,
        webView: WebView,
        beforeQuest: String
    ): Boolean {
        val beforeNum = beforeQuest.substringBefore('/').toIntOrNull() ?: 0
        val beforeTotal = beforeQuest.substringAfter('/').toIntOrNull() ?: 75

        if (beforeNum >= beforeTotal && beforeTotal > 0) {
            log(account.username, "[READQUEST_DIAG] SERVER_QUEST_ALREADY_COMPLETE before=$beforeQuest")
            fetchAndLogBalanceInfo(account, webView)
            return true
        }

        repeat(3) { attempt ->
            coroutineContext.ensureActive()
            log(account.username, "[READQUEST_DIAG] SERVER_QUEST_REFRESH attempt=${attempt + 1}/3 before=$beforeQuest")
            fetchAndLogBalanceInfo(account, webView)

            val afterQuest = lastKnownReadQuest
            val afterNum = afterQuest.substringBefore('/').toIntOrNull() ?: beforeNum
            val delta = afterNum - beforeNum
            log(account.username, "[READQUEST_DIAG] SERVER_QUEST_VERIFY before=$beforeQuest after=$afterQuest delta=${if (delta >= 0) "+$delta" else "$delta"}")

            if (afterNum > beforeNum) {
                log(account.username, "[READQUEST_DIAG] SERVER_QUEST_INCREMENT_CONFIRMED $beforeQuest->$afterQuest")
                return true
            }

            if (afterNum >= beforeTotal && beforeTotal > 0) {
                log(account.username, "[READQUEST_DIAG] SERVER_QUEST_ALREADY_COMPLETE after=$afterQuest")
                return true
            }

            if (attempt < 2) delay(1500L)
        }

        log(account.username, "[READQUEST_DIAG] SERVER_QUEST_INCREMENT_NOT_CONFIRMED before=$beforeQuest after=$lastKnownReadQuest", true)
        return false
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
                    is TaskResult.Success -> log(account.username, "BATTLE: TASK_SUCCESS elapsed=${elapsed}ms")
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
                runAdsTask(account, settings, webView)
                log(account.username, "TASK: ADS_END elapsed=${SystemClock.elapsedRealtime() - start}ms")
                fetchAndLogBalanceInfo(account, webView)
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
    ) {
        var adsDone = 0
        log(account.username, "TASK: ADS_START target_count=${settings.adsCount}")

        while (adsDone < settings.adsCount) {
            coroutineContext.ensureActive()

            val success = watchSingleAd(account, webView) { step ->
                updateStatus(
                    account,
                    "📺 Реклама ($adsDone/${settings.adsCount}): $step",
                    true,
                    "Реклама",
                    if (settings.adsCount > 0) adsDone.toFloat() / settings.adsCount else 1f
                )
            }

            if (!success) break

            adsDone++
            log(account.username, "TASK: ADS_COMPLETED_COUNT ad=$adsDone/${settings.adsCount}")
            delay(ADS_NEXT_DELAY_MS)
        }

        log(account.username, "TASK: ADS_END successCount=$adsDone")
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
                    onStep(details)
                }

                @JavascriptInterface
                fun onAdSuccess() { safeResume(true) }

                @JavascriptInterface
                fun onAdFailed(reason: String) { safeResume(false) }

                @JavascriptInterface
                fun onDailyLimit() { safeResume(false) }
            }

            try { webView.removeJavascriptInterface("AndroidAds") } catch (_: Exception) {}
            webView.addJavascriptInterface(AdsBridge(), "AndroidAds")

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url?.contains("/balance") != true) return

                    val script = """
                        (function() {
                            try {
                                var btn = document.querySelector('.wallet-panel__action.user-quest__watch-ads-btn');
                                if (!btn) {
                                    AndroidAds.onAdFailed('watch_button_not_found');
                                    return;
                                }

                                var count = parseInt(btn.getAttribute('data-count') || '0', 10);
                                if (count >= 3) {
                                    AndroidAds.onDailyLimit();
                                    return;
                                }

                                if (btn.disabled) {
                                    AndroidAds.onAdFailed('watch_button_disabled');
                                    return;
                                }

                                AndroidAds.onStateLog('WATCH_BUTTON_FOUND', 'count=' + count);
                                btn.click();
                                AndroidAds.onStateLog('WATCH_CLICKED', 'Клик по кнопке рекламы');

                                var elapsed = 0;
                                var timer = setInterval(function() {
                                    elapsed += 1000;
                                    var timerElem = document.querySelector('[data-fullscreen-element="timer"]');
                                    var finished = false;

                                    if (timerElem) {
                                        var value = parseInt((timerElem.innerText || '').replace(/\D+/g, ''), 10);
                                        if (!isNaN(value) && value <= 0) finished = true;
                                    } else if (elapsed >= 10000) {
                                        finished = true;
                                    }

                                    if (finished) {
                                        clearInterval(timer);
                                        var close = document.querySelector('[data-fullscreen-element-name="close-btn"], .close-btn');
                                        if (close) {
                                            try { close.click(); } catch(e) {}
                                        }
                                        setTimeout(function() { AndroidAds.onAdSuccess(); }, 1500);
                                    }

                                    if (elapsed >= 45000) {
                                        clearInterval(timer);
                                        AndroidAds.onAdFailed('ad_timeout');
                                    }
                                }, 1000);

                            } catch(e) {
                                AndroidAds.onAdFailed('exception=' + e.message);
                            }
                        })();
                    """.trimIndent()

                    view?.evaluateJavascript(script, null)
                }
            }

            webView.loadUrl("https://mangabuff.ru/balance")
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
        executeMiningInWebView(account, settings, webView)
        log(account.username, "MINE: COMPLETED")
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
                fun onMineProgress(clicks: Int, total: Int) {
                    updateStatus(
                        account,
                        "⛏️ Шахта ($clicks/$total)",
                        true,
                        "Шахта",
                        if (total > 0) clicks.toFloat() / total else 1f
                    )
                }

                @JavascriptInterface
                fun onMineLog(msg: String) { log(account.username, msg) }

                @JavascriptInterface
                fun onMineComplete() { safeResume(true) }
            }

            try { webView.removeJavascriptInterface("AndroidMine") } catch (_: Exception) {}
            webView.addJavascriptInterface(MineBridge(), "AndroidMine")

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    val script = """
                        (function() {
                            try {
                                var clicks = 0;
                                var hits = document.querySelector('.main-mine__game-hits-left');
                                var initialHits = hits ? parseInt(hits.innerText.trim(), 10) || 0 : 0;

                                if (initialHits <= 0) {
                                    finish();
                                    return;
                                }

                                function tap() {
                                    var current = document.querySelector('.main-mine__game-hits-left');
                                    var left = current ? parseInt(current.innerText.trim(), 10) || 0 : initialHits - clicks;
                                    var button = document.querySelector('button.main-mine__game-tap');

                                    if (button && left > 0) {
                                        button.click();
                                        clicks++;
                                        if (clicks % 5 === 0 || left <= 1) {
                                            AndroidMine.onMineProgress(clicks, initialHits);
                                        }
                                        setTimeout(tap, 1000 + Math.floor(Math.random() * 500));
                                    } else {
                                        finish();
                                    }
                                }

                                function finish() {
                                    if (${settings.mineAutoUpgrade}) {
                                        var upgrade = document.querySelector('button.mine-shop__upgrade-btn');
                                        if (upgrade) upgrade.click();
                                    }

                                    if (${settings.mineAutoExchange}) {
                                        var exchange = document.querySelector('button.mine-shop__ore-change-btn');
                                        if (exchange) exchange.click();
                                    }

                                    setTimeout(function() {
                                        AndroidMine.onMineComplete();
                                    }, $MINE_COMPLETION_DELAY_MS);
                                }

                                tap();

                            } catch(e) {
                                AndroidMine.onMineComplete();
                            }
                        })();
                    """.trimIndent()

                    view?.evaluateJavascript(script, null)
                }
            }

            webView.loadUrl("https://mangabuff.ru/mine")
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
                    // Use the baseline captured when this exact chapter opened.
                    val chapterQuestBefore = activeChapterContext?.readQuestBefore
                        ?.takeIf { it.isNotBlank() }
                        ?: lastKnownReadQuest

                    val duplicate = (chId.isNotBlank() && chId in completedChapterIds) ||
                            (chUrl.isNotBlank() && chUrl in readChapterUrlsInRun)

                    if (duplicate) {
                        log(account.username, "READER: DUPLICATE_CHAPTER_SKIPPED chapterId=$chId", true)
                        break
                    }

                    // The current chapter becomes pending. Its server quest
                    // increment is checked non-destructively while the NEXT
                    // chapter is being read.
                    if (pendingReadQuestBefore != null) {
                        val questAfter = if (pendingReadQuestConfirmedValue.isNotBlank()) {
                            pendingReadQuestConfirmedValue
                        } else {
                            lastKnownReadQuest
                        }

                        log(
                            account.username,
                            "READER: PENDING_SERVER_CHECK chapterId=$pendingReadChapterId " +
                                "questBefore=$pendingReadQuestBefore confirmed=$pendingReadQuestConfirmed " +
                                "questAfter=$questAfter"
                        )

                        if (!pendingReadQuestConfirmed) {
                            log(
                                account.username,
                                "READER: PREVIOUS_CHAPTER_NOT_SERVER_CONFIRMED " +
                                    "chapterId=$pendingReadChapterId questBefore=$pendingReadQuestBefore",
                                true
                            )
                            break
                        }

                        if (pendingReadChapterId.isNotBlank()) {
                            completedChapterIds.add(pendingReadChapterId)
                        }
                        if (pendingReadChapterUrl.isNotBlank()) {
                            readChapterUrlsInRun.add(pendingReadChapterUrl)
                        }

                        chaptersReadCount++
                        currentSessionChaptersRead = chaptersReadCount
                        updateReaderStatus(account)

                        log(
                            account.username,
                            "READER: CHAPTER_READ count=$chaptersReadCount/$target " +
                                "id=$pendingReadChapterId " +
                                "serverQuest=$pendingReadQuestBefore->$questAfter"
                        )

                        chaptersSinceComment++

                        pendingReadQuestBefore = null
                        pendingReadQuestConfirmed = false
                        pendingReadQuestConfirmedValue = ""
                        pendingReadChapterId = ""
                        pendingReadChapterUrl = ""
                    }

                    pendingReadQuestBefore = chapterQuestBefore
                    pendingReadQuestConfirmed = false
                    pendingReadQuestConfirmedValue = ""
                    pendingReadChapterId = chId
                    pendingReadChapterUrl = chUrl

                    log(
                        account.username,
                        "READER: CHAPTER_PENDING_SERVER_CONFIRM chapterId=$chId " +
                            "questBefore=$chapterQuestBefore"
                    )

                    log(
                        account.username,
                        "COMMENT: DECISION chaptersSinceComment=$chaptersSinceComment nextCommentAfter=$nextCommentAfter"
                    )

                    if (
                        account.commentEnabled &&
                        chaptersSinceComment >= nextCommentAfter &&
                        dailyCommentCount < settings.commentCount
                    ) {
                        val targetCommentUrl = activeChapterContext?.actualChapterUrl?.ifBlank { null }
                            ?: lastFinishedChapterUrl.ifBlank { null }
                            ?: currentMangaUrl.ifBlank { null }

                        if (!targetCommentUrl.isNullOrBlank()) {
                            val success = runCommentOnCurrentChapter(account, webView, targetCommentUrl)
                            if (success) {
                                dailyCommentCount++
                                chaptersSinceComment = 0
                                nextCommentAfter = (5..15).random()
                                log(
                                    account.username,
                                    "COMMENT: SUCCESS dailyCount=$dailyCommentCount/${settings.commentCount} nextThreshold=$nextCommentAfter"
                                )
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

                is ReaderResult.MangaCompleted -> {
                    log(account.username, "READER: MANGA_COMPLETED title='${result.title}'")

                    val chId = lastFinishedChapterId
                    val chUrl = lastFinishedChapterUrl
                    // The last chapter also carries its own immutable baseline.
                    val chapterQuestBefore = activeChapterContext?.readQuestBefore
                        ?.takeIf { it.isNotBlank() }
                        ?: lastKnownReadQuest
                    val duplicate = (chId.isNotBlank() && chId in completedChapterIds) ||
                            (chUrl.isNotBlank() && chUrl in readChapterUrlsInRun)

                    if (!duplicate && (chId.isNotBlank() || chUrl.isNotBlank())) {
                        // There is no following chapter to trigger the observed
                        // server-side transition, so leave the last chapter pending
                        // instead of falsely counting it.
                        pendingReadQuestBefore = chapterQuestBefore
                        pendingReadQuestConfirmed = false
                        pendingReadQuestConfirmedValue = ""
                        pendingReadChapterId = chId
                        pendingReadChapterUrl = chUrl
                        log(
                            account.username,
                            "READER: LAST_CHAPTER_LEFT_PENDING_NO_NEXT_CHAPTER " +
                                "chapterId=$chId questBefore=$chapterQuestBefore"
                        )
                    }

                    if (currentMangaUrl.isNotBlank()) {
                        skippedMangaUrls.add(currentMangaUrl)
                    }

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
                continuation.resume(result)
            }
        }

        continuation.invokeOnCancellation {
            mainHandler.post {
                try { webView.stopLoading() } catch (_: Exception) {}
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
                    log(account.username, "READER: MB_HISTORY_POST_FINISHED status=$status url=$url")
                    log(account.username, "[READQUEST_DIAG] POST_RESULT status=$status")
                    if (ok) {
                        log(account.username, "[READQUEST_DIAG] POST_HTTP_SUCCESS status=$status")
                    } else {
                        log(account.username, "[READQUEST_DIAG] POST_HTTP_FAILED status=$status", true)
                    }
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
                    isRealLastChapter: Boolean
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

                    log(account.username, "READ: CHAPTER_READER_FINISHED elapsed=${elapsedMs}ms")
                    log(account.username, "READ: CHAPTER_END_REACHED chapter=$number")
                    log(account.username, "READER: COMPLETION_ACCEPTED chapterId=$chapterId")

                    if (isRealLastChapter) {
                        log(account.username, "READER: LAST_CHAPTER_CONFIRMED")
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
                            safeResume(
                                ReaderResult.ChapterRead(
                                    giftsFound,
                                    chapterUrl,
                                    chapterId,
                                    nextChapterUrl
                                )
                            )
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
                                            var currChId = (window.current_chapter && window.current_chapter.chapter_id) ? String(window.current_chapter.chapter_id) : '';
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

                                    var startMs = Date.now();
                                    var chapterDone = false;
                                    var bottomStarted = 0;
                                    var lastProgress = -1;
                                    var unknownFinalStart = 0;
                                    var lastObservedScrollY = -1;
                                    var stagnantChecks = 0;
                                    var completionScheduled = false;

                                    window.__mbHistoryPostStarted = false;
                                    window.__mbHistoryPostStatus = null;

                                    var chapterId = '';
                                    if (window.current_chapter && window.current_chapter.chapter_id) {
                                        chapterId = String(window.current_chapter.chapter_id);
                                    }

                                    AndroidReaderBridge.onLogStep('READER: CHAPTER_ID=' + chapterId);
                                    AndroidReaderBridge.onLogStep('READER: CHAPTER_TIMER_STARTED chapterId=' + chapterId);
                                    try {
                                        var diagScrollingElement = document.scrollingElement || document.documentElement || document.body;
                                        AndroidReaderBridge.onLogStep(
                                            'READER: VIEWPORT_DIAG width=' + (window.innerWidth || 0) +
                                            ' height=' + (window.innerHeight || 0) +
                                            ' clientHeight=' + (diagScrollingElement ? (diagScrollingElement.clientHeight || 0) : 0) +
                                            ' scrollHeight=' + (diagScrollingElement ? (diagScrollingElement.scrollHeight || 0) : 0)
                                        );
                                    } catch(e) {}
                                    AndroidReaderBridge.onLogStep('READER: SCROLL_MODE=NATIVE_TOUCH_SWIPE');

                                    if (window.current_chapter) {
                                        var c = window.current_chapter;
                                        AndroidReaderBridge.onCurrentChapterData(
                                            String(c.id || ''),
                                            String(c.chapter_id || ''),
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
                                        }
                                    }

                                    function logPreNextChapterState() {
                                        try {
                                            var pool = [];
                                            try { pool = JSON.parse(localStorage.getItem('history_pool') || '[]'); } catch(e) {}

                                            var currentChId = (window.current_chapter && window.current_chapter.chapter_id)
                                                ? String(window.current_chapter.chapter_id)
                                                : chapterId;

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

                                    function finish(nextElement, isLast) {
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
                                            isLast
                                        );
                                    }

                                    function finishWithCandidateUrl(candidateUrl) {
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
                                            false
                                        );
                                    }

                                    function checkMangaBuffReadState() {
                                        var pool = [];
                                        try {
                                            pool = JSON.parse(localStorage.getItem('history_pool') || '[]');
                                        } catch (e) {}

                                        var currentChId = (window.current_chapter && window.current_chapter.chapter_id)
                                            ? String(window.current_chapter.chapter_id)
                                            : chapterId;

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
                                            AndroidReaderBridge.onLogStep('READER: MB_READ_CONFIRMED chapterId=' + state.chapterId);
                                            onSuccess();
                                            return;
                                        }

                                        if (!state.isRead) {
                                            try {
                                                window.dispatchEvent(new Event('scroll'));
                                            } catch(e) {}
                                        }

                                        if (confirmAttempt >= maxConfirmAttempts) {
                                            AndroidReaderBridge.onLogStep('READER: MB_READ_CONFIRM_TIMEOUT chapterId=' + state.chapterId);
                                            chapterDone = true;
                                            AndroidReaderBridge.onNextChapterUnknown();
                                            return;
                                        }

                                        AndroidReaderBridge.onLogStep('READER: MB_READ_WAIT attempt=' + confirmAttempt + ' chapterId=' + state.chapterId);
                                        setTimeout(function() {
                                            waitForMangaBuffConfirmation(onSuccess);
                                        }, 350);
                                    }

                                    function checkEnd() {
                                        if (chapterDone) return;

                                        var scrollingElement = document.scrollingElement || document.documentElement || document.body;
                                        var rawY = Math.max(
                                            window.scrollY || 0,
                                            window.pageYOffset || 0,
                                            scrollingElement ? (scrollingElement.scrollTop || 0) : 0,
                                            document.documentElement ? (document.documentElement.scrollTop || 0) : 0,
                                            document.body ? (document.body.scrollTop || 0) : 0
                                        );
                                        var rawViewport = Math.max(
                                            window.innerHeight || 0,
                                            scrollingElement ? (scrollingElement.clientHeight || 0) : 0,
                                            document.documentElement ? (document.documentElement.clientHeight || 0) : 0,
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

                                        // Probe /balance without navigating away from the reader.
                                        if (percent >= 55 && percent < 95) {
                                            if (!window.__mbPendingQuestProbeDone) {
                                                window.__mbPendingQuestProbeDone = {};
                                            }

                                            var probeKey = String(Math.floor(percent / 5) * 5);
                                            if (!window.__mbPendingQuestProbeDone[probeKey]) {
                                                window.__mbPendingQuestProbeDone[probeKey] = true;

                                                AndroidReaderBridge.onLogStep(
                                                    'READER: SERVER_QUEST_PROBE_START progress=' + percent + '%'
                                                );

                                                fetch('/balance', {
                                                    method: 'GET',
                                                    credentials: 'include',
                                                    cache: 'no-store'
                                                }).then(function(response) {
                                                    return response.text();
                                                }).then(function(html) {
                                                    try {
                                                        var parser = new DOMParser();
                                                        var doc = parser.parseFromString(html, 'text/html');
                                                        var wallet = doc.querySelector('.wallet-panel');
                                                        var quest = '';

                                                        if (wallet) {
                                                            var heads = Array.from(
                                                                wallet.querySelectorAll('.wallet-panel__stat-head')
                                                            );

                                                            heads.forEach(function(head) {
                                                                var span = head.querySelector('span');
                                                                var b = head.querySelector('b');
                                                                var label = (
                                                                    span ? (span.textContent || '') : (head.textContent || '')
                                                                ).replace(/\s+/g, ' ').trim().toLowerCase();
                                                                var value = b
                                                                    ? (b.textContent || '').replace(/\s+/g, ' ').trim()
                                                                    : '';

                                                                if (
                                                                    !quest &&
                                                                    (label.indexOf('глав') !== -1 || label.indexOf('чита') !== -1) &&
                                                                    value
                                                                ) {
                                                                    quest = value;
                                                                }

                                                                if (!quest) {
                                                                    var full = (head.textContent || '')
                                                                        .replace(/\s+/g, ' ').trim();
                                                                    var m = full.match(/(\d+)\s*\/\s*(\d+)/);
                                                                    if (
                                                                        m &&
                                                                        (full.toLowerCase().indexOf('глав') !== -1 ||
                                                                         full.toLowerCase().indexOf('чита') !== -1)
                                                                    ) {
                                                                        quest = m[1] + '/' + m[2];
                                                                    }
                                                                }
                                                            });
                                                        }

                                                        if (!quest) {
                                                            var bodyText = (doc.body && doc.body.innerText) || '';
                                                            var matches = bodyText.match(
                                                                /(?:глав|чита)[^\d]{0,80}(\d+)\s*\/\s*(\d+)/i
                                                            );
                                                            if (matches) quest = matches[1] + '/' + matches[2];
                                                        }

                                                        if (quest) {
                                                            AndroidReaderBridge.onServerReadQuestProbe(quest, percent);
                                                        } else {
                                                            AndroidReaderBridge.onLogStep(
                                                                'READER: SERVER_QUEST_PROBE_NO_VALUE progress=' +
                                                                    percent + '%'
                                                            );
                                                        }
                                                    } catch (e) {
                                                        AndroidReaderBridge.onLogStep(
                                                            'READER: SERVER_QUEST_PROBE_PARSE_ERROR error=' +
                                                                (e && e.message ? e.message : String(e))
                                                        );
                                                    }
                                                }).catch(function(e) {
                                                    AndroidReaderBridge.onLogStep(
                                                        'READER: SERVER_QUEST_PROBE_FETCH_ERROR error=' +
                                                            (e && e.message ? e.message : String(e))
                                                    );
                                                });
                                            }
                                        }

                                        var maxScrollTop = Math.max(0, metrics.height - metrics.viewport);
                                        var distance = Math.max(0, maxScrollTop - metrics.y);
                                        var atAbsoluteBottom = distance <= 8;
                                        var touchStalled = stagnantChecks >= 4;

                                        if (distance > 200 && !atAbsoluteBottom && !touchStalled) {
                                            bottomStarted = 0;
                                            unknownFinalStart = 0;
                                            return;
                                        }

                                        if (touchStalled && !atAbsoluteBottom) {
                                            AndroidReaderBridge.onLogStep(
                                                'READER: SCROLL_TOUCH_STALLED y=' + Math.floor(metrics.y) +
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

                                        if (isWaitingConfirmation || completionScheduled) return;
                                        isWaitingConfirmation = true;
                                        completionScheduled = true;

                                        AndroidReaderBridge.onLogStep('READER: BOTTOM_STABILIZATION_CHECK');
                                        AndroidReaderBridge.onLogStep('READER: BOTTOM_STABLE elapsed=' + stable);
                                        AndroidReaderBridge.onLogStep('READER: FINAL_UI_CHECK');
                                        AndroidReaderBridge.onLogStep('NEXT_CHAPTER_DOM_SCAN');

                                        // IMPORTANT: local is_read/history_pool/addHistory signals are only
                                        // hints. The authoritative acceptance remains the /balance quest
                                        // increment checked on the native side.
                                        var stateAtBottom = checkMangaBuffReadState();
                                        logReadState(stateAtBottom);
                                        AndroidReaderBridge.onLogStep(
                                            'READER: SERVER_BALANCE_WILL_VERIFY_LOCAL_STATE isRead=' +
                                            stateAtBottom.isRead +
                                            ' historyContainsCurrent=' +
                                            stateAtBottom.containsCurrent +
                                            ' postStatus=' + stateAtBottom.postStatus
                                        );

                                        // Give MangaBuff a short grace period to finish its own
                                        // addHistory/read-state update before the native /balance check.
                                        setTimeout(function() {
                                            var nextAfterSettle = findNextChapter();
                                            var isLastAfterSettle = isLastChapter();
                                            var candidateAfterSettle =
                                                (!nextAfterSettle && !isLastAfterSettle)
                                                    ? buildCandidateNextUrl()
                                                    : '';

                                            if (nextAfterSettle) {
                                                var nextHref = getHref(nextAfterSettle);
                                                AndroidReaderBridge.onLogStep('NEXT_CHAPTER_FOUND_DOM url=' + nextHref);
                                                chapterDone = true;
                                                finish(nextAfterSettle, false);
                                            } else if (isLastAfterSettle) {
                                                AndroidReaderBridge.onLogStep('FINAL_UI_DETECTED type=NOTIFY_NEW_CHAPTER');
                                                AndroidReaderBridge.onLogStep('LAST_CHAPTER_CONFIRMED');
                                                chapterDone = true;
                                                finish(null, true);
                                            } else if (candidateAfterSettle) {
                                                AndroidReaderBridge.onLogStep(
                                                    'NEXT_CHAPTER_URL_FALLBACK current=' + window.location.href +
                                                    ' candidate=' + candidateAfterSettle
                                                );
                                                chapterDone = true;
                                                finishWithCandidateUrl(candidateAfterSettle);
                                            } else {
                                                chapterDone = true;
                                                AndroidReaderBridge.onNextChapterUnknown();
                                            }
                                        }, 1200);
                                    }

                                    function humanScroll() {
                                        if (chapterDone) return;

                                        var current = window.scrollY || window.pageYOffset || 0;
                                        var viewport = window.innerHeight || 1;
                                        var width = window.innerWidth || 1;
                                        var height = Math.max(document.documentElement.scrollHeight || 0, document.body.scrollHeight || 0);
                                        var remaining = Math.max(0, height - (current + viewport));

                                        if (remaining <= 180) {
                                            checkEnd();
                                            return;
                                        }

                                        // Use a native WebView touch swipe so MangaBuff receives the
                                        // normal touch/scroll pipeline instead of only window.scrollTo().
                                        var centerX = Math.max(48, Math.min(width - 48, width / 2));
                                        var startY = Math.max(80, viewport * 0.78);
                                        var endY = Math.max(48, viewport * 0.22);
                                        var duration = 800 + Math.floor(Math.random() * 450);
                                        var before = Math.floor(current);

                                        AndroidReaderBridge.onLogStep(
                                            'READER: NATIVE_TOUCH_SWIPE from=' + before +
                                            ' remaining=' + Math.floor(remaining) +
                                            ' duration=' + duration
                                        );

                                        AndroidReaderBridge.nativeSwipe(
                                            centerX,
                                            startY,
                                            centerX,
                                            endY,
                                            duration
                                        );

                                        window.__mbScrollTimer = setTimeout(
                                            humanScroll,
                                            duration + 650 + Math.floor(Math.random() * 450)
                                        );
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