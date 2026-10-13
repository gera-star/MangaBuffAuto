package com.example.myapplication.automation

import android.content.Context
import android.net.Uri
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.random.Random

enum class ReaderScrollMode {
    NATIVE_MANGABUFF,
    BOT_HUMAN
}

sealed class TaskResult {
    data object Success : TaskResult()
    data object BattleWon : TaskResult()
    data object BattleLost : TaskResult()
    data object Skipped : TaskResult()
    data object Cancelled : TaskResult()
    data class Failed(val reason: String) : TaskResult()
    data class Timeout(val reason: String) : TaskResult()
}

private enum class AdWatchResult { Rewarded, DailyLimit, Failed }

sealed interface ReaderResult {
    data class ChapterRead(
        val gifts: Int,
        val chapterUrl: String,
        val chapterId: String,
        val nextChapterUrl: String = "",
        val terminal: Boolean = false
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
    private val onDailyStatsUpdate: (accountId: String, stats: DailyStats) -> Unit = { _, _ -> },
    private val onTowerExpeditionEndsAtUpdate: (accountId: String, endsAtMillis: Long) -> Unit = { _, _ -> },
    private val onRendererGone: (accountId: String, webView: WebView) -> Unit = { _, _ -> },
    private val onMineBalanceUpdate: (accountId: String, oreBalance: Int) -> Unit = { _, _ -> }
) {

    private open inner class AutomationWebViewClient(
        private val accountUsername: String,
        private val clientWebView: WebView
    ) : WebViewClient() {

        override fun onRenderProcessGone(
            view: WebView?,
            detail: android.webkit.RenderProcessGoneDetail?
        ): Boolean {
            val target = view ?: clientWebView
            log(
                accountUsername,
                "WEBVIEW: TASK_CLIENT_RENDERER_GONE didCrash=${detail?.didCrash()} instance=${target.hashCode()}",
                true
            )
            onRendererGone(accountUsername, target)
            return true
        }
    }

    companion object {
        private const val READER_END_STABLE_MS = 3_000L
        private const val BACKGROUND_SCROLL_SAMPLE_INTERVAL_MS = 2_000L
        private const val BACKGROUND_SCROLL_METRICS_TIMEOUT_MS = 4_500L
        private const val BACKGROUND_SCROLL_STALL_MS = 8_000L
        private const val BACKGROUND_SCROLL_DIAGNOSTIC_INTERVAL_MS = 15_000L
        private const val BACKGROUND_CONFIRMATION_TICK_INTERVAL_MS = 500L
        private const val BACKGROUND_CONFIRMATION_TICK_TIMEOUT_MS = 4_500L
        private const val BACKGROUND_SCROLL_METRICS_JS = """
            (function() {
                var m = null;
                try {
                    if (typeof window.__mbGetReaderScrollMetrics === 'function') {
                        m = window.__mbGetReaderScrollMetrics(false);
                    }
                } catch (e) {}
                var s = document.scrollingElement || document.documentElement || document.body;
                var v = Math.max(
                    m && m.viewport ? m.viewport : 0,
                    window.visualViewport ? window.visualViewport.height : 0,
                    window.innerHeight || 0,
                    s ? (s.clientHeight || 0) : 0,
                    Number(window.__mbNativeViewportHeight) || 0,
                    1
                );
                var h = m && m.height ? m.height : Math.max(
                    s ? (s.scrollHeight || 0) : 0,
                    document.documentElement ? (document.documentElement.scrollHeight || 0) : 0,
                    document.body ? (document.body.scrollHeight || 0) : 0
                );
                var y = m && typeof m.y === 'number' ? m.y : Math.max(
                    window.scrollY || 0,
                    window.pageYOffset || 0,
                    s ? (s.scrollTop || 0) : 0,
                    document.documentElement ? (document.documentElement.scrollTop || 0) : 0,
                    document.body ? (document.body.scrollTop || 0) : 0
                );
                return [
                    window.__mbFastScrollRunning ? 1 : 0,
                    Math.round(y),
                    Math.round(h),
                    Math.round(v)
                ].join(',');
            })()
        """


        private const val ADS_NEXT_DELAY_MS = 4000L
        private const val ADS_DAILY_LIMIT = 3
        private const val ADS_DAILY_LIMIT_MESSAGE = "Нельзя смотреть рекламу больше 3 раз в сутки"
        private const val MINE_COMPLETION_DELAY_MS = 2000L

        private const val COMMENT_DELAY_MS = 4000L
        private const val COMMENT_DAILY_LIMIT = 13
        private const val READER_MAX_CHAPTERS = 10

        private const val DECK_PAGE_SETTLE_MS = 2000L

        private const val BATTLE_COOLDOWN_MS = 2000L
        private const val BATTLE_WIN_TARGET = 2

        private const val BALANCE_WATCHDOG_MS = 15_000L
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val accountIoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun closeRuntime() {
        accountIoScope.coroutineContext[Job]?.cancel()
        stopBackgroundScroll()
        activeReaderSkip = null
        activeReaderMarkRead = null
        mainHandler.removeCallbacksAndMessages(null)
    }

    /*
     * Background reader state is local to THIS MangaBuffAutomation instance.
     * AutomationRuntime creates one engine per accountId, so this state cannot
     * be shared between accounts.
     */
    @Volatile
    private var backgroundScrollActive = false
    private var backgroundScrollRunnable: Runnable? = null
    private var backgroundScrollWebView: WebView? = null
    private var backgroundScreenWasOff = false
    private var backgroundScrollGeneration = 0L
    private var backgroundMetricsRequestId = 0L
    private var backgroundMetricsRequestInFlight = false
    private var backgroundMetricsRequestAt = 0L
    private var backgroundLastMetricsRequestAt = 0L
    private var backgroundLastScrollY: Long? = null
    private var backgroundLastProgressAt = 0L
    private var backgroundLastDiagnosticAt = 0L
    private var backgroundObservedUrl: String? = null
    private var backgroundFastScrollRunning = false
    private var backgroundLastFallbackStepAt = 0L
    private var backgroundLastFallbackLogAt = 0L
    private var backgroundScrollHeight = 0L
    private var backgroundViewportHeight = 0L
    private var backgroundRemaining = Long.MAX_VALUE
    private var backgroundLastConfirmationTickAt = 0L
    private var backgroundConfirmationTickAt = 0L
    private var backgroundLastConfirmationTimeoutLogAt = 0L
    private var backgroundConfirmationTickInFlight = false
    private var backgroundConfirmationTickRequestId = 0L

    private fun startBackgroundScroll(accountUsername: String, webView: WebView) {
        mainHandler.post {
            if (backgroundScrollActive && backgroundScrollWebView === webView) return@post

            stopBackgroundScroll()
            val generation = backgroundScrollGeneration
            val now = SystemClock.elapsedRealtime()
            backgroundScrollActive = true
            backgroundScrollWebView = webView
            backgroundScreenWasOff = false
            backgroundMetricsRequestInFlight = false
            backgroundMetricsRequestAt = 0L
            backgroundLastMetricsRequestAt = 0L
            backgroundLastScrollY = null
            backgroundLastProgressAt = now
            backgroundLastDiagnosticAt = 0L
            backgroundObservedUrl = webView.url.orEmpty()
            backgroundFastScrollRunning = false
            backgroundLastFallbackStepAt = 0L
            backgroundLastFallbackLogAt = 0L
            backgroundScrollHeight = 0L
            backgroundViewportHeight = 0L
            backgroundRemaining = Long.MAX_VALUE
            backgroundLastConfirmationTickAt = 0L
            backgroundConfirmationTickAt = 0L
            backgroundLastConfirmationTimeoutLogAt = 0L
            backgroundConfirmationTickInFlight = false

            val runnable = object : Runnable {
                override fun run() {
                    val target = backgroundScrollWebView
                    if (
                        !backgroundScrollActive ||
                        target == null ||
                        target !== webView ||
                        generation != backgroundScrollGeneration
                    ) return

                    val screenOff = BackgroundExecutionState.isScreenOff()
                    if (screenOff != backgroundScreenWasOff) {
                        backgroundScreenWasOff = screenOff
                        backgroundLastProgressAt = SystemClock.elapsedRealtime()
                        backgroundLastMetricsRequestAt = 0L
                        log(accountUsername, "READER: BACKGROUND_SCROLL_STATE screenOff=$screenOff")
                    }

                    if (screenOff) {
                        // A detached WebView can report a 0px/1px DOM viewport even while
                        // its native view has a valid measured size. Pass the native CSS
                        // viewport to the reader before every locked-screen JS scroll tick.
                        try {
                            val density = target.resources.displayMetrics.density.coerceAtLeast(0.1f)
                            val metrics = target.resources.displayMetrics
                            val nativeWidthPx = target.width.takeIf { it > 0 } ?: metrics.widthPixels
                            val nativeHeightPx = target.height.takeIf { it > 0 } ?: metrics.heightPixels
                            val nativeWidthCss = (nativeWidthPx / density).toInt().coerceAtLeast(1)
                            val nativeHeightCss = (nativeHeightPx / density).toInt().coerceAtLeast(1)
                            val viewportBootstrap =
                                "window.__mbNativeViewportWidth=$nativeWidthCss;" +
                                    "window.__mbNativeViewportHeight=$nativeHeightCss;"
                            target.evaluateJavascript(
                                viewportBootstrap + "window.__mbBackgroundStep && window.__mbBackgroundStep();",
                                null
                            )
                        } catch (e: Exception) {
                            log(
                                accountUsername,
                                "READER: BACKGROUND_SCROLL_EVAL_ERROR error=${e.message}",
                                true
                            )
                        }
                    }

                    // Monitor minimized/hidden WebViews too, but do not double-scroll them every 180 ms.
                    val backgroundMode = screenOff || !target.hasWindowFocus() || !target.isShown
                    if (backgroundMode) {
                        val tickNow = SystemClock.elapsedRealtime()

                        // Hidden WebViews may throttle requestAnimationFrame. Nudge only after
                        // observed progress has stopped, and throttle the nudge to avoid double scroll.
                        val fallbackIdleMs = tickNow - backgroundLastProgressAt
                        val fallbackRemainingThreshold = maxOf(120L, backgroundViewportHeight / 4L)
                        if (
                            !screenOff &&
                            backgroundFastScrollRunning &&
                            backgroundRemaining > fallbackRemainingThreshold &&
                            fallbackIdleMs >= (BACKGROUND_SCROLL_SAMPLE_INTERVAL_MS + 500L) &&
                            tickNow - backgroundLastFallbackStepAt >= 650L
                        ) {
                            backgroundLastFallbackStepAt = tickNow
                            try {
                                target.evaluateJavascript(
                                    "window.__mbBackgroundStep && window.__mbBackgroundStep();",
                                    null
                                )
                                if (
                                    backgroundLastFallbackLogAt == 0L ||
                                    tickNow - backgroundLastFallbackLogAt >= BACKGROUND_SCROLL_DIAGNOSTIC_INTERVAL_MS
                                ) {
                                    backgroundLastFallbackLogAt = tickNow
                                    log(
                                        accountUsername,
                                        "READER: BACKGROUND_SCROLL_FALLBACK_STEP source=HIDDEN_WEBVIEW idleMs=$fallbackIdleMs remaining=$backgroundRemaining"
                                    )
                                }
                            } catch (e: Exception) {
                                log(accountUsername, "READER: BACKGROUND_SCROLL_FALLBACK_ERROR error=${e.message}", true)
                            }
                        }

                        if (
                            backgroundConfirmationTickInFlight &&
                            tickNow - backgroundConfirmationTickAt >= BACKGROUND_CONFIRMATION_TICK_TIMEOUT_MS
                        ) {
                            val timedOutMs = tickNow - backgroundConfirmationTickAt
                            // A lost evaluateJavascript callback must not permanently block this
                            // account's confirmation pump. Invalidate the stale callback before retrying.
                            backgroundConfirmationTickInFlight = false
                            backgroundConfirmationTickAt = 0L
                            backgroundConfirmationTickRequestId += 1L
                            if (
                                backgroundLastConfirmationTimeoutLogAt == 0L ||
                                tickNow - backgroundLastConfirmationTimeoutLogAt >= BACKGROUND_SCROLL_DIAGNOSTIC_INTERVAL_MS
                            ) {
                                backgroundLastConfirmationTimeoutLogAt = tickNow
                                log(
                                    accountUsername,
                                    "READER: BACKGROUND_CONFIRMATION_TICK_TIMEOUT elapsed=${timedOutMs}ms url=${target.url.orEmpty()}",
                                    true
                                )
                            }
                        }
                        if (
                            !backgroundConfirmationTickInFlight &&
                            tickNow - backgroundLastConfirmationTickAt >= BACKGROUND_CONFIRMATION_TICK_INTERVAL_MS
                        ) {
                            backgroundLastConfirmationTickAt = tickNow
                            backgroundConfirmationTickAt = tickNow
                            backgroundConfirmationTickInFlight = true
                            val requestId = ++backgroundConfirmationTickRequestId
                            try {
                                // Keep at most one native continuation evaluation in flight for this account's WebView.
                                target.evaluateJavascript(
                                    "window.__mbNativeConfirmationTick && window.__mbNativeConfirmationTick();"
                                ) { _ ->
                                    if (
                                        backgroundScrollActive &&
                                        backgroundScrollWebView === target &&
                                        generation == backgroundScrollGeneration &&
                                        requestId == backgroundConfirmationTickRequestId
                                    ) {
                                        backgroundConfirmationTickInFlight = false
                                        backgroundConfirmationTickAt = 0L
                                    }
                                }
                            } catch (e: Exception) {
                                if (
                                    backgroundScrollActive &&
                                    backgroundScrollWebView === target &&
                                    generation == backgroundScrollGeneration &&
                                    requestId == backgroundConfirmationTickRequestId
                                ) {
                                    backgroundConfirmationTickInFlight = false
                                    backgroundConfirmationTickAt = 0L
                                }
                                log(
                                    accountUsername,
                                    "READER: BACKGROUND_CONFIRMATION_TICK_ERROR error=${e.message}",
                                    true
                                )
                            }
                        }

                        if (
                            backgroundMetricsRequestInFlight &&
                            tickNow - backgroundMetricsRequestAt >= BACKGROUND_SCROLL_METRICS_TIMEOUT_MS
                        ) {
                            backgroundMetricsRequestInFlight = false
                            backgroundMetricsRequestId += 1L
                            backgroundLastMetricsRequestAt = tickNow - BACKGROUND_SCROLL_SAMPLE_INTERVAL_MS
                            log(
                                accountUsername,
                                "READER: BACKGROUND_SCROLL_METRICS_TIMEOUT elapsed=" +
                                    (tickNow - backgroundMetricsRequestAt) + "ms url=${target.url.orEmpty()}",
                                true
                            )
                        }

                        if (
                            !backgroundMetricsRequestInFlight &&
                            tickNow - backgroundLastMetricsRequestAt >= BACKGROUND_SCROLL_SAMPLE_INTERVAL_MS
                        ) {
                            backgroundLastMetricsRequestAt = tickNow
                            backgroundMetricsRequestAt = tickNow
                            backgroundMetricsRequestInFlight = true
                            val requestId = ++backgroundMetricsRequestId
                            try {
                                target.evaluateJavascript(BACKGROUND_SCROLL_METRICS_JS) { result ->
                                    handleBackgroundScrollMetrics(
                                        accountUsername = accountUsername,
                                        webView = target,
                                        generation = generation,
                                        requestId = requestId,
                                        result = result
                                    )
                                }
                            } catch (e: Exception) {
                                if (
                                    backgroundScrollActive &&
                                    backgroundScrollWebView === target &&
                                    generation == backgroundScrollGeneration &&
                                    requestId == backgroundMetricsRequestId
                                ) {
                                    backgroundMetricsRequestInFlight = false
                                    log(
                                        accountUsername,
                                        "READER: BACKGROUND_SCROLL_EVAL_ERROR error=${e.message}",
                                        true
                                    )
                                }
                            }
                        }

                        val idleMs = tickNow - backgroundLastProgressAt
                        if (backgroundFastScrollRunning && idleMs >= BACKGROUND_SCROLL_STALL_MS) {
                            val recoveryThreshold = maxOf(120L, backgroundViewportHeight / 4L)
                            if (backgroundRemaining > recoveryThreshold) {
                                log(
                                    accountUsername,
                                    "READER: BACKGROUND_SCROLL_STALLED idleMs=${idleMs} " +
                                        "y=${backgroundLastScrollY ?: -1L} height=${backgroundScrollHeight} " +
                                        "viewport=${backgroundViewportHeight} remaining=${backgroundRemaining} " +
                                        "screenOff=${screenOff} windowFocus=${target.hasWindowFocus()} shown=${target.isShown} recovery=${if (screenOff) "BOUNDED_NATIVE_SWIPE" else "JS_BACKGROUND_TICK"}",
                                    true
                                )
                                backgroundLastProgressAt = tickNow
                                if (screenOff) {
                                    dispatchBackgroundRecoverySwipe(accountUsername, target, generation)
                                } else {
                                    try {
                                        target.evaluateJavascript(
                                            "window.__mbBackgroundStep && window.__mbBackgroundStep();",
                                            null
                                        )
                                    } catch (e: Exception) {
                                        log(
                                            accountUsername,
                                            "READER: BACKGROUND_SCROLL_RECOVERY_EVAL_ERROR error=${e.message}",
                                            true
                                        )
                                    }
                                }
                            } else if (tickNow - backgroundLastDiagnosticAt >= BACKGROUND_SCROLL_DIAGNOSTIC_INTERVAL_MS) {
                                backgroundLastDiagnosticAt = tickNow
                                log(
                                    accountUsername,
                                    "READER: BACKGROUND_SCROLL_STALLED_NEAR_BOTTOM idleMs=${idleMs} " +
                                        "y=${backgroundLastScrollY ?: -1L} height=${backgroundScrollHeight} " +
                                        "viewport=${backgroundViewportHeight} remaining=${backgroundRemaining} " +
                                        "recovery=SKIPPED"
                                )
                            }
                        }
                    }

                    if (backgroundScrollActive && generation == backgroundScrollGeneration) {
                        mainHandler.postDelayed(this, 180L)
                    }
                }
            }

            backgroundScrollRunnable = runnable
            log(accountUsername, "READER: BACKGROUND_SCROLL_NATIVE_STARTED")
            runnable.run()
        }
    }

    private fun handleBackgroundScrollMetrics(
        accountUsername: String,
        webView: WebView,
        generation: Long,
        requestId: Long,
        result: String?
    ) {
        if (
            !backgroundScrollActive ||
            backgroundScrollWebView !== webView ||
            generation != backgroundScrollGeneration ||
            requestId != backgroundMetricsRequestId
        ) return

        backgroundMetricsRequestInFlight = false
        val values = result?.trim()?.removeSurrounding("\"")?.split(',')
        if (values == null || values.size != 4) {
            val now = SystemClock.elapsedRealtime()
            if (now - backgroundLastDiagnosticAt >= BACKGROUND_SCROLL_DIAGNOSTIC_INTERVAL_MS) {
                backgroundLastDiagnosticAt = now
                log(
                    accountUsername,
                    "READER: BACKGROUND_SCROLL_METRICS_INVALID result=${result ?: "null"}",
                    true
                )
            }
            return
        }

        val isRunning = values[0].toIntOrNull() == 1
        val y = values[1].toLongOrNull() ?: return
        val height = values[2].toLongOrNull() ?: return
        val viewport = values[3].toLongOrNull()?.coerceAtLeast(1L) ?: return
        val now = SystemClock.elapsedRealtime()
        val currentUrl = webView.url.orEmpty()

        if (currentUrl != backgroundObservedUrl) {
            val previousUrl = backgroundObservedUrl.orEmpty()
            backgroundObservedUrl = currentUrl
            backgroundLastScrollY = y
            backgroundLastProgressAt = now
            backgroundLastFallbackStepAt = 0L
            backgroundLastFallbackLogAt = 0L
            backgroundFastScrollRunning = isRunning
            backgroundScrollHeight = height
            backgroundViewportHeight = viewport
            backgroundRemaining = (height - viewport - y).coerceAtLeast(0L)
            log(
                accountUsername,
                "READER: BACKGROUND_SCROLL_PAGE_CHANGED from=$previousUrl to=$currentUrl " +
                    "y=$y height=$height running=$isRunning"
            )
            return
        }

        backgroundFastScrollRunning = isRunning
        backgroundScrollHeight = height
        backgroundViewportHeight = viewport
        backgroundRemaining = (height - viewport - y).coerceAtLeast(0L)

        if (!isRunning) {
            backgroundLastScrollY = y
            backgroundLastProgressAt = now
            return
        }

        val previousY = backgroundLastScrollY
        if (previousY == null || y > previousY + 2L || y < previousY - 2L) {
            backgroundLastScrollY = y
            backgroundLastProgressAt = now
        } else {
            backgroundLastScrollY = y
        }

        if (now - backgroundLastDiagnosticAt >= BACKGROUND_SCROLL_DIAGNOSTIC_INTERVAL_MS) {
            backgroundLastDiagnosticAt = now
            log(
                accountUsername,
                "READER: BACKGROUND_SCROLL_WATCHDOG y=$y height=$height viewport=$viewport " +
                    "remaining=${backgroundRemaining} idleMs=${now - backgroundLastProgressAt} " +
                    "screenOff=${BackgroundExecutionState.isScreenOff()} windowFocus=${webView.hasWindowFocus()} shown=${webView.isShown} jsRunning=$isRunning"
            )
        }
    }

    private fun dispatchBackgroundRecoverySwipe(
        accountUsername: String,
        webView: WebView,
        generation: Long
    ) {
        if (
            !backgroundScrollActive ||
            backgroundScrollWebView !== webView ||
            generation != backgroundScrollGeneration
        ) return

        if (!webView.isAttachedToWindow || webView.width <= 0 || webView.height <= 0) {
            log(
                accountUsername,
                "READER: BACKGROUND_SCROLL_RECOVERY_SKIPPED reason=webview_not_attached",
                true
            )
            return
        }

        val density = webView.resources.displayMetrics.density.coerceAtLeast(1f)
        val cssWidth = webView.width / density
        val cssHeight = webView.height / density
        log(
            accountUsername,
            "READER: BACKGROUND_SCROLL_RECOVERY_GESTURE webView=${webView.hashCode()} " +
                "generation=$generation width=${webView.width} height=${webView.height} density=$density"
        )
        dispatchNativeSwipe(
            webView = webView,
            x1 = cssWidth * 0.5f,
            y1 = cssHeight * 0.74f,
            x2 = cssWidth * 0.5f,
            y2 = cssHeight * 0.52f,
            durationMs = 375L,
            triggerFling = false
        )
    }

    private fun stopBackgroundScroll(expectedWebView: WebView? = null) {
        if (expectedWebView != null && backgroundScrollWebView !== expectedWebView) return

        backgroundScrollGeneration += 1L
        backgroundMetricsRequestId += 1L
        backgroundMetricsRequestInFlight = false
        backgroundScrollActive = false
        backgroundScrollRunnable?.let(mainHandler::removeCallbacks)
        backgroundScrollRunnable = null
        backgroundScrollWebView = null
        backgroundScreenWasOff = false
        backgroundLastMetricsRequestAt = 0L
        backgroundLastScrollY = null
        backgroundLastProgressAt = 0L
        backgroundLastDiagnosticAt = 0L
        backgroundObservedUrl = null
        backgroundFastScrollRunning = false
        backgroundLastFallbackStepAt = 0L
        backgroundLastFallbackLogAt = 0L
        backgroundScrollHeight = 0L
        backgroundViewportHeight = 0L
        backgroundRemaining = Long.MAX_VALUE
        backgroundLastConfirmationTickAt = 0L
        backgroundConfirmationTickAt = 0L
        backgroundLastConfirmationTimeoutLogAt = 0L
        backgroundConfirmationTickInFlight = false
        backgroundConfirmationTickRequestId += 1L
    }

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

    /**
     * Dispatches a real local WebView tap using physical pixels.
     * AccessibilityNodeInfo bounds are screen coordinates in physical pixels,
     * so this helper intentionally does NOT apply WebView density conversion.
     */
    private fun dispatchNativeLocalTap(
        accountUsername: String,
        webView: WebView,
        localX: Float,
        localY: Float
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post {
                dispatchNativeLocalTap(accountUsername, webView, localX, localY)
            }
            return
        }

        if (!webView.isAttachedToWindow) {
            log(accountUsername, "ADS: ACCESSIBILITY_TAP_SKIPPED webview_detached", true)
            return
        }

        val px = localX.coerceIn(1f, (webView.width - 2).coerceAtLeast(1).toFloat())
        val py = localY.coerceIn(1f, (webView.height - 2).coerceAtLeast(1).toFloat())
        val downTime = SystemClock.uptimeMillis()

        val downEvent = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_DOWN, px, py, 0
        ).apply {
            source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        }

        val downConsumed = try {
            webView.dispatchTouchEvent(downEvent)
        } finally {
            downEvent.recycle()
        }

        mainHandler.postDelayed({
            if (!webView.isAttachedToWindow) {
                log(accountUsername, "ADS: ACCESSIBILITY_TAP_UP_SKIPPED webview_detached", true)
                return@postDelayed
            }

            val upTime = SystemClock.uptimeMillis()
            val upEvent = MotionEvent.obtain(
                downTime, upTime, MotionEvent.ACTION_UP, px, py, 0
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
                "ADS: ACCESSIBILITY_NATIVE_TAP localX=" + px +
                    " localY=" + py +
                    " downConsumed=" + downConsumed +
                    " upConsumed=" + upConsumed
            )
        }, 120L)
    }

    /**
     * Finds the real Yandex rewarded "Получить награду" control through the
     * Android accessibility tree exposed by Chromium/WebView. This is needed
     * because the rewarded UI can live in a cross-origin iframe invisible to
     * page JavaScript.
     */
    private fun autoClaimRewardFromAccessibility(
        accountUsername: String,
        webView: WebView
    ): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post {
                autoClaimRewardFromAccessibility(accountUsername, webView)
            }
            return false
        }

        if (!webView.isAttachedToWindow || webView.width <= 0 || webView.height <= 0) {
            log(
                accountUsername,
                "ADS: ACCESSIBILITY_SCAN_SKIP attached=" + webView.isAttachedToWindow +
                    " size=" + webView.width + "x" + webView.height,
                true
            )
            return false
        }

        val root = try {
            webView.createAccessibilityNodeInfo()
        } catch (e: Exception) {
            log(
                accountUsername,
                "ADS: ACCESSIBILITY_SCAN_ERROR root=" + e.message,
                true
            )
            return false
        }

        val queue = ArrayDeque<Pair<android.view.accessibility.AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)

        data class Candidate(
            val node: android.view.accessibility.AccessibilityNodeInfo,
            val label: String,
            val bounds: android.graphics.Rect,
            val exact: Boolean,
            val clickable: Boolean
        )

        val candidates = mutableListOf<Candidate>()
        var visited = 0
        val maxVisited = 600

        while (queue.isNotEmpty() && visited < maxVisited) {
            val (node, depth) = queue.removeFirst()
            visited++

            val label = buildString {
                node.text?.toString()?.let { append(it) }
                node.contentDescription?.toString()?.let {
                    if (isNotEmpty()) append(" ")
                    append(it)
                }
            }.replace("\\s+".toRegex(), " ").trim()

            val normalized = label.lowercase()
            val exact = normalized.contains("получить награду")
            val rewardLike = exact ||
                (normalized.contains("получить") && normalized.contains("наград")) ||
                normalized.contains("claim reward") ||
                normalized.contains("get reward")

            if (rewardLike && node.isVisibleToUser) {
                val bounds = android.graphics.Rect()
                try {
                    node.getBoundsInScreen(bounds)
                } catch (_: Exception) {
                    bounds.setEmpty()
                }

                if (!bounds.isEmpty) {
                    candidates += Candidate(
                        node = node,
                        label = label,
                        bounds = bounds,
                        exact = exact,
                        clickable = node.isClickable
                    )
                }
            }

            if (depth < 14) {
                for (i in 0 until node.childCount) {
                    try {
                        node.getChild(i)?.let { child ->
                            queue.add(child to (depth + 1))
                        }
                    } catch (_: Exception) {
                        // The ad accessibility subtree can change during transitions.
                    }
                }
            }
        }

        log(
            accountUsername,
            "ADS: ACCESSIBILITY_SCAN visited=" + visited +
                " candidates=" + candidates.size
        )

        val candidate = candidates.sortedWith(
            compareByDescending<Candidate> { it.exact }
                .thenByDescending { it.clickable }
                .thenBy { it.bounds.width().toLong() * it.bounds.height().toLong() }
        ).firstOrNull() ?: return false

        log(
            accountUsername,
            "ADS: ACCESSIBILITY_REWARD_FOUND text=" + candidate.label.take(180) +
                " clickable=" + candidate.clickable +
                " bounds=" + candidate.bounds.toShortString()
        )

        try {
            if (candidate.node.performAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK
                )
            ) {
                log(
                    accountUsername,
                    "ADS: ACCESSIBILITY_REWARD_ACTION_CLICK success=true"
                )
                return true
            }
        } catch (e: Exception) {
            log(
                accountUsername,
                "ADS: ACCESSIBILITY_REWARD_ACTION_CLICK error=" + e.message,
                true
            )
        }

        val location = IntArray(2)
        webView.getLocationOnScreen(location)
        val centerX = candidate.bounds.centerX().toFloat() - location[0]
        val centerY = candidate.bounds.centerY().toFloat() - location[1]

        log(
            accountUsername,
            "ADS: ACCESSIBILITY_REWARD_ACTION_CLICK fallback_tap " +
                "screen=" + candidate.bounds.centerX() + "," + candidate.bounds.centerY() +
                " webViewOrigin=" + location[0] + "," + location[1] +
                " local=" + centerX + "," + centerY
        )

        dispatchNativeLocalTap(accountUsername, webView, centerX, centerY)
        return true
    }

    private fun dispatchNativeSwipe(
        webView: WebView,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long,
        triggerFling: Boolean = true
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

        /*
         * IMPORTANT:
         * MotionEvents must be dispatched at their real wall-clock times.
         * The previous implementation sent the whole gesture in one main-thread
         * loop and only changed MotionEvent.eventTime. WebView therefore received
         * all MOVE events almost instantly, while Chromium's velocity tracker saw
         * the artificial timestamps and could start a huge fling on ACTION_UP.
         *
         * That is exactly the "first swipes are normal, then the reader becomes
         * lightning fast" behaviour from the logs:
         *   distance=40..46
         *   deltaY=471 -> 497 -> 4509 -> 8101 -> 11532 -> 16686
         *
         * Send the events asynchronously over real elapsed time instead. The
         * final position is held briefly before ACTION_UP so the finger velocity
         * reaches ~0 instead of handing WebView a fling.
         */
        val safeDuration = durationMs.coerceIn(325L, 425L)
        val moveSteps = 12
        val settleMs = 35L
        val downTime = SystemClock.uptimeMillis()

        fun send(action: Int, x: Float, y: Float) {
            val eventTime = SystemClock.uptimeMillis()
            MotionEvent.obtain(
                downTime,
                eventTime,
                action,
                x,
                y,
                0
            ).also { event ->
                try {
                    webView.dispatchTouchEvent(event)
                } finally {
                    event.recycle()
                }
            }
        }

        send(MotionEvent.ACTION_DOWN, startX, startY)

        for (i in 1 until moveSteps) {
            val fraction = i.toFloat() / moveSteps.toFloat()
            val delayMs = (safeDuration * fraction).toLong()

            mainHandler.postDelayed({
                if (!webView.isAttachedToWindow) return@postDelayed

                // Ease-out movement: most of the finger travel happens early,
                // then the finger naturally slows toward the end of the swipe.
                val eased = 1f - ((1f - fraction) * (1f - fraction))
                val currentX = startX + ((endX - startX) * eased)
                val currentY = startY + ((endY - startY) * eased)
                send(MotionEvent.ACTION_MOVE, currentX, currentY)
            }, delayMs)
        }

        // Final MOVE at the destination. Keep only a very short settle period:
        // we intentionally want a small, controlled native inertia after release.
        mainHandler.postDelayed({
            if (!webView.isAttachedToWindow) return@postDelayed
            send(MotionEvent.ACTION_MOVE, endX, endY)

            mainHandler.postDelayed({
                if (!webView.isAttachedToWindow) return@postDelayed
                send(MotionEvent.ACTION_UP, endX, endY)

                if (triggerFling) {
                    /*
                     * Controlled inertia experiment: increase the post-release velocity
                     * so the reader behaves more like a real phone swipe. The gesture
                     * itself is still dispatched with real wall-clock MotionEvent times;
                     * only this bounded native continuation is being changed.
                     *
                     * 2400 px/s was too weak on a high-density device (~800 CSS px/s
                     * at density 3), producing only about 210-220 CSS px of continuation
                     * in the logs. 4800 px/s should roughly double that continuation
                     * while avoiding the old synthetic-timestamp runaway fling.
                     */
                    val inertiaVelocity = 4800

                    log(
                        "SYSTEM",
                        "READER: NATIVE_FLING_START velocity=$inertiaVelocity " +
                            "duration=$safeDuration"
                    )

                    mainHandler.postDelayed({
                        if (!webView.isAttachedToWindow) return@postDelayed
                        webView.flingScroll(0, inertiaVelocity)
                    }, 20L)
                } else {
                    log("SYSTEM", "READER: BACKGROUND_SCROLL_RECOVERY_GESTURE_FINISHED fling=false")
                }
            }, settleMs)
        }, safeDuration)
    }

    private var dailyStats = DailyStats(day = currentStatsDay())

    private val dailyCalendarPrefs by lazy {
        context.getSharedPreferences("mangabuff_daily_calendar_guard", Context.MODE_PRIVATE)
    }
    private val dailyCalendarAttemptsInFlight =
        java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // MangaBuff's daily reward rollover follows Moscow time rather than the device's timezone.
    private fun dailyCalendarDate(): String =
        java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("Europe/Moscow")
        }.format(java.util.Date())

    /**
     * The calendar is only opened after all three ad rewards have been recorded for today.
     * A per-account/day preference prevents repeated modal openings, and an in-flight set
     * prevents overlapping balance refreshes from clicking the same reward twice.
     */
    private fun reserveDailyCalendarAttempt(account: MangaBuffAccount): Pair<Boolean, String> {
        val statsDay = currentStatsDay()
        val adsToday = when {
            dailyStats.day == statsDay -> dailyStats.ads.coerceAtLeast(0)
            account.dailyStatsDay == statsDay -> account.dailyAds.coerceAtLeast(0)
            else -> 0
        }
        if (adsToday < ADS_DAILY_LIMIT) {
            return false to "SKIPPED_WAITING_FOR_ADS ads=$adsToday/$ADS_DAILY_LIMIT"
        }

        val calendarDay = dailyCalendarDate()
        val key = "claimed_day_${account.id}"
        if (dailyCalendarPrefs.getString(key, "") == calendarDay) {
            return false to "SKIPPED_ALREADY_CLAIMED day=$calendarDay"
        }
        if (!dailyCalendarAttemptsInFlight.add(account.id)) {
            return false to "SKIPPED_ALREADY_IN_FLIGHT"
        }
        return true to "RUN day=$calendarDay ads=$adsToday/$ADS_DAILY_LIMIT"
    }

    private fun completeDailyCalendarAttempt(account: MangaBuffAccount, result: String) {
        if (!dailyCalendarAttemptsInFlight.remove(account.id)) return

        val confirmedOrAlreadyCollected =
            result.startsWith("CLAIM_STATE_CHANGED") || result == "NO_CLAIMABLE_REWARD"
        if (confirmedOrAlreadyCollected) {
            dailyCalendarPrefs.edit()
                .putString("claimed_day_${account.id}", dailyCalendarDate())
                .apply()
        }
        log(
            account.username,
            "DAILY_REWARD: DAILY_GUARD saved=$confirmedOrAlreadyCollected result=$result day=${dailyCalendarDate()}"
        )
    }

    private fun releaseDailyCalendarAttempt(account: MangaBuffAccount, reason: String) {
        if (dailyCalendarAttemptsInFlight.remove(account.id)) {
            log(account.username, "DAILY_REWARD: DAILY_GUARD released reason=$reason")
        }
    }

    private fun initDailyStats(account: MangaBuffAccount) {
        dailyStats = if (account.dailyStatsDay == currentStatsDay()) {
            DailyStats(
                day = account.dailyStatsDay,
                battles = account.dailyBattles,
                battleAttempts = account.dailyBattleAttempts,
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
    private var heartbeatChapterContext: Pair<String, String> = Pair("", "")

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

    private val deckCommentPhrases = listOf(
        "Какая классная колода! Просто мечта! 😍",
        "Вау, вот это колода! Очень круто собрано! 🔥",
        "Шикарная колода, глаз не оторвать! ✨",
        "Вот это подборка! Я бы такую колоду тоже хотел! ❤️",
        "Очень крутая колода, настоящая мечта коллекционера! 😍",
        "Какая красота! Отличная колода! 💜",
        "Сильная и очень стильная колода! 🔥",
        "Просто восторг! Очень понравилась эта колода! 😍",
        "Вот это состав! Выглядит невероятно круто! ✨",
        "Мечта, а не колода! Очень классная работа! ❤️",
        "Ух ты, какая замечательная колода! 😍",
        "Очень удачная подборка, выглядит потрясающе! 🔥",
        "Какие красивые карты! Колода получилась шикарной! ✨",
        "Сразу видно, сколько души вложено в эту колоду! ❤️",
        "Невероятно классная колода, мне очень нравится! 💜",
        "Вот это уровень! Такая колода реально радует глаз! 😍",
        "Отличная идея и очень крутая подборка! 🔥",
        "Прям хочется собрать себе такую же колоду! ✨",
        "Очень атмосферная колода, получилась просто супер! ❤️",
        "Красота! Такая колода точно заслуживает внимания! 😍",
        "Отлично подобраны карты, колода смотрится гармонично! ✨",
        "Классный набор, сразу видно интересную задумку! 🔥",
        "Есть в этой колоде свой стиль — здорово получилось! 💜",
        "Очень приятное сочетание карт, выглядит здорово! 😍",
        "Подборка вышла на славу, продолжай в том же духе! ❤️",
        "У каждой карты будто своё место — классная работа! ✨",
        "Колода выглядит сбалансированной и интересной! 👏",
        "Здорово видеть такую оригинальную подборку! 🔥",
        "Мне нравится общий стиль этой колоды! 💙",
        "Очень достойный состав, приятно посмотреть! 😎",
        "Хорошая идея для колоды, получилось выразительно! ✨",
        "Видно, что подборка продумана до мелочей! ❤️",
        "Красиво собраны карты, всё смотрится на своём месте! 💜",
        "Интересный выбор карт — колода цепляет взгляд! 👀",
        "Получилось свежо и необычно, класс! 🔥",
        "Отличная коллекция, есть на что посмотреть! 😍",
        "Люблю такие подборки с характером! 💫",
        "Колода получилась запоминающейся, здорово! 👏",
        "Приятно смотреть на такую аккуратную сборку! ✨",
        "Круто, что у колоды есть свой узнаваемый стиль! 💜" 
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

    /** Current page chapter identity for heartbeat diagnostics, separate from last completion. */
    fun getHeartbeatChapterContext(): Pair<String, String> = heartbeatChapterContext

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
            upper.startsWith("TOWER:") -> "TOWER"
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

    private fun logWebViewNetworkRequest(
        account: MangaBuffAccount,
        request: WebResourceRequest
    ) {
        val url = request.url.toString()
        if (!url.startsWith("https://mangabuff.ru")) return

        val important = listOf(
            "/addHistory",
            "/balance",
            "/mine",
            "/battle",
            "/quiz",
            "/ads",
            "/auth",
            "/login",
            "/api/"
        )
        if (important.none { url.contains(it, ignoreCase = true) }) return

        val interestingHeaders = request.requestHeaders
            .asSequence()
            .filter { (name, _) ->
                name.lowercase() in setOf(
                    "accept",
                    "accept-language",
                    "content-type",
                    "origin",
                    "referer",
                    "sec-ch-ua",
                    "sec-ch-ua-mobile",
                    "sec-ch-ua-platform",
                    "sec-fetch-dest",
                    "sec-fetch-mode",
                    "sec-fetch-site",
                    "x-requested-with",
                    "x-csrf-token"
                )
            }
            .sortedBy { it.key.lowercase() }
            .joinToString(";") { (name, value) ->
                val lower = name.lowercase()
                val safeValue = when (lower) {
                    "x-csrf-token",
                    "x-xsrf-token",
                    "authorization",
                    "proxy-authorization",
                    "cookie",
                    "set-cookie" -> "<redacted len=${value.length}>"
                    else -> value.take(240)
                }
                "$name=$safeValue"
            }
            .ifBlank { "none" }

        log(
            account.username,
            "NET: WEBVIEW_REQUEST method=${request.method} url=$url headers=$interestingHeaders"
        )
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
        var pendingBalanceSummary: String? = null
        var dailyRewardFlowResult: String? = null
        // Must outlive the main-thread setup block because cancellation cleanup runs outside it.
        val dailyRewardAttemptReserved = java.util.concurrent.atomic.AtomicBoolean(false)

        mainHandler.post {

            var resumed = false
            var dailyRewardScriptInjected = false

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
                    pendingBalanceSummary = summaryStr
                    if (dailyRewardFlowResult != null) {
                        safeResume(summaryStr)
                    } else {
                        log(account.username, "STAT: WAITING_FOR_DAILY_REWARD_FLOW")
                    }
                }

                @JavascriptInterface
                fun onDailyRewardFlowDone(result: String) {
                    if (dailyRewardAttemptReserved.compareAndSet(true, false)) {
                        completeDailyCalendarAttempt(account, result)
                    }
                    if (resumed) return
                    dailyRewardFlowResult = result
                    log(account.username, "DAILY_REWARD: FLOW_DONE result=$result")
                    val summary = pendingBalanceSummary
                    if (summary != null) safeResume(summary)
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

            webView.webViewClient = object : AutomationWebViewClient(account.username, webView) {

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
                                AndroidBalanceBridge.onBalanceDiag('STAT: BALANCE_SCRIPT_START');
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
                                                var chapterMatch = fullText.match(/(\d+)\s*\/\s*(\d+)/);
                                                if (chapterMatch) {
                                                    chaptersVal = chapterMatch[1] + '/' + chapterMatch[2];
                                                } else if (value) {
                                                    chaptersVal = value;
                                                }
                                            }

                                            if (normalized.indexOf('комментар') !== -1) {
                                                // Prefer the fraction in the complete visible row text. Some
                                                // layouts put a quota/badge in <b> that is not current progress.
                                                var commentMatch = fullText.match(/(\d+)\s*\/\s*(\d+)/);
                                                if (commentMatch) {
                                                    commentsVal = commentMatch[1] + '/' + commentMatch[2];
                                                } else if (value) {
                                                    commentsVal = value;
                                                }
                                                AndroidBalanceBridge.onBalanceDiag(
                                                    'STAT: COMMENT_STAT_SOURCE label=' + label +
                                                    ' selected=' + commentsVal +
                                                    ' row=' + fullText.slice(0, 160)
                                                );
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

                    val dailyRewardScript = """
                        (function() {
                            var finished = false;
                            var startedAt = Date.now();
                            var maxWaitMs = 6500;

                            function textOf(el) {
                                return el ? String(el.innerText || el.textContent || '')
                                    .replace(/\\s+/g, ' ').trim() : '';
                            }

                            function finish(result) {
                                if (finished) return;
                                finished = true;
                                try {
                                    AndroidBalanceBridge.onDailyRewardFlowDone(result);
                                } catch (e) {}
                            }

                            function verifyClaim(day, clickedAt) {
                                var item = document.querySelector(
                                    '.daily-rewards-item[data-day="' + day + '"]'
                                );
                                var exp = item ? item.querySelector('.daily-rewards-item-exp') : null;
                                var label = textOf(exp);
                                var stillClaimable = !!exp &&
                                    exp.classList.contains('daily-rewards-item-exp--active') &&
                                    /забрать/i.test(label);

                                if (!stillClaimable) {
                                    AndroidBalanceBridge.onBalanceDiag(
                                        'DAILY_REWARD: CLAIM_STATE_CHANGED day=' + day +
                                        ' label=' + label
                                    );
                                    finish('CLAIM_STATE_CHANGED day=' + day);
                                    return;
                                }

                                if (Date.now() - clickedAt < 3500) {
                                    setTimeout(function() { verifyClaim(day, clickedAt); }, 350);
                                    return;
                                }

                                AndroidBalanceBridge.onBalanceDiag(
                                    'DAILY_REWARD: CLAIM_STILL_ACTIVE day=' + day +
                                    ' label=' + label
                                );
                                finish('CLAIM_STILL_ACTIVE day=' + day);
                            }

                            function inspectCalendar() {
                                if (finished) return;

                                var list = document.querySelector('.daily-rewards');
                                var active = list
                                    ? list.querySelector('.daily-rewards-item-exp--active')
                                    : null;

                                if (active) {
                                    var label = textOf(active);
                                    if (/забрать/i.test(label)) {
                                        var item = active.closest
                                            ? active.closest('.daily-rewards-item')
                                            : active.parentElement;
                                        var day = item ? (item.getAttribute('data-day') || '?') : '?';

                                        AndroidBalanceBridge.onBalanceDiag(
                                            'DAILY_REWARD: CLAIMABLE_FOUND day=' + day +
                                            ' label=' + label
                                        );

                                        // Click only once per balance-page load. Poll the same day
                                        // to verify the active "Забрать" state disappeared.
                                        active.click();
                                        AndroidBalanceBridge.onBalanceDiag(
                                            'DAILY_REWARD: CLAIM_CLICKED day=' + day
                                        );
                                        var clickedAt = Date.now();
                                        setTimeout(function() {
                                            verifyClaim(day, clickedAt);
                                        }, 350);
                                        return;
                                    }
                                }

                                if (list && Date.now() - startedAt >= 1200) {
                                    finish('NO_CLAIMABLE_REWARD');
                                    return;
                                }

                                if (Date.now() - startedAt >= maxWaitMs) {
                                    finish('CALENDAR_MODAL_TIMEOUT');
                                    return;
                                }

                                setTimeout(inspectCalendar, 250);
                            }

                            try {
                                var trigger = document.querySelector(
                                    'button.daily-rewards-btn[data-modal-trigger="daily-rewards-info"]'
                                );
                                if (!trigger) {
                                    AndroidBalanceBridge.onBalanceDiag(
                                        'DAILY_REWARD: CALENDAR_BUTTON_NOT_FOUND'
                                    );
                                    finish('CALENDAR_BUTTON_NOT_FOUND');
                                    return;
                                }

                                AndroidBalanceBridge.onBalanceDiag('DAILY_REWARD: CALENDAR_OPEN');
                                trigger.click();
                                setTimeout(inspectCalendar, 250);
                            } catch (e) {
                                finish('SCRIPT_ERROR ' + (e && e.message ? e.message : String(e)));
                            }
                        })();
                    """.trimIndent()

                    view?.evaluateJavascript(script, null)
                    if (!dailyRewardScriptInjected) {
                        dailyRewardScriptInjected = true
                        val (shouldRunCalendar, calendarDecision) = reserveDailyCalendarAttempt(account)
                        if (shouldRunCalendar) {
                            dailyRewardAttemptReserved.set(true)
                            log(account.username, "DAILY_REWARD: DAILY_GUARD state=RUN detail=$calendarDecision")
                            if (view != null) {
                                view.evaluateJavascript(dailyRewardScript, null)
                            } else {
                                dailyRewardAttemptReserved.set(false)
                                releaseDailyCalendarAttempt(account, "BALANCE_VIEW_NULL")
                                dailyRewardFlowResult = "SKIPPED_BALANCE_VIEW_NULL"
                            }
                        } else {
                            dailyRewardFlowResult = calendarDecision
                            log(account.username, "DAILY_REWARD: DAILY_GUARD state=$calendarDecision")
                        }
                        val summary = pendingBalanceSummary
                        if (summary != null && dailyRewardFlowResult != null) safeResume(summary)
                    }
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
                    if (dailyRewardAttemptReserved.compareAndSet(true, false)) {
                        releaseDailyCalendarAttempt(account, "BALANCE_TIMEOUT")
                    }
                    log(account.username, "STAT: BALANCE_TIMEOUT after=15000ms; stats remain unknown", true)
                    safeResume("⚠️ Статистика не обновлена: тайм-аут")
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
                if (dailyRewardAttemptReserved.compareAndSet(true, false)) {
                    releaseDailyCalendarAttempt(account, "BALANCE_CANCELLED")
                }
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
        webView: WebView,
        promoCode: String = ""
    ) {
        coroutineContext.ensureActive()

        initDailyStats(account)
        log(account.username, "TASK: ACCOUNT_START type=${taskType.title}")
        updateStatus(account, "Обновление статистики...", true, taskType.title, 0.05f)

        refreshCsrfToken(account)

        if (taskType == TaskType.ADS) {
            /*
             * Pure ad test: runAdsTask() owns the /balance navigation and waits
             * for the page before injecting the ad runner. Do not run the
             * general balance/diagnostic pass first, because it installs its
             * own WebViewClient and executes unrelated page diagnostics before
             * the ad test gets control of the WebView.
             */
            log(account.username, "TASK: ADS_TEST_PREP skip_initial_balance_refresh")
        } else {
            log(account.username, "STAT: REFRESH_BEFORE_START (Обновление баланса и статистики)")
            val balanceResult = fetchAndLogBalanceInfo(account, webView)

            if (balanceResult.contains("Требуется повторный вход") ||
                account.getSafeStatusMessage().contains("Требуется повторный вход")
            ) {
                log(account.username, "AUTH: ABORTING_TASKS due to expired session")
                return
            }
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

        if (taskType == TaskType.ALL || taskType == TaskType.TOWER) {
            coroutineContext.ensureActive()
            if (account.towerEnabled || taskType == TaskType.TOWER) {
                log(account.username, "TASK: TOWER_START")
                val start = SystemClock.elapsedRealtime()
                runTowerTask(account, webView, settings)
                log(account.username, "TASK: TOWER_END elapsed=${SystemClock.elapsedRealtime() - start}ms")
                // Balance is refreshed once at the end of the account run, not between tasks.
            } else {
                log(account.username, "TOWER: SKIPPED_DISABLED")
            }
        }

        if (taskType == TaskType.ALL || taskType == TaskType.ADS) {
            coroutineContext.ensureActive()
            if (account.advEnabled || taskType == TaskType.ADS) {
                log(account.username, "TASK: ADS_START")
                val start = SystemClock.elapsedRealtime()
                val adsSuccess = runAdsTask(account, webView)
                log(account.username, "TASK: ADS_END elapsed=" + (SystemClock.elapsedRealtime() - start) + "ms success=" + adsSuccess)
                fetchAndLogBalanceInfo(account, webView)
                if (!adsSuccess) {
                    updateStatus(
                        account,
                        "⚠️ Реклама: награда не подтверждена — продолжаем",
                        true,
                        "Реклама",
                        0f
                    )
                    log(account.username, "TASK: ADS_FAILED_CONTINUE reason=REWARD_NOT_CONFIRMED", true)
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
                log(
                    account.username,
                    "TASK: COMMENT_END elapsed=" +
                        (SystemClock.elapsedRealtime() - start) + "ms"
                )
                fetchAndLogBalanceInfo(account, webView)
            }

            if (account.deckCommentEnabled) {
                coroutineContext.ensureActive()
                log(account.username, "TASK: DECK_COMMENT_ENABLED")
                val start = SystemClock.elapsedRealtime()
                runDeckCommentTask(account, webView)
                log(
                    account.username,
                    "TASK: DECK_COMMENT_END elapsed=" +
                        (SystemClock.elapsedRealtime() - start) + "ms"
                )
                fetchAndLogBalanceInfo(account, webView)
            } else {
                log(account.username, "COMMENT: DECK_DISABLED")
            }
        }

        if (taskType == TaskType.PROMO) {
            coroutineContext.ensureActive()
            runPromoCodeTask(account, webView, promoCode)
        }

        val finalSummary = fetchAndLogBalanceInfo(account, webView)
        updateStatus(account, finalSummary, false, "", 1.0f)
        log(account.username, "TASK: ACCOUNT_FINISHED type=${taskType.title}")
    }

    // =========================================================
    // PROMO CODE
    // =========================================================

    private suspend fun runPromoCodeTask(
        account: MangaBuffAccount,
        webView: WebView,
        promoCode: String
    ) {
        val code = promoCode.trim()
        if (code.isBlank()) {
            log(account.username, "PROMO: SKIPPED_EMPTY_CODE", true)
            return
        }

        suspendCancellableCoroutine<Unit> { continuation ->
            val completed = java.util.concurrent.atomic.AtomicBoolean(false)
            var watchdog: Runnable? = null
            val promoScriptInjected = java.util.concurrent.atomic.AtomicBoolean(false)
            val promoActivationStarted = java.util.concurrent.atomic.AtomicBoolean(false)

            fun finish(success: Boolean, detail: String) {
                if (!completed.compareAndSet(false, true)) return
                mainHandler.post {
                    watchdog?.let { mainHandler.removeCallbacks(it) }
                    watchdog = null
                    try { webView.removeJavascriptInterface("AndroidPromoBridge") } catch (_: Exception) {}
                    log(account.username, "PROMO: TASK_RESULT success=$success detail=$detail", !success)
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }

            continuation.invokeOnCancellation {
                mainHandler.post {
                    watchdog?.let { mainHandler.removeCallbacks(it) }
                    try { webView.removeJavascriptInterface("AndroidPromoBridge") } catch (_: Exception) {}
                    try { webView.stopLoading() } catch (_: Exception) {}
                }
            }

            mainHandler.post {
                if (!continuation.isActive) {
                    finish(false, "continuation_cancelled")
                    return@post
                }

                class PromoBridge {
                    @JavascriptInterface
                    fun onPromoLog(state: String, detail: String) {
                        if (state == "ACTIVATE_CLICKING" || state == "ACTIVATE_CLICKED") {
                            promoActivationStarted.set(true)
                        }
                        val error = state.contains("ERROR", true) ||
                            state.contains("FAIL", true) ||
                            state.contains("REJECTED", true)
                        log(account.username, "PROMO: $state $detail", error)
                    }

                    @JavascriptInterface
                    fun onPromoFinished(success: Boolean, detail: String) {
                        finish(success, detail)
                    }
                }

                try {
                    webView.removeJavascriptInterface("AndroidPromoBridge")
                    webView.addJavascriptInterface(PromoBridge(), "AndroidPromoBridge")
                    webView.settings.javaScriptEnabled = true
                    webView.webViewClient = object : AutomationWebViewClient(account.username, webView) {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (url.isNullOrBlank()) return
                            val uri = try { Uri.parse(url) } catch (_: Exception) { null }
                            if (uri?.host != "mangabuff.ru") return

                            if (uri.path.orEmpty().startsWith("/login")) {
                                finish(false, "login_required")
                                return
                            }
                            if (uri.path.orEmpty().trimEnd('/') != "/promo-code") return

                            if (!promoScriptInjected.compareAndSet(false, true)) {
                                // A full-page response after Activate must be inspected, not submitted again.
                                if (promoActivationStarted.get()) {
                                    val resultOnlyScript = """
                                        (function() {
                                            var startedAt = Date.now();
                                            function readMessage() {
                                                var nodes = Array.from(document.querySelectorAll(
                                                    '.promo-page__message,.promo-page__error,.promo-page__success,' +
                                                    '.promo-code-message,.promo-code-result,[role="alert"],.alert,.toast,.notification'
                                                ));
                                                return nodes.map(function(el) {
                                                    return String(el.innerText || el.textContent || '').replace(/\\s+/g, ' ').trim();
                                                }).filter(function(t) { return t.length > 0; }).slice(-3).join(' | ');
                                            }
                                            function inspect() {
                                                var message = readMessage();
                                                var lower = message.toLowerCase();
                                                if (message && /невер|ист[её]к|уже использ|уже активир|не существует|ошиб|недоступ|не найден|лимит|отказ/i.test(lower)) {
                                                    AndroidPromoBridge.onPromoFinished(false, 'site_rejected_after_navigation: ' + message);
                                                    return;
                                                }
                                                if (message && /успеш|активирован|примен[её]н|получен|начислен|бонус|промокод принят/i.test(lower)) {
                                                    AndroidPromoBridge.onPromoFinished(true, 'site_confirmed_after_navigation: ' + message);
                                                    return;
                                                }
                                                if (Date.now() - startedAt >= 6000) {
                                                    AndroidPromoBridge.onPromoFinished(false, 'activation_navigation_result_unconfirmed');
                                                    return;
                                                }
                                                setTimeout(inspect, 350);
                                            }
                                            inspect();
                                        })();
                                    """.trimIndent()
                                    view?.evaluateJavascript(resultOnlyScript, null)
                                }
                                return
                            }

                            log(account.username, "PROMO: PAGE_READY url=$url")
                            val codeLiteral = org.json.JSONObject.quote(code)
                            val script = """
                                (function() {
                                    var runKey = 'mb_promo_run_' + Date.now() + '_' + Math.random();
                                    if (window.__mbPromoRunKey === runKey) return;
                                    window.__mbPromoRunKey = runKey;
                                    var promoCode = $codeLiteral;
                                    var startedAt = Date.now();
                                    var clickedAt = 0;
                                    var finished = false;

                                    function send(state, detail) {
                                        try {
                                            AndroidPromoBridge.onPromoLog(
                                                state,
                                                String(detail || '').replace(/\s+/g, ' ').slice(0, 220)
                                            );
                                        } catch (e) {}
                                    }

                                    function finish(success, detail) {
                                        if (finished) return;
                                        finished = true;
                                        try {
                                            AndroidPromoBridge.onPromoFinished(!!success, String(detail || '').slice(0, 500));
                                        } catch (e) {}
                                    }

                                    function textOf(el) {
                                        return el ? String(el.innerText || el.textContent || '')
                                            .replace(/\s+/g, ' ').trim() : '';
                                    }

                                    function findResultMessage() {
                                        var nodes = Array.from(document.querySelectorAll(
                                            '.promo-page__message,.promo-page__error,.promo-page__success,' +
                                            '.promo-code-message,.promo-code-result,[role="alert"],.alert,.toast,.notification'
                                        ));
                                        return nodes.map(textOf).filter(function(text) { return text.length > 0; })
                                            .slice(-3).join(' | ');
                                    }

                                    function pollResult() {
                                        if (finished) return;
                                        var message = findResultMessage();
                                        if (message) {
                                            var lower = message.toLowerCase();
                                            if (/невер|ист[её]к|уже использ|уже активир|не существует|ошиб|недоступ|не найден|лимит|отказ/i.test(lower)) {
                                                send('ACTIVATION_REJECTED', message);
                                                finish(false, 'site_rejected: ' + message);
                                                return;
                                            }
                                            if (/успеш|активирован|примен[её]н|получен|начислен|бонус|промокод принят/i.test(lower)) {
                                                send('ACTIVATION_CONFIRMED', message);
                                                finish(true, 'site_confirmed: ' + message);
                                                return;
                                            }
                                        }

                                        if (Date.now() - clickedAt >= 10000) {
                                            send('ACTIVATION_RESULT_UNCONFIRMED',
                                                message || 'The site did not expose a recognizable result message');
                                            finish(false, 'activation_result_unconfirmed');
                                            return;
                                        }
                                        setTimeout(pollResult, 400);
                                    }

                                    function locateAndActivate() {
                                        if (finished) return;
                                        var input = document.querySelector(
                                            'input.promo-page__input.promo-code-use-input,' +
                                            'input[name^="promo_code_fake_"]'
                                        );
                                        var button = document.querySelector(
                                            'button.promo-page__button.promo-code-use-btn'
                                        );

                                        if (!input || !button) {
                                            if (Date.now() - startedAt >= 12000) {
                                                send('FORM_NOT_FOUND',
                                                    'inputFound=' + !!input + ' buttonFound=' + !!button);
                                                finish(false, 'promo_form_not_found');
                                                return;
                                            }
                                            setTimeout(locateAndActivate, 300);
                                            return;
                                        }

                                        if (button.disabled || button.getAttribute('aria-disabled') === 'true') {
                                            send('ACTIVATE_BUTTON_DISABLED', 'The site currently disables the Activate button');
                                            finish(false, 'activate_button_disabled');
                                            return;
                                        }

                                        try {
                                            var descriptor = Object.getOwnPropertyDescriptor(
                                                HTMLInputElement.prototype, 'value'
                                            );
                                            if (descriptor && descriptor.set) descriptor.set.call(input, promoCode);
                                            else input.value = promoCode;
                                            input.dispatchEvent(new Event('input', {bubbles: true}));
                                            input.dispatchEvent(new Event('change', {bubbles: true}));
                                            send('CODE_ENTERED', 'length=' + promoCode.length);

                                            setTimeout(function() {
                                                if (finished) return;
                                                if (!document.contains(button)) {
                                                    button = document.querySelector(
                                                        'button.promo-page__button.promo-code-use-btn'
                                                    );
                                                }
                                                if (!button || button.disabled) {
                                                    send('ACTIVATE_BUTTON_UNAVAILABLE', 'Button missing or disabled after input');
                                                    finish(false, 'activate_button_unavailable_after_input');
                                                    return;
                                                }
                                                clickedAt = Date.now();
                                                send('ACTIVATE_CLICKING', 'Submitting entered promo code');
                                                button.click();
                                                send('ACTIVATE_CLICKED', 'Clicked the real site Activate button');
                                                setTimeout(pollResult, 400);
                                            }, 250);
                                        } catch (e) {
                                            send('FORM_ERROR', e && e.message ? e.message : String(e));
                                            finish(false, 'form_interaction_error');
                                        }
                                    }

                                    locateAndActivate();
                                })();
                            """.trimIndent()
                            view?.evaluateJavascript(script, null)
                        }
                    }

                    watchdog = Runnable { finish(false, "promo_page_timeout") }
                    mainHandler.postDelayed(watchdog!!, 30_000L)
                    log(account.username, "PROMO: OPEN url=https://mangabuff.ru/promo-code")
                    updateStatus(account, "🎟️ Промокод: активация…", true, "Промокод", 0.1f)
                    webView.loadUrl("https://mangabuff.ru/promo-code")
                } catch (e: Exception) {
                    finish(false, "startup_error=" + e.message)
                }
            }
        }
    }

    // =========================================================
    // BATTLE
    // =========================================================

    private suspend fun runBattleTask(
        account: MangaBuffAccount,
        settings: GlobalSettings,
        webView: WebView
    ): TaskResult {
        val battleTarget = settings.battleTargetCount.coerceAtLeast(0)
        var battleCount = 0
        var winCount = 0

        log(
            account.username,
            "BATTLE: START targetBattles=" + battleTarget + " targetWins=" + BATTLE_WIN_TARGET
        )
        updateStatus(
            account,
            "⚔️ " + battleTarget + "(боев)/" + winCount + " победы",
            true,
            "Бои",
            0f
        )

        while (battleCount < battleTarget) {
            coroutineContext.ensureActive()

            val progress = if (battleTarget > 0) {
                battleCount.toFloat() / battleTarget
            } else {
                1f
            }

            updateStatus(
                account,
                "⚔️ " + battleTarget + "(боев)/" + winCount + " победы",
                true,
                "Бои",
                progress
            )

            when (val roundResult = runSingleBattleRound(
                account,
                webView,
                battleCount + 1,
                battleTarget
            )) {
                is TaskResult.BattleWon -> {
                    battleCount++
                    winCount++
                    addDaily(account) {
                        it.copy(
                            battleAttempts = it.battleAttempts + 1,
                            battles = it.battles + 1
                        )
                    }
                    log(
                        account.username,
                        "BATTLE: RESULT=WIN battles=" + battleCount +
                            "/" + battleTarget + " wins=" + winCount +
                            " dailyAttempts=" + dailyStats.battleAttempts +
                            " dailyWins=" + dailyStats.battles
                    )
                }

                is TaskResult.BattleLost -> {
                    battleCount++
                    addDaily(account) {
                        it.copy(
                            battleAttempts = it.battleAttempts + 1
                        )
                    }
                    log(
                        account.username,
                        "BATTLE: RESULT=LOSS battles=" + battleCount +
                            "/" + battleTarget + " wins=" + winCount +
                            " dailyAttempts=" + dailyStats.battleAttempts +
                            " dailyWins=" + dailyStats.battles
                    )
                }

                is TaskResult.Cancelled -> return TaskResult.Cancelled
                is TaskResult.Failed -> return roundResult
                else -> return TaskResult.Failed("unexpected_battle_round_result")
            }

            updateStatus(
                account,
                "⚔️ " + battleTarget + "(боев)/" + winCount + " победы",
                true,
                "Бои",
                if (battleTarget > 0) battleCount.toFloat() / battleTarget else 1f
            )

            if (battleCount < battleTarget) {
                delay(BATTLE_COOLDOWN_MS)
            }
        }

        log(
            account.username,
            "BATTLE: TARGET_REACHED battles=" + battleCount +
                "/" + battleTarget + " wins=" + winCount
        )
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
                fun onBattleResult(result: String) {
                    val normalized = result.trim().lowercase()
                    log(account.username, "BATTLE: RESULT_DETECTED value=" + normalized)

                    mainHandler.post {
                        if (!continuation.isActive) return@post

                        when (normalized) {
                            "win", "победа" -> safeResume(TaskResult.BattleWon)
                            "loss", "lose", "поражение" -> safeResume(TaskResult.BattleLost)
                            else -> safeResume(
                                TaskResult.Failed("unknown_battle_result=" + normalized)
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

            webView.webViewClient = object : AutomationWebViewClient(account.username, webView) {
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

                                    function waitForResultPanel(startedAt) {
                                        var panel = document.querySelector('.battle-finish-panel');
                                        var title = panel
                                            ? panel.querySelector('.battle-finish-panel__title')
                                            : null;
                                        var resultText = title
                                            ? (title.textContent || '').replace(/\\s+/g, ' ').trim()
                                            : '';

                                        if (title && /^победа$/i.test(resultText)) {
                                            AndroidBattleBridge.onStateLog(
                                                'RESULT_PANEL',
                                                'title=Победа class=' + (panel.className || '')
                                            );
                                            AndroidBattleBridge.onBattleResult('win');
                                            return;
                                        }

                                        if (title && /^поражение$/i.test(resultText)) {
                                            AndroidBattleBridge.onStateLog(
                                                'RESULT_PANEL',
                                                'title=Поражение class=' + (panel.className || '')
                                            );
                                            AndroidBattleBridge.onBattleResult('loss');
                                            return;
                                        }

                                        if (Date.now() - startedAt >= 15000) {
                                            AndroidBattleBridge.onRoundFailed('result_panel_timeout');
                                            return;
                                        }

                                        setTimeout(function() {
                                            waitForResultPanel(startedAt);
                                        }, 300);
                                    }

                                    if (skip) {
                                        AndroidBattleBridge.onStateLog(
                                            'RESULTS_BUTTON_FOUND',
                                            'Кнопка К итогам найдена'
                                        );
                                        skip.click();
                                        waitForResultPanel(Date.now());
                                        return;
                                    }

                                    var elapsed = 0;
                                    var timer = setInterval(function() {
                                        elapsed += 500;
                                        var btn = Array.from(
                                            document.querySelectorAll('button.battle-control__button--skip')
                                        ).find(function(x) {
                                            return (x.textContent || '').trim() === 'К итогам' && !x.disabled;
                                        });

                                        if (btn) {
                                            clearInterval(timer);
                                            btn.click();
                                            waitForResultPanel(Date.now());
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
    // TOWER OF THE RIFT
    // =========================================================

    /**
     * Drives Tower through visible live-page controls: claim rewards, send the squad,
     * accept the requested Resonance/send confirmations, and wait for the actual expedition timer.
     * Never repeats a send when its button is absent.
     */
    private suspend fun runTowerTask(
        account: MangaBuffAccount,
        webView: WebView,
        settings: GlobalSettings
    ) = suspendCancellableCoroutine<Unit> { continuation ->
        val selectedTowerUpgradeKeysJs = buildList {
            if (settings.towerUpgradeAssault) add("assault")
            if (settings.towerUpgradeFortitude) add("fortitude")
            if (settings.towerUpgradeHunt) add("hunt")
            if (settings.towerUpgradeMentorship) add("mentorship")
            if (settings.towerUpgradeSupply) add("supply")
        }.joinToString(",") { "'$it'" }
        val runToken = UUID.randomUUID().toString().replace("-", "")
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
        var watchdog: Runnable? = null
        var scriptRecovery: Runnable? = null

        fun finish(success: Boolean, detail: String) {
            if (!completed.compareAndSet(false, true)) return
            mainHandler.post {
                watchdog?.let { mainHandler.removeCallbacks(it) }
                scriptRecovery?.let { mainHandler.removeCallbacks(it) }
                log(account.username, "TOWER: TASK_RESULT success=$success detail=$detail", !success)
                updateStatus(
                    account,
                    if (success) "🗼 Башня: действия обработаны" else "🗼 Башня: настройка требуется",
                    false,
                    "Башня Разлома",
                    if (success) 1f else 0f
                )
                try { webView.removeJavascriptInterface("AndroidTowerBridge") } catch (_: Exception) {}
                if (continuation.isActive) continuation.resume(Unit)
            }
        }

        continuation.invokeOnCancellation {
            mainHandler.post {
                watchdog?.let { mainHandler.removeCallbacks(it) }
                scriptRecovery?.let { mainHandler.removeCallbacks(it) }
                try { webView.removeJavascriptInterface("AndroidTowerBridge") } catch (_: Exception) {}
            }
        }

        mainHandler.post {
            if (!continuation.isActive) {
                finish(false, "webview_unavailable")
                return@post
            }

            class TowerBridge {
                @JavascriptInterface
                fun onTowerLog(state: String, detail: String) {
                    val error = state.contains("FAIL", true) || state.contains("ERROR", true) ||
                        state.contains("NOT_FOUND", true) || state.contains("SKIPPED", true)
                    log(account.username, "TOWER: $state $detail", error)
                }

                @JavascriptInterface
                fun onTowerTimerState(remainingSeconds: Long, active: Boolean, complete: Boolean) {
                    val endsAtMillis = if (active && !complete && remainingSeconds > 0L) {
                        System.currentTimeMillis() + remainingSeconds * 1000L
                    } else {
                        0L
                    }
                    onTowerExpeditionEndsAtUpdate(account.id, endsAtMillis)
                    log(
                        account.username,
                        "TOWER: TIMER_STATE active=$active complete=$complete remainingSeconds=$remainingSeconds"
                    )
                }

                @JavascriptInterface
                fun onTowerFinished(success: Boolean, detail: String) {
                    finish(success, detail)
                }
            }

            try {
                webView.removeJavascriptInterface("AndroidTowerBridge")
                webView.addJavascriptInterface(TowerBridge(), "AndroidTowerBridge")
                val towerScript = """
                            (function() {
                                var runKey = 'mb_tower_$runToken';
                                if (window.__mbTowerPassActive === runKey) return;
                                window.__mbTowerPassActive = runKey;
                                function send(state, detail) {
                                    try { AndroidTowerBridge.onTowerLog(state, String(detail || '').slice(0, 220)); } catch (e) {}
                                }
                                function finish(success, detail) {
                                    try { AndroidTowerBridge.onTowerFinished(!!success, String(detail || '').slice(0, 500)); } catch (e) {}
                                }
                                function attr(root, names) {
                                    for (var i = 0; i < names.length; i++) {
                                        var value = root.getAttribute(names[i]);
                                        if (value && value.trim()) return value.trim();
                                    }
                                    return '';
                                }
                                function sameOriginUrl(raw) {
                                    try {
                                        if (!raw) return '';
                                        var u = new URL(raw, location.origin);
                                        return u.origin === location.origin ? u.href : '';
                                    } catch (e) { return ''; }
                                }
                                function visible(el) {
                                    if (!el || el.disabled) return false;
                                    var s = getComputedStyle(el), r = el.getBoundingClientRect();
                                    return s.display !== 'none' && s.visibility !== 'hidden' &&
                                        parseFloat(s.opacity || '1') > 0 && r.width > 0 && r.height > 0;
                                }
                                function textOf(el) {
                                    return (el && (el.innerText || el.textContent || el.value) || '').replace(/\s+/g, ' ').trim();
                                }
                                function action(root, re) {
                                    return Array.from(root.querySelectorAll('button,a,[role="button"],input[type="submit"]'))
                                        .find(function(el) {
                                            return visible(el) && re.test(textOf(el) || el.getAttribute('aria-label') || el.title || '');
                                        }) || null;
                                }
                                function selectedCards(root) {
                                    // First read the explicit selected-ID state exposed by the page.
                                    var ids = attr(root, ['data-selected-card-ids']);
                                    var fromAttribute = ids.split(/[;,\\s]+/).filter(function(id) { return id.length > 0; });
                                    var marked = Array.from(root.querySelectorAll(
                                        '[data-card-id][aria-selected="true"],[data-card-id][aria-pressed="true"],' +
                                        '[data-card-id][data-selected="true"],[data-card-id].selected,' +
                                        '[data-card-id].is-selected,[data-tower-card][aria-pressed="true"],' +
                                        '.mbf-abyss-tower__card[aria-selected="true"],' +
                                        '.mbf-abyss-tower__card.is-selected,.mbf-abyss-tower__card.selected'
                                    ));
                                    return Math.max(new Set(fromAttribute).size, new Set(marked).size);
                                }
                                function truthyAttribute(value) {
                                    return /^(1|true|yes)$/i.test(String(value || '').trim());
                                }
                                function expeditionState(root) {
                                    var complete = truthyAttribute(root.getAttribute('data-expedition-complete')) ||
                                        root.classList.contains('is-expedition-complete');
                                    var timer = root.querySelector('[data-tower-timer]');
                                    var remainingSeconds = timer
                                        ? Number(timer.getAttribute('data-remaining-seconds') || 0)
                                        : 0;
                                    var active = !complete && (
                                        truthyAttribute(root.getAttribute('data-expedition-active')) ||
                                        root.classList.contains('is-expedition-active') ||
                                        (timer && timer.classList.contains('is-ticking') && remainingSeconds > 0)
                                    );
                                    return {active: !!active, complete: !!complete, remainingSeconds: remainingSeconds};
                                }
                                function resonanceOpen(root) {
                                    return Array.from(root.querySelectorAll(
                                        'dialog,[role="dialog"],[aria-modal="true"],.modal,.modal-dialog,' +
                                        '[class*="modal"],[class*="dialog"],[class*="confirm"],[class*="resonance"]'
                                    )).some(function(el) {
                                        return visible(el) && /резонанс|resonance/i.test(textOf(el));
                                    });
                                }
                                function acceptResonanceOffer() {
                                    var overlays = Array.from(document.querySelectorAll(
                                        'dialog,[role="dialog"],[aria-modal="true"],.modal,.modal-dialog,' +
                                        '[class*="modal"],[class*="dialog"],[class*="confirm"],[class*="resonance"]'
                                    )).filter(visible);
                                    var resonanceOverlays = overlays.filter(function(el) {
                                        return /резонанс|resonance/i.test(textOf(el));
                                    });
                                    if (!resonanceOverlays.length) return 'dialog_not_found';
                                    var acceptKey = runKey + ':resonanceAcceptClicked';
                                    if (sessionStorage.getItem(acceptKey) === '1') return 'already_clicked';

                                    // Deal text and its action can live in sibling containers.
                                    var button = null;
                                    for (var i = resonanceOverlays.length - 1; i >= 0; i--) {
                                        button = action(resonanceOverlays[i], /^принять${'$'}/i);
                                        if (button) break;
                                    }
                                    if (!button) {
                                        var pageAcceptButtons = Array.from(document.querySelectorAll(
                                            'button,a,[role="button"],input[type="submit"]'
                                        )).filter(function(el) {
                                            return visible(el) &&
                                                /^принять${'$'}/i.test(
                                                    textOf(el) || el.getAttribute('aria-label') || el.title || ''
                                                );
                                        });
                                        if (pageAcceptButtons.length === 1) button = pageAcceptButtons[0];
                                        else return 'accept_button_not_found visibleExactAcceptButtons=' + pageAcceptButtons.length +
                                            ' resonanceOverlays=' + resonanceOverlays.length;
                                    }
                                    try {
                                        button.click();
                                        sessionStorage.setItem(acceptKey, '1');
                                        send('RESONANCE_ACCEPT_CLICKED',
                                            'Clicked visible exact-label Accept for the current-expedition Resonance deal');
                                        return 'clicked';
                                    } catch (e) {
                                        return 'click_error=' + (e && e.message ? e.message : String(e));
                                    }
                                }
                                async function upgradeTowerHeadquarters() {
                                    var selectedKeys = [$selectedTowerUpgradeKeysJs];
                                    if (!selectedKeys.length) {
                                        send('HQ_UPGRADE_SKIPPED', 'reason=no_skills_selected');
                                        return 'no_skills_selected';
                                    }
                                    var sequenceKey = runKey + ':hqUpgradeSequenceDone';
                                    try {
                                        if (sessionStorage.getItem(sequenceKey) === '1') {
                                            return 'already_attempted_in_this_page_run';
                                        }
                                    } catch (e) {}

                                    var openButton = document.querySelector('button[data-tower-hq-open]');
                                    if (!openButton || !visible(openButton)) {
                                        send('HQ_OPEN_BUTTON_NOT_FOUND',
                                            'Could not find visible button[data-tower-hq-open]');
                                        return 'hq_open_button_not_found';
                                    }

                                    try {
                                        openButton.click();
                                        send('HQ_OPEN_CLICKED', 'Opening Tower headquarters');
                                    } catch (e) {
                                        send('HQ_OPEN_CLICK_ERROR', e && e.message ? e.message : String(e));
                                        return 'hq_open_click_failed';
                                    }

                                    function hqWait(ms) {
                                        return new Promise(function(resolve) { setTimeout(resolve, ms); });
                                    }

                                    var panel = null;
                                    for (var readyAttempt = 0; readyAttempt < 24; readyAttempt++) {
                                        panel = document.querySelector(
                                            '.mbf-tower-hq__panel.is-active[data-tower-hq-panel="banner"]'
                                        ) || document.querySelector('.mbf-tower-hq__upgrade-grid');
                                        if (panel) break;
                                        await hqWait(250);
                                    }

                                    if (!panel) {
                                        send('HQ_PANEL_NOT_FOUND',
                                            'Upgrade panel did not appear after opening headquarters');
                                        return 'hq_panel_not_found';
                                    }

                                    var order = [
                                        {key: 'assault', name: 'Натиск'},
                                        {key: 'fortitude', name: 'Оплот'},
                                        {key: 'hunt', name: 'Знание Хранителей'},
                                        {key: 'mentorship', name: 'Наставление'},
                                        {key: 'supply', name: 'Снабжение'}
                                    ].filter(function(item) {
                                        return selectedKeys.indexOf(item.key) !== -1;
                                    });
                                    send('HQ_UPGRADE_SELECTION', 'selected=' + selectedKeys.join(','));
                                    var upgraded = 0;
                                    var skipped = 0;

                                    function readUpgradeLevel(key) {
                                        var card = document.querySelector(
                                            '[data-hq-upgrade-card="' + key + '"]'
                                        );
                                        var levelNode = card ? card.querySelector('[data-hq-upgrade-level]') : null;
                                        var level = levelNode ? parseInt(textOf(levelNode), 10) : NaN;
                                        return isNaN(level) ? -1 : level;
                                    }

                                    function findUpgradeButton(item, useAll) {
                                        var card = document.querySelector(
                                            '[data-hq-upgrade-card="' + item.key + '"]'
                                        );
                                        var selector = useAll
                                            ? 'button[data-upgrade-mode="max"][data-upgrade-key="' + item.key + '"]'
                                            : 'button[data-hq-upgrade][data-upgrade-key="' + item.key + '"]:not([data-upgrade-mode="max"])';
                                        return {card: card, button: card ? card.querySelector(selector) : null};
                                    }

                                    async function clickUpgradeAndConfirm(item, button, mode) {
                                        var beforeLevel = readUpgradeLevel(item.key);
                                        if (beforeLevel < 0) {
                                            send('HQ_UPGRADE_SKIPPED',
                                                'key=' + item.key + ' name=' + item.name + ' reason=level_not_readable');
                                            return {ok: false, reason: 'level_not_readable'};
                                        }

                                        try {
                                            button.click();
                                            send('HQ_UPGRADE_CLICKED',
                                                'key=' + item.key + ' name=' + item.name +
                                                ' mode=' + mode + ' beforeLevel=' + beforeLevel);
                                        } catch (e) {
                                            send('HQ_UPGRADE_CLICK_ERROR',
                                                'key=' + item.key + ' mode=' + mode +
                                                ' error=' + (e && e.message ? e.message : String(e)));
                                            return {ok: false, reason: 'click_error'};
                                        }

                                        var afterLevel = beforeLevel;
                                        for (var updateAttempt = 0; updateAttempt < 16; updateAttempt++) {
                                            await hqWait(250);
                                            afterLevel = readUpgradeLevel(item.key);
                                            if (afterLevel > beforeLevel) break;
                                        }

                                        if (afterLevel > beforeLevel) {
                                            var gained = afterLevel - beforeLevel;
                                            upgraded += gained;
                                            send('HQ_UPGRADE_CONFIRMED',
                                                'key=' + item.key + ' levels=' + beforeLevel + '->' + afterLevel +
                                                ' gained=' + gained + ' mode=' + mode);
                                            await hqWait(250);
                                            return {ok: true, gained: gained};
                                        }

                                        send('HQ_UPGRADE_NOT_CONFIRMED',
                                            'key=' + item.key + ' beforeLevel=' + beforeLevel +
                                            ' afterLevel=' + afterLevel + ' mode=' + mode);
                                        return {ok: false, reason: 'level_not_changed'};
                                    }

                                    if (order.length === 1) {
                                        // With one skill selected, "На все" intentionally spends all available
                                        // Dark Crystals on that one skill.
                                        var onlyItem = order[0];
                                        var onlyControls = findUpgradeButton(onlyItem, true);
                                        if (!onlyControls.card || !onlyControls.button) {
                                            skipped++;
                                            send('HQ_UPGRADE_SKIPPED',
                                                'key=' + onlyItem.key + ' name=' + onlyItem.name +
                                                ' reason=max_button_not_found');
                                        } else if (onlyControls.button.disabled ||
                                                   onlyControls.button.getAttribute('aria-disabled') === 'true' ||
                                                   !visible(onlyControls.button)) {
                                            skipped++;
                                            send('HQ_UPGRADE_SKIPPED',
                                                'key=' + onlyItem.key + ' name=' + onlyItem.name +
                                                ' reason=disabled_or_insufficient_essence');
                                        } else {
                                            var maxResult = await clickUpgradeAndConfirm(
                                                onlyItem, onlyControls.button, 'all'
                                            );
                                            if (!maxResult.ok) skipped++;
                                        }
                                    } else if (order.length > 1) {
                                        // "На все" empties the entire resource balance into one skill.
                                        // For multiple selections, buy one level at a time in round-robin order
                                        // so every enabled skill gets a chance before resources run out.
                                        send('HQ_UPGRADE_ALLOCATION',
                                            'mode=round_robin_plus_one selected=' +
                                            order.map(function(item) { return item.key; }).join(','));
                                        var blockedKeys = {};
                                        var purchases = 0;
                                        var maxPurchases = 120;
                                        var anyProgress = true;

                                        while (anyProgress && purchases < maxPurchases) {
                                            anyProgress = false;

                                            for (var i = 0; i < order.length; i++) {
                                                if (purchases >= maxPurchases) break;
                                                var item = order[i];
                                                if (blockedKeys[item.key]) continue;

                                                var controls = findUpgradeButton(item, false);
                                                if (!controls.card || !controls.button) {
                                                    blockedKeys[item.key] = true;
                                                    skipped++;
                                                    send('HQ_UPGRADE_SKIPPED',
                                                        'key=' + item.key + ' name=' + item.name +
                                                        ' reason=plus_one_button_not_found');
                                                    continue;
                                                }

                                                if (controls.button.disabled ||
                                                    controls.button.getAttribute('aria-disabled') === 'true' ||
                                                    !visible(controls.button)) {
                                                    blockedKeys[item.key] = true;
                                                    skipped++;
                                                    send('HQ_UPGRADE_SKIPPED',
                                                        'key=' + item.key + ' name=' + item.name +
                                                        ' reason=disabled_or_insufficient_essence mode=plus_one');
                                                    continue;
                                                }

                                                var result = await clickUpgradeAndConfirm(
                                                    item, controls.button, 'plus_one'
                                                );
                                                if (result.ok) {
                                                    purchases += result.gained;
                                                    anyProgress = true;
                                                } else {
                                                    // Do not hammer the same control if the site did not confirm
                                                    // the change; continue distributing resources to other skills.
                                                    blockedKeys[item.key] = true;
                                                    skipped++;
                                                }
                                            }
                                        }

                                        if (purchases >= maxPurchases) {
                                            send('HQ_UPGRADE_SAFETY_STOP',
                                                'Reached maxPurchases=' + maxPurchases +
                                                '; stopping safely instead of unbounded clicking');
                                        }
                                    } else {
                                        skipped++;
                                        send('HQ_UPGRADE_SKIPPED', 'reason=no_skills_selected');
                                    }

                                    try { sessionStorage.setItem(sequenceKey, '1'); } catch (e) {}

                                    // Close the HQ panel before the normal expedition controls run.
                                    var closeButton = document.querySelector(
                                        '[data-tower-hq-close], [data-tower-hq-dismiss], .mbf-tower-hq__close,' +
                                        'button[aria-label*="Закрыть"], button[title*="Закрыть"]'
                                    );
                                    if (closeButton && visible(closeButton)) {
                                        try {
                                            closeButton.click();
                                            send('HQ_CLOSE_CLICKED', 'Closed headquarters panel');
                                        } catch (e) {}
                                    } else {
                                        try {
                                            document.dispatchEvent(new KeyboardEvent('keydown', {
                                                key: 'Escape', code: 'Escape', bubbles: true
                                            }));
                                            document.dispatchEvent(new KeyboardEvent('keyup', {
                                                key: 'Escape', code: 'Escape', bubbles: true
                                            }));
                                            send('HQ_CLOSE_ESCAPE_SENT', 'Sent Escape to close headquarters panel');
                                        } catch (e) {}
                                    }
                                    await hqWait(300);
                                    return 'upgraded=' + upgraded + ' skipped=' + skipped +
                                        ' order=' + selectedKeys.join(',');
                                }

                                async function finishAfterExpeditionStarted(detail) {
                                    var hqSummary = 'not_run';
                                    try {
                                        hqSummary = await upgradeTowerHeadquarters();
                                        send('HQ_UPGRADE_SEQUENCE_DONE', hqSummary);
                                    } catch (e) {
                                        hqSummary = 'error=' + (e && e.message ? e.message : String(e));
                                        send('HQ_UPGRADE_ERROR', hqSummary);
                                    }
                                    finish(true, detail + '; hq=' + hqSummary);
                                }

                                async function run(root, attempt) {
                                    function wait(ms) {
                                        return new Promise(function(resolve) { setTimeout(resolve, ms); });
                                    }
                                    function liveRoot() {
                                        return document.querySelector('[data-tower-root]') || root;
                                    }
                                    function findClaimButton() {
                                        var button = document.querySelector('[data-tower-claim]');
                                        if (button && visible(button)) return button;
                                        return action(document, /забрать\s+(награды|награду)|получить\s+награды|claim\s+reward/i);
                                    }
                                    function findStartButton(currentRoot) {
                                        return action(currentRoot, /отправить\s+отряд|начать\s+экспедицию|отправить\s+в\s+экспедицию|запустить\s+экспедицию|start\s+expedition|send\s+squad/i);
                                    }
                                    function findAcceptButton(currentRoot) {
                                        if (resonanceOpen(document) || resonanceOpen(currentRoot)) return null;
                                        var overlays = Array.from(document.querySelectorAll(
                                            'dialog,[role="dialog"],[aria-modal="true"],.modal,.modal-dialog,[class*="modal"],[class*="dialog"],[class*="confirm"]'
                                        )).filter(visible);
                                        for (var i = overlays.length - 1; i >= 0; i--) {
                                            var overlayText = textOf(overlays[i]);
                                            if (/резонанс|resonance/i.test(overlayText)) continue;
                                            var button = action(
                                                overlays[i],
                                                /^(принять|подтвердить|подтверждаю|да,?\s*отправить|отправить\s+экспедицию)${'$'}/i
                                            );
                                            if (button) return button;
                                        }
                                        // Some Tower confirmations are rendered without dialog semantics.
                                        return action(currentRoot, /^принять${'$'}/i) || action(document, /^принять${'$'}/i);
                                    }
                                    var lastTimerSignature = '';
                                    function readAndPublishTimer(currentRoot) {
                                        var state = expeditionState(currentRoot);
                                        var signature = [
                                            state.active ? 1 : 0,
                                            state.complete ? 1 : 0,
                                            state.remainingSeconds
                                        ].join(':');
                                        if (signature !== lastTimerSignature) {
                                            lastTimerSignature = signature;
                                            try {
                                                AndroidTowerBridge.onTowerTimerState(
                                                    state.remainingSeconds,
                                                    state.active,
                                                    state.complete
                                                );
                                            } catch (timerError) {
                                                send('TIMER_BRIDGE_ERROR', timerError && timerError.message ? timerError.message : String(timerError));
                                            }
                                        }
                                        return state;
                                    }
                                    async function claimRewards(currentRoot) {
                                        var button = findClaimButton();
                                        if (!button) return 'none';
                                        var state = readAndPublishTimer(currentRoot);
                                        // The visible claim button is authoritative. Timer attributes can be stale
                                        // for a moment after expiry, so never let them suppress this click.
                                        send('CLAIM_BUTTON_FOUND',
                                            'selector=' + (button.matches('[data-tower-claim]') ? '[data-tower-claim]' : 'text_fallback') +
                                            ' text=' + textOf(button));
                                        var claimKey = runKey + ':claimClicked';
                                        if (sessionStorage.getItem(claimKey) !== '1') {
                                            sessionStorage.setItem(claimKey, '1');
                                            send('CLAIM_CLICKED', 'Visible reward button found; invoking the site UI handler');
                                            try {
                                                button.click();
                                            } catch (e) {
                                                send('CLAIM_CLICK_ERROR', e && e.message ? e.message : String(e));
                                                return 'failed';
                                            }
                                        } else {
                                            send('CLAIM_CLICKED_PREVIOUSLY', 'Not clicking reward button twice in the same run');
                                        }
                                        for (var claimAttempt = 0; claimAttempt < 24; claimAttempt++) {
                                            await wait(350);
                                            currentRoot = liveRoot();
                                            state = readAndPublishTimer(currentRoot);
                                            var currentButton = findClaimButton();
                                            if (!currentButton) {
                                                send('CLAIM_CONFIRMED', 'Visible reward button disappeared after click');
                                                return 'claimed';
                                            }
                                        }
                                        send('CLAIM_NOT_CONFIRMED', 'Reward button remains visible after click');
                                        return 'failed';
                                    }

                                    if (!root) {
                                        // The live site's reward button may exist even if its wrapper selector
                                        // changes. Never ignore a visible reward just because the root attribute
                                        // is missing; use the document element as a safe query root.
                                        var globalClaimButton = findClaimButton();
                                        if (globalClaimButton) {
                                            root = document.documentElement;
                                            send('TOWER_ROOT_FALLBACK_CLAIM_BUTTON',
                                                'Using document root because visible [data-tower-claim] exists');
                                        } else if (attempt < 20) {
                                            setTimeout(function() {
                                                run(document.querySelector('[data-tower-root]'), attempt + 1);
                                            }, 500);
                                            return;
                                        } else {
                                            send('ROOT_NOT_FOUND', 'No [data-tower-root] or visible reward button after 10 seconds');
                                            finish(false, 'tower_root_not_found');
                                            return;
                                        }
                                    }

                                    // Collect the completed expedition and send the next squad first.
                                    // Headquarters upgrades run only after this run confirms the new timer.

                                    var currentRoot = liveRoot();
                                    var state = readAndPublishTimer(currentRoot);
                                    send('EXPEDITION_STATE',
                                        'active=' + state.active +
                                        ' complete=' + state.complete +
                                        ' remainingSeconds=' + state.remainingSeconds +
                                        ' rootActiveAttr=' + currentRoot.getAttribute('data-expedition-active') +
                                        ' rootCompleteAttr=' + currentRoot.getAttribute('data-expedition-complete'));

                                    // 1) If the timer is running, the squad has already been sent.
                                    // Never press "Send squad" a second time.
                                    if (state.active && !state.complete && state.remainingSeconds > 0 &&
                                        !findClaimButton()) {
                                        send('EXPEDITION_ALREADY_RUNNING', 'Timer is visible; no new squad will be sent');
                                        finish(true, 'expedition_already_active; remainingSeconds=' + state.remainingSeconds);
                                        return;
                                    }

                                    // 2) If rewards are ready, click the real visible button and wait for
                                    // the site to update before deciding whether another expedition can start.
                                    var claimResult = await claimRewards(currentRoot);
                                    if (claimResult === 'active') {
                                        state = readAndPublishTimer(liveRoot());
                                        finish(true, 'expedition_already_active; remainingSeconds=' + state.remainingSeconds);
                                        return;
                                    }
                                    if (claimResult === 'failed') {
                                        finish(false, 'claim_not_confirmed');
                                        return;
                                    }

                                    // 3) After claiming (or when there were no rewards), wait for the live
                                    // page to expose the next valid state: timer, reward button, or Send squad.
                                    var startButton = null;
                                    for (var stateAttempt = 0; stateAttempt < 24; stateAttempt++) {
                                        currentRoot = liveRoot();
                                        state = readAndPublishTimer(currentRoot);
                                        if (state.active && !state.complete && state.remainingSeconds > 0 &&
                                            !findClaimButton()) {
                                            send('EXPEDITION_ALREADY_RUNNING', 'Timer appeared while refreshing Tower state');
                                            finish(true, 'expedition_already_active; remainingSeconds=' + state.remainingSeconds);
                                            return;
                                        }
                                        var newClaimButton = findClaimButton();
                                        if (newClaimButton) {
                                            claimResult = await claimRewards(currentRoot);
                                            if (claimResult === 'failed') {
                                                finish(false, 'claim_not_confirmed');
                                                return;
                                            }
                                            if (claimResult === 'active') {
                                                state = readAndPublishTimer(liveRoot());
                                                finish(true, 'expedition_already_active; remainingSeconds=' + state.remainingSeconds);
                                                return;
                                            }
                                        }
                                        startButton = findStartButton(currentRoot);
                                        if (startButton) break;
                                        await wait(350);
                                    }

                                    // Contract for this task: if "Send squad" is absent, do not create a
                                    // second expedition. The site is either running the timer or is in its
                                    // post-expedition/reward transition state.
                                    if (!startButton) {
                                        currentRoot = liveRoot();
                                        state = readAndPublishTimer(currentRoot);
                                        send('START_BUTTON_ABSENT_ASSUME_SENT',
                                            'No visible Send squad button; avoiding a duplicate expedition; active=' +
                                            state.active + ' complete=' + state.complete +
                                            ' remainingSeconds=' + state.remainingSeconds);
                                        finish(true, state.remainingSeconds > 0
                                            ? 'expedition_already_active; remainingSeconds=' + state.remainingSeconds
                                            : 'start_button_absent_existing_expedition_state');
                                        return;
                                    }

                                    var resonanceAcceptedBeforeStart = false;
                                    if (resonanceOpen(document) || resonanceOpen(currentRoot)) {
                                        var preStartResonanceResult = acceptResonanceOffer();
                                        if (preStartResonanceResult === 'clicked' ||
                                            preStartResonanceResult === 'already_clicked') {
                                            resonanceAcceptedBeforeStart = true;
                                            send('RESONANCE_OFFER_ACCEPTED',
                                                'Resonance offer accepted; wait for timer without sending twice');
                                        } else {
                                            send('RESONANCE_ACCEPT_NOT_POSSIBLE',
                                                'Visible Resonance dialog could not be accepted: ' + preStartResonanceResult);
                                            finish(false, 'resonance_accept_failed=' + preStartResonanceResult);
                                            return;
                                        }
                                    }

                                    // 4) Send the squad using the site's own button handler.
                                    // If Resonance acceptance already submitted it, do not click twice.
                                    var startKey = runKey + ':startClicked';
                                    if (resonanceAcceptedBeforeStart) {
                                        send('START_SKIPPED_AFTER_RESONANCE_ACCEPT',
                                            'Resonance acceptance already submitted; waiting for timer');
                                    } else if (sessionStorage.getItem(startKey) !== '1') {
                                        sessionStorage.setItem(startKey, '1');
                                        send('START_CLICKED', 'Clicking visible Send squad button');
                                        try {
                                            startButton.click();
                                        } catch (e) {
                                            send('START_CLICK_ERROR', e && e.message ? e.message : String(e));
                                            finish(false, 'start_click_failed');
                                            return;
                                        }
                                    } else {
                                        send('START_CLICKED_PREVIOUSLY', 'Waiting for the result of the previous click; will not click twice');
                                    }

                                    // 5) The site may show a confirmation dialog after Send squad.
                                    // Click its "Принять" button, but never accept a resonance/purchase dialog.
                                    var acceptButton = null;
                                    for (var acceptAttempt = 0; acceptAttempt < 36; acceptAttempt++) {
                                        await wait(300);
                                        currentRoot = liveRoot();
                                        state = readAndPublishTimer(currentRoot);
                                        if (resonanceOpen(document) || resonanceOpen(currentRoot)) {
                                            var resonanceResult = acceptResonanceOffer();
                                            if (resonanceResult === 'clicked') {
                                                await wait(400);
                                                continue;
                                            }
                                            if (resonanceResult === 'already_clicked') {
                                                await wait(300);
                                                continue;
                                            }
                                            send('RESONANCE_ACCEPT_NOT_POSSIBLE',
                                                'Visible Resonance dialog could not be accepted: ' + resonanceResult);
                                            finish(false, 'resonance_accept_failed=' + resonanceResult);
                                            return;
                                        }
                                        if (state.active && !state.complete && state.remainingSeconds > 0) {
                                            send('START_CONFIRMED', 'Timer became visible after Send squad');
                                            await finishAfterExpeditionStarted(
                                                'expedition_started; remainingSeconds=' + state.remainingSeconds
                                            );
                                            return;
                                        }
                                        acceptButton = findAcceptButton(currentRoot);
                                        if (acceptButton) {
                                            var acceptKey = runKey + ':acceptClicked';
                                            if (sessionStorage.getItem(acceptKey) !== '1') {
                                                sessionStorage.setItem(acceptKey, '1');
                                                send('ACCEPT_CLICKED', 'Clicking the Tower confirmation button');
                                                try {
                                                    acceptButton.click();
                                                } catch (e) {
                                                    send('ACCEPT_CLICK_ERROR', e && e.message ? e.message : String(e));
                                                    finish(false, 'accept_click_failed');
                                                    return;
                                                }
                                            } else {
                                                send('ACCEPT_CLICKED_PREVIOUSLY', 'Confirmation already clicked in this run');
                                            }
                                            break;
                                        }
                                    }

                                    if (!acceptButton) {
                                        send('ACCEPT_BUTTON_NOT_FOUND', 'No visible confirmation button; checking whether the timer started directly');
                                    }

                                    // 6) Wait until the site shows an active expedition timer or a claimable reward.
                                    for (var verifyAttempt = 0; verifyAttempt < 45; verifyAttempt++) {
                                        await wait(350);
                                        currentRoot = liveRoot();
                                        state = readAndPublishTimer(currentRoot);
                                        if (resonanceOpen(document) || resonanceOpen(currentRoot)) {
                                            var resonanceResult = acceptResonanceOffer();
                                            if (resonanceResult === 'clicked') {
                                                await wait(400);
                                                continue;
                                            }
                                            if (resonanceResult === 'already_clicked') {
                                                await wait(300);
                                                continue;
                                            }
                                            send('RESONANCE_ACCEPT_NOT_POSSIBLE',
                                                'Visible Resonance dialog could not be accepted: ' + resonanceResult);
                                            finish(false, 'resonance_accept_failed=' + resonanceResult);
                                            return;
                                        }
                                        if (state.active && !state.complete && state.remainingSeconds > 0 &&
                                            !findClaimButton()) {
                                            send('START_CONFIRMED', 'Live Tower DOM exposes the active timer');
                                            await finishAfterExpeditionStarted(
                                                'expedition_started; remainingSeconds=' + state.remainingSeconds
                                            );
                                            return;
                                        }
                                        if (findClaimButton()) {
                                            claimResult = await claimRewards(currentRoot);
                                            if (claimResult === 'claimed') {
                                                send('CLAIM_AFTER_START', 'Reward was claimed; refreshing Tower state');
                                                currentRoot = liveRoot();
                                                state = readAndPublishTimer(currentRoot);
                                            } else if (claimResult === 'failed') {
                                                finish(false, 'claim_not_confirmed_after_start');
                                                return;
                                            }
                                        }
                                    }

                                    currentRoot = liveRoot();
                                    state = readAndPublishTimer(currentRoot);
                                    startButton = findStartButton(currentRoot);
                                    if (!startButton) {
                                        send('START_BUTTON_ABSENT_ASSUME_SENT',
                                            'Send squad button disappeared after confirmation; no duplicate click will be attempted');
                                        if (state.remainingSeconds > 0) {
                                            await finishAfterExpeditionStarted(
                                                'expedition_started; remainingSeconds=' + state.remainingSeconds
                                            );
                                        } else {
                                            finish(true, 'send_and_accept_processed; waiting_for_tower_timer_render');
                                        }
                                        return;
                                    }

                                    send('START_NOT_CONFIRMED',
                                        'Send squad remains available and the site did not expose an active timer');
                                    finish(false, 'start_not_confirmed');
                                }
                                run(document.querySelector('[data-tower-root]'), 0).catch(function(e) {
                                    send('TASK_ERROR', e && e.message ? e.message : String(e));
                                    finish(false, 'tower_script_error');
                                });
                            })();
                        """.trimIndent()

                webView.webViewClient = object : AutomationWebViewClient(account.username, webView) {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (url == null) return
                        log(account.username, "TOWER: PAGE_FINISHED url=$url")
                        val uri = try { Uri.parse(url) } catch (_: Exception) { null }
                        if (uri?.host == "mangabuff.ru" && uri.path.orEmpty().startsWith("/login")) {
                            finish(false, "login_required")
                            return
                        }
                        if (uri?.host != "mangabuff.ru" || !uri.path.orEmpty().startsWith("/tower")) return

                        view?.evaluateJavascript(towerScript, null)
                    }
                }
                // The HQ sequence may confirm upgrades one at a time after the expedition starts.
                // Allow that bounded sequence to finish instead of failing at the old 75-second limit.
                watchdog = Runnable { finish(false, "tower_page_timeout") }
                mainHandler.postDelayed(watchdog!!, 120_000L)
                log(account.username, "TOWER: OPEN url=https://mangabuff.ru/tower")
                updateStatus(account, "🗼 Башня Разлома: проверка состояния…", true, "Башня Разлома", 0.05f)
                webView.loadUrl("https://mangabuff.ru/tower")
                scriptRecovery = object : Runnable {
                    private var attempt = 0
                    override fun run() {
                        if (completed.get() || attempt >= 12) return
                        attempt++
                        val currentUrl = webView.url.orEmpty()
                        val currentUri = try { Uri.parse(currentUrl) } catch (_: Exception) { null }
                        if (currentUri?.host != "mangabuff.ru" || !currentUri.path.orEmpty().startsWith("/tower")) {
                            mainHandler.postDelayed(this, 2500L)
                            return
                        }
                        val markerCheck = "(function(){return window.__mbTowerPassActive === 'mb_tower_$runToken' ? 'RUNNING' : 'MISSING';})()"
                        try {
                            webView.evaluateJavascript(markerCheck) { raw ->
                                if (completed.get()) return@evaluateJavascript
                                if (raw.orEmpty().contains("RUNNING")) {
                                    log(account.username, "TOWER: SCRIPT_RECOVERY_NOT_NEEDED attempt=$attempt")
                                    return@evaluateJavascript
                                }
                                log(account.username, "TOWER: SCRIPT_RECOVERY_INJECT attempt=$attempt url=${currentUrl.take(140)}")
                                try {
                                    webView.evaluateJavascript(towerScript, null)
                                } catch (e: Exception) {
                                    log(account.username, "TOWER: SCRIPT_RECOVERY_ERROR error=${e.message}", true)
                                }
                                if (attempt < 12 && !completed.get()) mainHandler.postDelayed(this, 2500L)
                            }
                        } catch (e: Exception) {
                            log(account.username, "TOWER: SCRIPT_RECOVERY_CHECK_ERROR error=${e.message}", true)
                            if (attempt < 12 && !completed.get()) mainHandler.postDelayed(this, 2500L)
                        }
                    }
                }
                mainHandler.postDelayed(scriptRecovery!!, 6000L)
            } catch (e: Exception) {
                finish(false, "startup_error=" + (e.message ?: "unknown"))
            }
        }
    }

    // =========================================================
    // ADS
    // =========================================================

    /**
     * Preflight check on the real MangaBuff balance page.
     * The local counter is only a cache; the site's toast is authoritative.
     * We poll briefly because the limit toast can be inserted asynchronously.
     */
    private suspend fun checkAdsDailyLimitOnSite(
        account: MangaBuffAccount,
        webView: WebView
    ): String? = suspendCancellableCoroutine { continuation ->
        var resumed = false
        var pollRunnable: Runnable? = null
        val startedAt = SystemClock.elapsedRealtime()
        val timeoutMs = 4000L

        fun finish(result: String?) {
            if (resumed) return
            resumed = true
            pollRunnable?.let { mainHandler.removeCallbacks(it) }
            if (continuation.isActive) continuation.resume(result)
        }

        mainHandler.post {
            fun poll() {
                if (resumed || !continuation.isActive) return

                val script = """
                    (function() {
                        try {
                            function visible(el) {
                                if (!el) return false;
                                var s = getComputedStyle(el);
                                return s.display !== 'none' &&
                                       s.visibility !== 'hidden' &&
                                       parseFloat(s.opacity || '1') > 0 &&
                                       (el.offsetWidth > 0 || el.offsetHeight > 0);
                            }

                            function textOf(el) {
                                return (el && (el.innerText || el.textContent) || '')
                                    .replace(/\\s+/g, ' ')
                                    .trim();
                            }

                            var exact = 'нельзя смотреть рекламу больше 3 раз в сутки';
                            var nodes = Array.from(document.querySelectorAll(
                                '.toast-message, .toast.toast-error, [role="alert"]'
                            ));

                            for (var i = 0; i < nodes.length; i++) {
                                var node = nodes[i];
                                var text = textOf(node).toLowerCase();
                                if (visible(node) &&
                                    (text.indexOf(exact) !== -1 ||
                                     text.indexOf('нельзя смотреть рекламу больше') !== -1)) {
                                    return JSON.stringify({found:true,text:textOf(node)});
                                }
                            }

                            return JSON.stringify({found:false,text:''});
                        } catch (e) {
                            return JSON.stringify({found:false,text:'',error:String(e)});
                        }
                    })();
                """.trimIndent()

                try {
                    webView.evaluateJavascript(script) { raw ->
                        if (resumed) return@evaluateJavascript

                        val value = raw.orEmpty()
                        if (value.contains("\\\"found\\\":true")) {
                            val toastText = ADS_DAILY_LIMIT_MESSAGE
                            log(account.username, "ADS: DAILY_LIMIT_TOAST_FOUND source=SITE_PREFLIGHT text=$toastText")
                            finish(toastText)
                            return@evaluateJavascript
                        }

                        if (SystemClock.elapsedRealtime() - startedAt >= timeoutMs) {
                            log(account.username, "ADS: DAILY_LIMIT_PREFLIGHT_CLEAR timeout=" + timeoutMs + "ms")
                            finish(null)
                        } else {
                            pollRunnable = Runnable { poll() }
                            mainHandler.postDelayed(pollRunnable!!, 250L)
                        }
                    }
                } catch (e: Exception) {
                    log(account.username, "ADS: DAILY_LIMIT_PREFLIGHT_ERROR error=" + e.message, true)
                    finish(null)
                }
            }

            log(account.username, "ADS: DAILY_LIMIT_PREFLIGHT_START url=" + webView.url.orEmpty())
            poll()
        }

        continuation.invokeOnCancellation {
            mainHandler.post {
                pollRunnable?.let { mainHandler.removeCallbacks(it) }
                pollRunnable = null
            }
        }
    }

    private suspend fun runAdsTask(
        account: MangaBuffAccount,
        webView: WebView
    ): Boolean {
        /*
         * MangaBuff currently pays at most 3 rewarded ads per account/day.
         *
         * The local daily counter is only an optimization: it prevents us from
         * opening an ad session that we already know cannot succeed. The website
         * remains the source of truth and is checked again inside watchSingleAd
         * by looking for the actual daily-limit toast.
         */
        val dailyAdsToday = if (dailyStats.day == currentStatsDay()) {
            dailyStats.ads.coerceAtLeast(0)
        } else {
            0
        }
        val dailyLimit = ADS_DAILY_LIMIT
        val remainingAds = (dailyLimit - dailyAdsToday).coerceAtLeast(0)
        // The setting is intentionally not user-editable: attempt all remaining ads up to 3/day.
        val target = remainingAds
        var adsDone = 0

        log(
            account.username,
            "TASK: ADS_START localToday=$dailyAdsToday " +
                "remaining=$remainingAds target=$target dailyLimit=$dailyLimit"
        )

        if (remainingAds == 0) {
            log(
                account.username,
                "ADS: DAILY_LIMIT_REACHED source=LOCAL_COUNTER today=$dailyAdsToday limit=$dailyLimit"
            )
            updateStatus(account, "📺 Реклама: лимит на сегодня уже достигнут", false, "Реклама", 1f)
            return true
        }

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

                when (success) {
                    AdWatchResult.Rewarded -> {
                        adsDone++
                        addDaily(account) { it.copy(ads = (it.ads + 1).coerceAtMost(dailyLimit)) }
                        log(account.username, "TASK: ADS_COMPLETED_COUNT ad=" + adsDone + "/" + target + " daily=" + dailyStats.ads + "/" + dailyLimit)
                        if (adsDone < target) delay(ADS_NEXT_DELAY_MS)
                    }
                    AdWatchResult.DailyLimit -> {
                        if (dailyStats.ads < dailyLimit) addDaily(account) { it.copy(ads = dailyLimit) }
                        log(account.username, "ADS: DAILY_LIMIT_REACHED source=SITE_TOAST local=" + dailyStats.ads + "/" + dailyLimit)
                        updateStatus(account, "📺 Реклама: лимит " + dailyLimit + "/" + dailyLimit + " на сегодня", false, "Реклама", 1f)
                        return true
                    }
                    AdWatchResult.Failed -> {
                        log(account.username, "ADS: WATCH_FAILED completed=" + adsDone + "/" + target, true)
                        return false
                    }
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
    ): AdWatchResult = suspendCancellableCoroutine { continuation ->
        var resumed = false

        fun safeResume(result: AdWatchResult) {
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
                    safeResume(AdWatchResult.Rewarded)
                }

                @JavascriptInterface
                fun onAdFailed(reason: String) {
                    clearAdViewingLock()
                    log(account.username, "ADS: FAILED reason=$reason", true)
                    safeResume(AdWatchResult.Failed)
                }

                @JavascriptInterface
                fun onDailyLimit() {
                    clearAdViewingLock()
                    log(account.username, "ADS: DAILY_LIMIT_REACHED")
                    safeResume(AdWatchResult.DailyLimit)
                }

                @JavascriptInterface
                fun onAutoRewardClaimRequested() {
                    mainHandler.post {
                        val success = try {
                            autoClaimRewardFromAccessibility(account.username, webView)
                        } catch (e: Exception) {
                            log(
                                account.username,
                                "ADS: ACCESSIBILITY_REWARD_EXCEPTION error=" + e.message,
                                true
                            )
                            false
                        }

                        log(
                            account.username,
                            "ADS: ACCESSIBILITY_REWARD_REQUEST result=" + success
                        )

                        try {
                            webView.evaluateJavascript(
                                "window.__mbAdsAccessibilityClaimResult && " +
                                    "window.__mbAdsAccessibilityClaimResult(" +
                                    success + ");",
                                null
                            )
                        } catch (e: Exception) {
                            log(
                                account.username,
                                "ADS: ACCESSIBILITY_CALLBACK_ERROR error=" + e.message,
                                true
                            )
                        }
                    }
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

                                // Diagnostic state for the real user's close action.
                                // We intentionally never synthesize the rewarded-ad tap.
                                var closeWasVisible = false;
                                var closeAvailableLogged = false;

                                var rewardClaimAttempts = 0;
                                var rewardClaimPending = false;
                                var rewardClaimFinished = false;

                                window.__mbAdsAccessibilityClaimResult = function(success) {
                                    rewardClaimPending = false;

                                    if (finished) return;

                                    AndroidAds.onStateLog(
                                        "ACCESSIBILITY_REWARD_RESULT",
                                        "success=" + !!success +
                                            " attempts=" + rewardClaimAttempts
                                    );

                                    if (success) {
                                        rewardClaimFinished = true;
                                        clearInterval(watchTimer);

                                        AndroidAds.onStateLog(
                                            "REWARD_BUTTON_CLICKED",
                                            "source=ANDROID_ACCESSIBILITY"
                                        );

                                        setTimeout(function() {
                                            verifyReward(0);
                                        }, 750);
                                    }
                                };

                                function textOf(el) {
                                    return (el && (el.innerText || el.textContent) || "")
                                        .replace(/\s+/g, " ")
                                        .trim();
                                }

                                function findWatchButton() {
                                    var direct = document.querySelector(
                                        "button.wallet-panel__action.wallet-panel__action--ads.user-quest__watch-ads-btn"
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
                                    var t = String(text || "").replace(/\\s+/g, " ").trim().toLowerCase();
                                    var exact = "нельзя смотреть рекламу больше 3 раз в сутки";

                                    return t.indexOf(exact) !== -1 ||
                                           t.indexOf("нельзя смотреть рекламу больше") !== -1 ||
                                           t.indexOf("лимит") !== -1 ||
                                           (t.indexOf("дост") !== -1 && t.indexOf("заверш") !== -1);
                                }

                                function findDailyLimitToast() {
                                    var nodes = Array.from(document.querySelectorAll(".toast-message"));
                                    for (var i = 0; i < nodes.length; i++) {
                                        var text = textOf(nodes[i]);
                                        if (isDailyLimitText(text)) return text;
                                    }
                                    return "";
                                }

                                function checkDailyLimitToast() {
                                    var toastText = findDailyLimitToast();
                                    if (!toastText) return false;

                                    AndroidAds.onStateLog(
                                        "DAILY_LIMIT_TOAST_FOUND",
                                        "text=" + toastText
                                    );
                                    finished = true;
                                    window.__mbAdsRunnerActive = false;
                                    AndroidAds.onDailyLimit();
                                    return true;
                                }

                                /*
                                 * MangaBuff invokes Yandex Rewarded through
                                 * Ya.Context.AdvManager.render(). The Rewarded contract
                                 * exposes onRewarded(true) after the reward condition is met.
                                 * Hook it as an additional completion signal and diagnostic.
                                 * The MangaBuff server balance remains authoritative.
                                 */
                                window.__mbYandexRewardHookState = { rewarded: false, callbackValue: null };

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
                                    // Keep the exact selector first; class fallbacks are only
                                    // for older Yandex variants.
                                    return deepQuery(
                                        "[data-fullscreen-element=\"timer\"]," +
                                        "[data-fullscreen-element-name=\"timer\"]," +
                                        "[class*=\"timer\"]"
                                    );
                                }

                                var lastYandexSurfaceDiagAt = 0;

                                function logYandexSurfaceDiagnostics(force) {
                                    var now = Date.now();
                                    if (!force && now - lastYandexSurfaceDiagAt < 5000) return;
                                    lastYandexSurfaceDiagAt = now;

                                    try {
                                        var markers = document.querySelectorAll(
                                            "[data-fullscreen-element], [data-fullscreen-element-name]"
                                        ).length;
                                        var iframes = document.querySelectorAll("iframe").length;
                                        var shadowHosts = 0;
                                        var nodes = Array.from(document.querySelectorAll("*"));

                                        for (var i = 0; i < nodes.length; i++) {
                                            if (nodes[i] && nodes[i].shadowRoot) shadowHosts++;
                                        }

                                        var timer = deepQuery("[data-fullscreen-element=\"timer\"]");
                                        var close = deepQuery("[data-fullscreen-element=\"close\"]");

                                        AndroidAds.onStateLog(
                                            "YANDEX_SURFACE_DIAG",
                                            "markers=" + markers +
                                            " iframes=" + iframes +
                                            " shadowHosts=" + shadowHosts +
                                            " timerFound=" + !!timer +
                                            " closeFound=" + !!close +
                                            " url=" + (location.href || "")
                                        );
                                    } catch (e) {
                                        AndroidAds.onStateLog(
                                            "YANDEX_SURFACE_DIAG_ERROR",
                                            "error=" + (e && e.message ? e.message : String(e))
                                        );
                                    }
                                }

                                function readYandexTimerSeconds() {
                                    var timer = findTimer();
                                    if (!timer) {
                                        logYandexSurfaceDiagnostics(false);
                                        return null;
                                    }

                                    try {
                                        /*
                                         * Do not require getBoundingClientRect() visibility here.
                                         * Yandex may keep the timer in an overlay/shadow tree whose
                                         * geometry is not exposed to the page document even though
                                         * the user can see it. The presence of the exact timer node
                                         * is enough to read its countdown.
                                         */
                                        var text = String(
                                            timer.innerText ||
                                            timer.textContent ||
                                            timer.getAttribute("aria-label") ||
                                            timer.getAttribute("title") ||
                                            ""
                                        ).replace(/\s+/g, " ").trim();

                                        var seconds = null;

                                        // Handle MM:SS / HH:MM:SS first.
                                        var clock = text.match(/(?:^|\s)(\d{1,2}):(\d{2})(?:\s|$)/);
                                        if (clock) {
                                            seconds = parseInt(clock[2], 10);
                                        } else {
                                            var match = text.match(/(\d{1,2})/);
                                            if (match) seconds = parseInt(match[1], 10);
                                        }

                                        if (seconds === null || isNaN(seconds)) {
                                            if (window.__mbLastYandexTimerText !== text) {
                                                window.__mbLastYandexTimerText = text;
                                                AndroidAds.onStateLog(
                                                    "YANDEX_TIMER",
                                                    "text=" + JSON.stringify(text) + " parsed=?"
                                                );
                                            }
                                            return null;
                                        }

                                        if (
                                            window.__mbLastYandexTimerValue !== seconds ||
                                            window.__mbLastYandexTimerText !== text
                                        ) {
                                            window.__mbLastYandexTimerValue = seconds;
                                            window.__mbLastYandexTimerText = text;
                                            AndroidAds.onStateLog(
                                                "YANDEX_TIMER",
                                                "text=" + JSON.stringify(text) + " seconds=" + seconds
                                            );
                                        }

                                        return seconds;
                                    } catch (e) {
                                        AndroidAds.onStateLog(
                                            "YANDEX_TIMER_ERROR",
                                            "error=" + (e && e.message ? e.message : String(e))
                                        );
                                        return null;
                                    }
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

                                /*
                                 * Rewarded-ad controls must remain under the ad SDK/user.
                                 * Do not synthesize a native tap or invoke a hidden/iframe X.
                                 * When the control is inaccessible to the parent document,
                                 * we only record geometry for diagnostics and wait for the
                                 * user to close the ad normally.
                                 */
                                function requestNativeCloseTap(closeElement) {
                                    var width = window.innerWidth || document.documentElement.clientWidth || 0;
                                    var height = window.innerHeight || document.documentElement.clientHeight || 0;

                                    if (width <= 0 || height <= 0) {
                                        AndroidAds.onStateLog("NATIVE_CLOSE_SKIP", "invalid_viewport");
                                        return false;
                                    }

                                    var exact = null;
                                    try {
                                        if (closeElement && closeElement.getBoundingClientRect) {
                                            exact = getTopViewportRect(closeElement);
                                        }
                                    } catch (e) {
                                        AndroidAds.onStateLog(
                                            "NATIVE_CLOSE_RECT_ERROR",
                                            "error=" + (e && e.message ? e.message : String(e))
                                        );
                                    }

                                    if (!exact || exact.width <= 0 || exact.height <= 0) {
                                        AndroidAds.onStateLog(
                                            "NATIVE_CLOSE_UNAVAILABLE",
                                            "close_control_found_but_geometry_unavailable"
                                        );
                                        return false;
                                    }

                                    var tapX = exact.left + exact.width / 2;
                                    var tapY = exact.top + exact.height / 2;

                                    AndroidAds.onStateLog(
                                        "NATIVE_CLOSE_AUTO_TAP",
                                        "x=" + tapX +
                                            " y=" + tapY +
                                            " rect=" + exact.width + "x" + exact.height +
                                            " selector=data-fullscreen-element:close"
                                    );

                                    try {
                                        AndroidAds.onCloseTapRequested(
                                            tapX,
                                            tapY,
                                            "Yandex data-fullscreen-element=close"
                                        );
                                        return true;
                                    } catch (e) {
                                        AndroidAds.onStateLog(
                                            "NATIVE_CLOSE_AUTO_TAP_ERROR",
                                            "error=" + (e && e.message ? e.message : String(e))
                                        );
                                        return false;
                                    }
                                }

                                function verifyReward(attempt) {
                                    if (finished) return;

                                    var diamondNow = readDiamondBalance(document);
                                    var btnNow = findWatchButton();
                                    var buttonCountNow = readButtonCount(btnNow);
                                    var diamondConfirmed = initialDiamond !== null &&
                                        diamondNow !== null && diamondNow >= initialDiamond + 7;
                                    /*
                                     * The button counter is only a UI/state signal. It can
                                     * change before MangaBuff has actually credited the reward
                                     * to the account. Never finish the ad task from that signal.
                                     * The real account balance (+7 diamonds) is authoritative.
                                     */
                                    if (diamondConfirmed) {
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

                                    /*
                                     * The reward is credited only after the real fullscreen
                                     * close action. Keep checking the server after that close;
                                     * the balance page may otherwise be served from an
                                     * intermediate/cache layer for a short time.
                                     *
                                     * The query parameter makes every verification request
                                     * unique. This does NOT interact with the ad itself.
                                     */
                                    var balanceUrl = "/balance?_mb_reward_check=" + Date.now();

                                    fetch(balanceUrl, {
                                        method: "GET",
                                        credentials: "include",
                                        cache: "no-store",
                                        headers: {
                                            "Accept": "text/html,application/xhtml+xml",
                                            "Cache-Control": "no-cache",
                                            "Pragma": "no-cache"
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

                                            if (attempt >= 60) {
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

                                            /*
                                             * Right after the user closes the fullscreen ad,
                                             * MangaBuff normally credits the +7 reward within
                                             * a couple of seconds. Poll faster during the first
                                             * 10 seconds, then fall back to the normal cadence.
                                             */
                                            var nextDelay = attempt < 20 ? 500 : 1000;
                                            rewardPoll = setTimeout(function() {
                                                verifyReward(attempt + 1);
                                            }, nextDelay);
                                        } catch (e) {
                                            if (attempt >= 60) {
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

                                var nativeCloseAttempts = 0;
                                var nativeCloseRetryTimer = null;
                                var nativeCloseRequested = false;

                                function finishNativeCloseRetryTimer() {
                                    if (nativeCloseRetryTimer) {
                                        clearTimeout(nativeCloseRetryTimer);
                                        nativeCloseRetryTimer = null;
                                    }
                                }

                                /*
                                 * Screenshot-verified position of the Yandex fullscreen close
                                 * cross on the actual 384x850 CSS viewport:
                                 *
                                 *   cross center ~= (354, 64) CSS px
                                 *
                                 * It is immediately above the "U" in our overlaid
                                 * "Вернуться в UI" button. The previous (364,20) point was
                                 * demonstrably wrong: it landed in the status-bar area and did
                                 * not close the ad.
                                 *
                                 * Do not use getBoundingClientRect() from the injected page here:
                                 * that rect may belong to a nested/cross-origin Yandex frame and is
                                 * not necessarily in the parent WebView coordinate space.
                                 */
                                var verifiedViewportWidth =
                                    window.visualViewport && window.visualViewport.width
                                        ? window.visualViewport.width
                                        : (window.innerWidth || 384);
                                var VERIFIED_CLOSE_FALLBACK_X =
                                    Math.max(20.0, verifiedViewportWidth - 30.0);
                                var VERIFIED_CLOSE_FALLBACK_Y = 64.0;
                                var verifiedFallbackRequested = false;

                                function verifyAfterNativeClose() {
                                    setTimeout(function() {
                                        if (!finished) verifyReward(0);

                                        /*
                                         * If the fallback tap did not close the fullscreen ad
                                         * (e.g. the control appeared a little later), let the
                                         * accessibility path attempt the actual reward control.
                                         */
                                        if (
                                            !finished &&
                                            !window.__mbAdsRewardFallbackStarted
                                        ) {
                                            window.__mbAdsRewardFallbackStarted = true;
                                            AndroidAds.onStateLog(
                                                "AD_REWARD_FALLBACK",
                                                "verified_top_right_tap_did_not_confirm_reward"
                                            );
                                            requestRewardClaim();
                                        }
                                    }, 1200);
                                }

                                function attemptAutoClose() {
                                    if (
                                        finished ||
                                        nativeCloseRequested ||
                                        nativeCloseAttempts >= 2
                                    ) {
                                        return;
                                    }

                                    /*
                                     * IMPORTANT:
                                     * The Yandex fullscreen close element can be discovered in
                                     * the injected DOM, but its getBoundingClientRect() coordinates
                                     * are NOT guaranteed to be coordinates of the parent WebView.
                                     * The previous test proved this: the DOM returned a point near
                                     * the bottom of the WebView and the native tap opened the ad's
                                     * Google Play destination instead of closing the ad.
                                     *
                                     * Therefore NEVER dispatch a native tap using the DOM rect here.
                                     * Use the previously verified fullscreen top-right point.
                                     */
                                    AndroidAds.onStateLog(
                                        "AUTO_CLOSE_SEARCH",
                                        "attempt=" + (nativeCloseAttempts + 1) +
                                            " dom_coordinate_tap=DISABLED source=VERIFIED_FALLBACK"
                                    );

                                    if (!verifiedFallbackRequested) {
                                        verifiedFallbackRequested = true;
                                        nativeCloseAttempts++;

                                        AndroidAds.onStateLog(
                                            "AUTO_CLOSE_SEARCH",
                                            "attempt=" + nativeCloseAttempts +
                                                " found=false visible=false " +
                                                "source=VERIFIED_FALLBACK"
                                        );

                                        AndroidAds.onStateLog(
                                            "NATIVE_CLOSE_FALLBACK",
                                            "x=" + VERIFIED_CLOSE_FALLBACK_X +
                                                " y=" + VERIFIED_CLOSE_FALLBACK_Y +
                                                " viewport=" +
                                                (window.innerWidth || 0) + "x" +
                                                (window.innerHeight || 0) +
                                                " source=fullscreen_top_right_fallback"
                                        );

                                        try {
                                            AndroidAds.onCloseTapRequested(
                                                VERIFIED_CLOSE_FALLBACK_X,
                                                VERIFIED_CLOSE_FALLBACK_Y,
                                                "fullscreen_top_right_fallback"
                                            );

                                            nativeCloseRequested = true;

                                            AndroidAds.onStateLog(
                                                "CLOSE_NATIVE_REQUESTED",
                                                "elapsed=32s source=fullscreen_top_right_fallback"
                                            );

                                            verifyAfterNativeClose();
                                            return;
                                        } catch (e) {
                                            AndroidAds.onStateLog(
                                                "NATIVE_CLOSE_FALLBACK_ERROR",
                                                "error=" +
                                                    (e && e.message ? e.message : String(e))
                                            );
                                        }
                                    }

                                    /*
                                     * Do not hammer the fullscreen overlay. After one verified
                                     * fallback tap, a later retry is allowed only if the native
                                     * request itself could not be issued.
                                     */
                                    if (!nativeCloseRequested) {
                                        nativeCloseRetryTimer = setTimeout(
                                            attemptAutoClose,
                                            1000
                                        );
                                    }
                                }

                                function requestRewardClaim() {
                                    if (
                                        finished ||
                                        rewardClaimFinished ||
                                        rewardClaimPending ||
                                        rewardClaimAttempts >= 20
                                    ) {
                                        return;
                                    }

                                    rewardClaimPending = true;
                                    rewardClaimAttempts++;

                                    AndroidAds.onStateLog(
                                        "REWARD_BUTTON_SEARCH",
                                        "attempt=" + rewardClaimAttempts +
                                            " source=ANDROID_ACCESSIBILITY"
                                    );

                                    try {
                                        AndroidAds.onAutoRewardClaimRequested();
                                    } catch (e) {
                                        rewardClaimPending = false;
                                        AndroidAds.onStateLog(
                                            "ACCESSIBILITY_REWARD_REQUEST_EXCEPTION",
                                            "error=" + (e && e.message ? e.message : String(e))
                                        );
                                    }
                                }

                                function startAdMonitoring() {
                                    /*
                                     * Exact Yandex close markup confirmed for this rewarded format:
                                     *   [data-fullscreen-element="close"]
                                     *     [data-survey-fullscreen-control]
                                     *       <svg width="40" height="40">...</svg>
                                     *
                                     * After the minimum viewing period, press that REAL close
                                     * control through the native WebView. Accessibility remains
                                     * only as a fallback when the cross-origin/iframe DOM is not
                                     * exposed to page JavaScript.
                                     */
                                    var adStartedAt = Date.now();
                                    var ownWatchDurationMs = 32000;
                                    var lastSecondLogged = -1;

                                    AndroidAds.onStateLog(
                                        "OUR_TIMER_STARTED",
                                        "duration=32s"
                                    );

                                    watchTimer = setInterval(function() {
                                        if (finished) {
                                            clearInterval(watchTimer);
                                            finishNativeCloseRetryTimer();
                                            return;
                                        }

                                        var elapsed = Date.now() - adStartedAt;
                                        var remainingMs = Math.max(0, ownWatchDurationMs - elapsed);
                                        var remainingSec = Math.ceil(remainingMs / 1000);

                                        if (remainingSec !== lastSecondLogged) {
                                            lastSecondLogged = remainingSec;
                                            AndroidAds.onStateLog(
                                                "OUR_TIMER",
                                                "remaining=" + remainingSec + "s"
                                            );
                                        }

                                        if (elapsed < ownWatchDurationMs) {
                                            return;
                                        }

                                        /*
                                         * First preference: exact Yandex close control.
                                         * Secondary preference: Android accessibility reward
                                         * control if the close node is not visible to page JS.
                                         */
                                        if (!nativeCloseRequested) {
                                            attemptAutoClose();
                                            return;
                                        }

                                        if (!rewardClaimFinished && !rewardClaimPending) {
                                            /*
                                             * Fallback is triggered by verifyAfterNativeClose()
                                             * only when the verified native close did not produce a
                                             * reward confirmation.
                                             */
                                        }
                                    }, 750);
                                }

                                function tryFindAndClick() {
                                    if (finished) return;

                                    // The site can create this toast asynchronously after a
                                    // balance/ad request. Check it before touching the ad button.
                                    if (checkDailyLimitToast()) return;

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

                                    try {
                                        btn.click();
                                    } catch (e) {
                                        finished = true;
                                        AndroidAds.onAdFailed("watch_click_exception=" + (e.message || e));
                                        return;
                                    }

                                    AndroidAds.onStateLog(
                                        "WATCH_BUTTON_CLICKED",
                                        "button=.wallet-panel__action--ads.user-quest__watch-ads-btn data-count=" +
                                            (btn.getAttribute("data-count") || "")
                                    );
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

            webView.webViewClient = object : AutomationWebViewClient(account.username, webView) {

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
                            safeResume(AdWatchResult.Failed)
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
                    safeResume(AdWatchResult.Failed)
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
                fun onMineBalance(oreBalance: Int) {
                    if (oreBalance >= 0) onMineBalanceUpdate(account.id, oreBalance)
                }

                @JavascriptInterface
                fun onMineProgress(clicks: Int, total: Int, ore: Int) {
                    if (ore >= 0) onMineBalanceUpdate(account.id, ore)
                    updateStatus(account, "⛏️ Шахта ($clicks/$total) • 🪨 $ore", true, "Шахта",
                        if (total > 0) clicks.toFloat() / total else 1f)
                }

                @JavascriptInterface
                fun onMineMined(oreMined: Int) {
                    log(account.username, "MINE: ORE_STAT_CALLBACK mined=$oreMined")
                    if (oreMined > 0) {
                        addDaily(account) { it.copy(mineOre = it.mineOre + oreMined) }
                    }
                }

                @JavascriptInterface
                fun onMineExchange(oreMined: Int, oreExchanged: Int, diamondsReceived: Int, oreRemaining: Int) {
                    addDaily(account) { it.copy(mineExchangeOre = it.mineExchangeOre + oreExchanged, mineDiamonds = it.mineDiamonds + diamondsReceived) }
                    onMineBalanceUpdate(account.id, oreRemaining)
                    log(account.username, "MINE: EXCHANGE_SUCCESS mined=$oreMined exchanged=$oreExchanged diamonds=+$diamondsReceived remainingOre=$oreRemaining")
                    updateStatus(account, "⛏️ Шахта завершена • 🪨 осталось $oreRemaining • 💎 +$diamondsReceived", true, "Шахта", 1f)
                }

                @JavascriptInterface
                fun onMineLog(msg: String) { log(account.username, "MINE: $msg") }

                @JavascriptInterface
                fun onMineComplete() { safeResume(true) }
            }

            // Keep AndroidMine registered across the /mine navigation.
            webView.addJavascriptInterface(MineBridge(), "AndroidMine")

            webView.webViewClient = object : AutomationWebViewClient(account.username, webView) {
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
                                var initialOre = 0;
                                var hits = document.querySelector('.main-mine__game-hits-left');
                                var initialHits = hits ? parseInt((hits.innerText || '').trim(), 10) || 0 : 0;
                                var autoUpgrade = ${settings.mineAutoUpgrade};

                                function numText(el) {
                                    if (!el) return 0;
                                    var raw = (el.innerText || el.textContent || '').trim();
                                    var normalized = raw.replace(/[^\d]/g, '');
                                    var n = normalized ? parseInt(normalized, 10) : 0;
                                    return isFinite(n) ? n : 0;
                                }

                                function currentOre() {
                                    // While the shop is open its counter is the live balance.
                                    // Do not trust a hidden shop counter while the mining page is active.
                                    var modal = document.querySelector('#modal-mine-shop');
                                    var modalStyle = modal ? window.getComputedStyle(modal) : null;
                                    var modalVisible = !!modal &&
                                        modal.getClientRects().length > 0 &&
                                        modalStyle.display !== 'none' &&
                                        modalStyle.visibility !== 'hidden';

                                    if (modalVisible) {
                                        var visibleShopOre = modal.querySelector('.mine-shop__ore-count') ||
                                            document.querySelector('.mine-shop__ore-count');
                                        if (visibleShopOre) return numText(visibleShopOre);
                                    }

                                    var mine = document.querySelector('.main-mine');
                                    if (mine) {
                                        var raw = mine.getAttribute('data-ore');
                                        var value = raw !== null && raw.trim() !== ''
                                            ? parseInt(raw, 10)
                                            : NaN;
                                        if (isFinite(value) && value >= 0) return value;
                                    }

                                    // Some site layouts expose only the shop counter and do not
                                    // attach the ore value to .main-mine[data-ore].
                                    var shopOre = document.querySelector('.mine-shop__ore-count');
                                    if (shopOre) return numText(shopOre);
                                    return 0;
                                }

                                initialOre = currentOre();
                                AndroidMine.onMineBalance(initialOre);
                                AndroidMine.onMineLog(
                                    'DOM_READY hits=' + initialHits +
                                    ' ore=' + initialOre +
                                    ' autoUpgrade=' + autoUpgrade
                                );

                                function reportMinedOre(finalOre, source) {
                                    var minedOre = Math.max(0, finalOre - initialOre);
                                    AndroidMine.onMineLog(
                                        'ORE_STAT_COMPLETED source=' + source +
                                        ' baseline=' + initialOre +
                                        ' final=' + finalOre +
                                        ' mined=' + minedOre
                                    );
                                    AndroidMine.onMineMined(minedOre);
                                    return minedOre;
                                }

                                function waitForShop(done, failed) {
                                    var started = Date.now();
                                    function poll() {
                                        var modal = document.querySelector('#modal-mine-shop');
                                        var exchange = document.querySelector('#modal-mine-shop .mine-shop__ore-change-btn');
                                        var style = modal ? window.getComputedStyle(modal) : null;
                                        var visible = !!modal &&
                                            modal.getClientRects().length > 0 &&
                                            style.display !== 'none' &&
                                            style.visibility !== 'hidden';
                                        if (visible && exchange) { done(); return; }
                                        if (Date.now() - started >= 10000) {
                                            AndroidMine.onMineLog('SHOP_NOT_OPEN visible=' + visible + ' controls=' + !!exchange);
                                            if (failed) failed(); else AndroidMine.onMineComplete();
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

                                    if (!${settings.mineAutoExchange}) {
                                        var finalOreManual = currentOre();
                                        var minedOreManual = reportMinedOre(finalOreManual, 'mine-page');
                                        AndroidMine.onMineLog('AUTO_EXCHANGE_DISABLED_MANUAL_REQUIRED minedOre=' + minedOreManual + ' remainingOre=' + finalOreManual);
                                        AndroidMine.onMineProgress(clicks, initialHits, finalOreManual);
                                        AndroidMine.onMineComplete();
                                        return;
                                    }

                                    var header = document.querySelector('.main-mine__header_score');
                                    if (!header) {
                                        AndroidMine.onMineLog('CRYSTAL_HEADER_NOT_FOUND');
                                        reportMinedOre(currentOre(), 'mine-page-no-shop');
                                        AndroidMine.onMineComplete();
                                        return;
                                    }

                                    // The authoritative .mine-shop__ore-count is only reliable
                                    // after the shop is open. Compute the mined delta after that,
                                    // not before the click that reveals the counter.
                                    header.click();
                                    waitForShop(function() {
                                        var finalOre = currentOre();
                                        var minedOre = reportMinedOre(finalOre, 'shop');
                                        runShop(minedOre);
                                    }, function() {
                                        reportMinedOre(currentOre(), 'mine-page-shop-timeout');
                                        AndroidMine.onMineComplete();
                                    });
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

                                    if (autoUpgrade) {
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

                        view.evaluateJavascript(script) { result ->
                            log(account.username, "MINE: JS_EVAL_RESULT result=$result")
                        }
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

        currentMangaUrl = ensureCanonicalMangaUrl(account.getSafeActiveMangaUrl())
        log(
            account.username,
            "READER: RESUME_STATE activeMangaUrl=" +
                (currentMangaUrl.ifBlank { "NONE_CATALOG_FALLBACK" }) +
                " title='" + account.getSafeActiveMangaTitle() + "'"
        )

        skippedMangaUrls.clear()
        completedMangaIds.clear()
        completedChapterIds.clear()
        readChapterUrlsInRun.clear()
        historyServerAcceptedChapterIds.clear()
        currentHistoryPostChapterIds.clear()

        activeChapterContext = null
        heartbeatChapterContext = Pair("", "")

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

        val target = settings.readerChapters.coerceIn(1, READER_MAX_CHAPTERS)
        currentSessionTarget = target
        currentSessionChaptersRead = 0
        log(account.username, "READER: START targetChapters=$target")

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
                    nextChapterUrlToOpen,
                    siteAutoscrollEnabled = settings.readerSiteAutoscroll
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
                    addDaily(account) { it.copy(readerChapters = it.readerChapters + 1) }

                    log(account.username, "READER: CHAPTER_READ count=" + chaptersReadCount +
                        "/" + target + " id=" + chId +
                        " terminal=" + result.terminal +
                        " serverQuestGate=DEFERRED_CCL_ACCOUNTING")

                    chaptersSinceComment++

                    // MangaBuff's reading quest uses batched/CCL accounting: the
                    // visible "Главы X/75" value may advance only after several
                    // chapters. Refresh the server balance after every 2 completed
                    // chapters for UI/diagnostics, but NEVER make this refresh a reader gate.
                    if (chaptersReadCount % 2 == 0) {
                        log(account.username,
                            "READER: PERIODIC_BALANCE_REFRESH chaptersRead=" + chaptersReadCount +
                                " reason=2_CHAPTERS")
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
                        dailyCommentCount < settings.commentCount &&
                        dailyStats.comments < COMMENT_DAILY_LIMIT
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

                    if (result.terminal) {
                        if (currentMangaUrl.isNotBlank()) {
                            skippedMangaUrls.add(currentMangaUrl)
                        }

                        log(
                            account.username,
                            "READER: MANGA_COMPLETED terminal=true title='" +
                                cleanMangaTitle(lastFinishedMangaTitle) + "'"
                        )

                        currentMangaUrl = ""
                        nextChapterUrlToOpen = ""
                        totalMangaChapters = 0
                        activeChapterContext = null
                        onMangaActiveUrlUpdate(account.id, "", "")

                        if (chaptersReadCount < target) {
                            delay(1000L)
                        }
                        continue
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
        startUrl: String = "",
        siteAutoscrollEnabled: Boolean = true
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
                            safeResume(
                                ReaderResult.MangaSkipped(
                                    mangaUrl = markUrl,
                                    title = cleanMangaTitle(lastFinishedMangaTitle)
                                )
                            )
                        }

                        mainHandler.postDelayed({
                            if (!resumed && continuation.isActive && pendingMangaMarkAsRead) {
                                pendingMangaMarkAsRead = false
                                log(
                                    account.username,
                                    "READER: MARK_READ_WATCHDOG_TIMEOUT -> SKIP_MANGA",
                                    true
                                )
                                safeResume(
                                    ReaderResult.MangaSkipped(
                                        mangaUrl = markUrl,
                                        title = cleanMangaTitle(lastFinishedMangaTitle)
                                    )
                                )
                            }
                        }, 15_000L)
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
                fun isScreenOff(): Boolean = BackgroundExecutionState.isScreenOff()

                @JavascriptInterface
                fun startBackgroundScroll() {
                    mainHandler.post {
                        startBackgroundScroll(account.username, webView)
                    }
                }

                @JavascriptInterface
                fun cancelBackgroundScroll() {
                    mainHandler.post {
                        stopBackgroundScroll(webView)
                    }
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

                            /*
                             * Do NOT call WebView.scrollBy()/pageDown() here.
                             * MangaBuff's reader can use its own document scroller, so
                             * WebView.scrollY may belong to the outer WebView container
                             * and can diverge wildly from the reader's JS scrollY.
                             *
                             * The normal reader path uses a real touchscreen gesture.
                             * Recovery must use the same path so it targets the actual
                             * scrollable reader instead of the outer WebView.
                             */
                            val width = webView.width.coerceAtLeast(2).toFloat()
                            val height = webView.height.coerceAtLeast(2).toFloat()
                            val x = width * 0.5f
                            val startY = height * 0.78f
                            val endY = height * 0.20f
                            val duration = when {
                                deltaPx <= 0 -> 300L
                                deltaPx < 500 -> 260L
                                else -> 300L
                            }

                            log(
                                account.username,
                                "READER: NATIVE_RECOVERY_FINGER_SWIPE delta=$deltaPx " +
                                    "startY=" + startY.toInt() +
                                    " endY=" + endY.toInt() +
                                    " duration=" + duration
                            )

                            dispatchNativeSwipe(
                                webView = webView,
                                x1 = x,
                                y1 = startY,
                                x2 = x,
                                y2 = endY,
                                durationMs = duration
                            )
                        } catch (e: Exception) {
                            log(
                                account.username,
                                "READER: NATIVE_RECOVERY_FINGER_ERROR " +
                                    (e.message ?: "unknown"),
                                true
                            )
                        }
                    }
                }

                @JavascriptInterface
                fun isExpectedMangaId(mangaId: String): Boolean {
                    val candidate = mangaId.trim()
                    if (candidate.isBlank()) return false

                    val expected = activeChapterContext?.mangaId?.trim().orEmpty()
                    val current = currentMangaUrl
                    val matches = expected.isNotBlank() && candidate == expected

                    log(
                        account.username,
                        "READER: LAST_CHAPTER_MANGA_ID_CHECK notifyId=$candidate expected=$expected matches=$matches"
                    )
                    return matches
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

                    val currentChapterNumber = activeChapterContext?.chapterNumber.orEmpty()
                    if (
                        currentChapterNumber.isNotBlank() &&
                        (chapter.isBlank() || chapter == currentChapterNumber)
                    ) {
                        val previous = heartbeatChapterContext
                        heartbeatChapterContext = Pair(
                            chapterIdStr.ifBlank { previous.first },
                            chapter.ifBlank { currentChapterNumber }
                        )
                    }

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
                fun onReaderTotalChaptersFound(total: Int) {
                    if (total <= 0) return

                    // The reader list is authoritative; the catalogue can expose only the first 100 chapters.
                    val previous = totalMangaChapters
                    if (previous != total) {
                        totalMangaChapters = total
                        log(account.username, "READER: TOTAL_CHAPTERS_READER_LIST total=$total previous=$previous")
                        updateReaderStatus(account)
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
                        pendingMangaMarkAsRead = false
                        log(account.username, "READER: READ_ACTION_NOT_CONFIRMED folder-id=3 -> SKIP_MANGA", true)
                        safeResume(
                            ReaderResult.MangaSkipped(
                                mangaUrl = currentMangaUrl,
                                title = cleanMangaTitle(title).ifBlank { cleanMangaTitle(lastFinishedMangaTitle) }
                            )
                        )
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

                    accountIoScope.launch {
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

                        /*
                         * The reader end marker is already the authoritative terminal
                         * signal for the current chapter. Do not navigate back to the
                         * manga info page and wait for the optional "Прочитано" folder
                         * action here: that UI is not part of chapter completion and
                         * its selectors can vary, which previously left the reader
                         * continuation suspended forever after the final chapter.
                         *
                         * The chapter has already passed the local/server read-history
                         * confirmation above, so finish this ReaderResult immediately.
                         * runReaderTask() will then mark the manga as completed for this
                         * run and continue with the normal task pipeline/catalog flow.
                         */
                        pendingMangaMarkAsRead = false

                        val chapterCanonical = ensureCanonicalMangaUrl(chapterUrl)
                        val slug = chapterCanonical.substringAfter("/manga/").substringBefore("/")

                        currentMangaUrl = if (slug.isNotBlank()) {
                            "https://mangabuff.ru/manga/$slug"
                        } else {
                            currentMangaUrl
                        }

                        val completedTitle = cleanMangaTitle(title)
                            .ifBlank { cleanMangaTitle(lastFinishedMangaTitle) }

                        log(
                            account.username,
                            "READER: LAST_CHAPTER_TERMINAL " +
                                "terminal=true nextChapterSearch=SKIPPED " +
                                "markReadGate=SKIPPED title='$completedTitle'"
                        )

                        safeResume(
                            ReaderResult.ChapterRead(
                                gifts = giftsFound,
                                chapterUrl = chapterUrl,
                                chapterId = chapterId,
                                nextChapterUrl = "",
                                terminal = true
                            )
                        )
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

            webView.webViewClient = object : AutomationWebViewClient(account.username, webView) {

                // Some MangaBuff reader pages keep a resource request pending indefinitely.
                // WebView then never calls onPageFinished, leaving the reader at chapterId=PENDING.
                // Keep a per-navigation watchdog so the same DOM setup can run once after timeout.
                private var pageLoadGeneration: Int = 0
                private var handledPageGeneration: Int = -1

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    if (request != null) {
                        logWebViewNetworkRequest(account, request)

                        val urlStr = request.url.toString()
                        if (urlStr.contains("/addHistory")) {
                            log(
                                account.username,
                                "READER: MB_HISTORY_SHOULD_INTERCEPT " +
                                    "url=$urlStr method=${request.method}"
                            )
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    pageLoadGeneration += 1
                    val generation = pageLoadGeneration
                    log(account.username, "PAGE: PAGE_STARTED url=$url generation=$generation")

                    // Do not wait forever for image/analytics requests. If the main document
                    // is still current and WebView has not finished it after 12 seconds, run
                    // the normal page-finished handler against the already-rendered DOM.
                    if (!url.isNullOrBlank()) {
                        mainHandler.postDelayed({
                            val samePage = view?.url?.substringBefore('#') == url.substringBefore('#')
                            if (generation == pageLoadGeneration &&
                                handledPageGeneration != generation &&
                                samePage
                            ) {
                                log(
                                    account.username,
                                    "PAGE: PAGE_FINISHED_WATCHDOG_FALLBACK url=$url generation=$generation",
                                    true
                                )
                                onPageFinished(view, url)
                            }
                        }, 12_000L)
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    val currentUrl = url ?: ""
                    // Ignore stale callbacks and duplicate callbacks after the watchdog fallback.
                    if (view != null && currentUrl.substringBefore('#') != view.url?.substringBefore('#')) {
                        log(account.username, "PAGE: STALE_PAGE_FINISHED_IGNORED callback=$currentUrl current=${view.url}")
                        return
                    }
                    if (handledPageGeneration == pageLoadGeneration) {
                        log(account.username, "PAGE: DUPLICATE_PAGE_FINISHED_IGNORED url=$currentUrl generation=$pageLoadGeneration")
                        return
                    }
                    handledPageGeneration = pageLoadGeneration
                    super.onPageFinished(view, url)
                    log(account.username, "PAGE: PAGE_FINISHED url=$currentUrl generation=$pageLoadGeneration")

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
                        // Do not attribute the previous chapter's ID to a newly loaded page.
                        heartbeatChapterContext = Pair("", chapterNum)

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


                                    // Keep animation, background recovery and end detection on the
                                    // same scroll target. Some reader layouts lock the document and
                                    // put the long chapter content in an overflow container.
                                    window.__mbReaderScrollTarget = null;
                                    window.__mbReaderScrollTargetLabel = '';
                                    window.__mbReaderLastInnerScanAt = 0;

                                    function mbScrollCapacity(el) {
                                        if (!el) return 0;
                                        return Math.max(0, (el.scrollHeight || 0) - (el.clientHeight || 0));
                                    }

                                    function mbDocumentScrollLocked() {
                                        try {
                                            var htmlStyle = getComputedStyle(document.documentElement);
                                            var bodyStyle = document.body ? getComputedStyle(document.body) : null;
                                            var hidden = function(value) {
                                                return value === 'hidden' || value === 'clip';
                                            };
                                            return hidden(htmlStyle.overflowY) ||
                                                (bodyStyle && hidden(bodyStyle.overflowY));
                                        } catch (e) {
                                            return false;
                                        }
                                    }

                                    function mbSetReaderScrollTarget(target, label) {
                                        if (!target) return null;
                                        var previous = window.__mbReaderScrollTarget;
                                        var previousLabel = window.__mbReaderScrollTargetLabel || '';
                                        window.__mbReaderScrollTarget = target;
                                        window.__mbReaderScrollTargetLabel = label;
                                        if (previous !== target || previousLabel !== label) {
                                            try {
                                                AndroidReaderBridge.onLogStep(
                                                    'READER: SCROLL_TARGET_SELECTED kind=' + label +
                                                    ' tag=' + (target.tagName || 'UNKNOWN') +
                                                    ' id=' + (target.id || '') +
                                                    ' class=' + String(target.className || '').slice(0, 100) +
                                                    ' scrollHeight=' + (target.scrollHeight || 0) +
                                                    ' clientHeight=' + (target.clientHeight || 0)
                                                );
                                            } catch (ignoreLog) {}
                                        }
                                        return target;
                                    }

                                    function mbFindInnerReaderScroller() {
                                        var now = Date.now();
                                        if (now - (window.__mbReaderLastInnerScanAt || 0) < 1200) {
                                            var cached = window.__mbReaderScrollTarget;
                                            if (cached && cached !== document.scrollingElement &&
                                                document.documentElement.contains(cached) &&
                                                mbScrollCapacity(cached) > 80) {
                                                return cached;
                                            }
                                            return null;
                                        }
                                        window.__mbReaderLastInnerScanAt = now;

                                        var viewport = Math.max(window.innerHeight || 0, 1);
                                        var minClientHeight = Math.max(240, viewport * 0.40);
                                        var minWidth = Math.max(180, (window.innerWidth || 0) * 0.55);
                                        var nodes;
                                        try {
                                            nodes = document.querySelectorAll('body *');
                                        } catch (e) {
                                            return null;
                                        }

                                        var best = null;
                                        var bestScore = -1;
                                        for (var i = 0; i < nodes.length; i++) {
                                            var el = nodes[i];
                                            if (!el || el === document.body || el === document.documentElement) continue;

                                            var capacity = mbScrollCapacity(el);
                                            if (capacity < Math.max(160, viewport * 0.20)) continue;
                                            if ((el.clientHeight || 0) < minClientHeight) continue;

                                            var style;
                                            try { style = getComputedStyle(el); } catch (e) { continue; }
                                            var overflowY = style.overflowY || '';
                                            var scrollOverflow = overflowY === 'auto' ||
                                                overflowY === 'scroll' ||
                                                overflowY === 'overlay';
                                            var name = String(el.id || '') + ' ' +
                                                (typeof el.className === 'string' ? el.className : '');
                                            var readerLike = /reader|chapter|page|manga|content|scroll/i.test(name);
                                            var hiddenOverflow = overflowY === 'hidden' || overflowY === 'clip';
                                            if (!scrollOverflow && !(hiddenOverflow && readerLike)) continue;

                                            var rect;
                                            try { rect = el.getBoundingClientRect(); } catch (e) { continue; }
                                            if (!rect || rect.width < minWidth || rect.height < minClientHeight) continue;
                                            if (rect.bottom <= 0 || rect.top >= viewport) continue;

                                            var imageCount = 0;
                                            try { imageCount = Math.min(40, el.querySelectorAll('img').length); } catch (e) {}
                                            if (!readerLike && imageCount === 0) continue;

                                            var score = (readerLike ? 120 : 0) +
                                                (scrollOverflow ? 25 : 0) +
                                                Math.min(60, imageCount * 3) +
                                                Math.min(30, Math.round((rect.width / Math.max(window.innerWidth || 1, 1)) * 30)) +
                                                Math.min(25, Math.round((el.clientHeight / viewport) * 25)) +
                                                Math.min(25, Math.round(capacity / 1000));
                                            if (score > bestScore) {
                                                best = el;
                                                bestScore = score;
                                            }
                                        }
                                        return best;
                                    }

                                    window.__mbGetReaderScrollMetrics = function(forceInnerScan) {
                                        var root = document.scrollingElement ||
                                            document.documentElement ||
                                            document.body;
                                        var rootCapacity = mbScrollCapacity(root);
                                        var locked = mbDocumentScrollLocked();
                                        var target = window.__mbReaderScrollTarget;
                                        var targetIsDocument = target === root ||
                                            target === document.documentElement ||
                                            target === document.body;

                                        if (target && targetIsDocument && (locked || forceInnerScan)) {
                                            target = null;
                                        } else if (target && !targetIsDocument) {
                                            var stillAttached = false;
                                            try {
                                                stillAttached = document.documentElement.contains(target);
                                            } catch (e) {}
                                            if (!stillAttached || mbScrollCapacity(target) <= 80) target = null;
                                        }

                                        if (!target && !forceInnerScan && !locked && rootCapacity > 80) {
                                            target = mbSetReaderScrollTarget(root, 'document');
                                        }

                                        if (!target && (locked || forceInnerScan)) {
                                            var inner = mbFindInnerReaderScroller();
                                            if (inner) target = mbSetReaderScrollTarget(inner, 'inner');
                                        }

                                        if (!target && !locked && rootCapacity > 0) {
                                            target = mbSetReaderScrollTarget(root, 'document');
                                        }
                                        if (!target) {
                                            target = mbSetReaderScrollTarget(root || document.documentElement,
                                                locked ? 'document_locked_no_inner_found' : 'document_fallback');
                                        }

                                        targetIsDocument = target === root ||
                                            target === document.documentElement ||
                                            target === document.body;

                                        var y, height, viewport;
                                        var nativeViewport = Number(window.__mbNativeViewportHeight) || 0;
                                        var usedNativeViewportFallback = false;
                                        if (targetIsDocument) {
                                            y = Math.max(
                                                window.scrollY || 0,
                                                window.pageYOffset || 0,
                                                root ? (root.scrollTop || 0) : 0,
                                                document.documentElement ? (document.documentElement.scrollTop || 0) : 0,
                                                document.body ? (document.body.scrollTop || 0) : 0
                                            );
                                            height = Math.max(
                                                root ? (root.scrollHeight || 0) : 0,
                                                document.documentElement ? (document.documentElement.scrollHeight || 0) : 0,
                                                document.body ? (document.body.scrollHeight || 0) : 0
                                            );
                                            viewport = Math.max(
                                                window.visualViewport ? (window.visualViewport.height || 0) : 0,
                                                window.innerHeight || 0,
                                                root ? (root.clientHeight || 0) : 0,
                                                1
                                            );
                                        } else {
                                            y = Math.max(0, target.scrollTop || 0);
                                            height = Math.max(0, target.scrollHeight || 0);
                                            viewport = Math.max(1, target.clientHeight || window.innerHeight || 1);
                                        }

                                        // Detached WebViews can report a one-pixel viewport. Use the
                                        // measured native viewport only for that invalid case; otherwise
                                        // preserve the reader's real inner-scroller dimensions.
                                        if (viewport <= 1 && nativeViewport > 1) {
                                            viewport = nativeViewport;
                                            usedNativeViewportFallback = true;
                                        }

                                        // A one-pixel viewport may already have scrolled to height-1.
                                        // Clamp that stale position to the real viewport's bottom so
                                        // the next background tick can keep reading instead of stalling.
                                        if (usedNativeViewportFallback && height > 0) {
                                            var maxScrollY = Math.max(0, height - viewport);
                                            if (y > maxScrollY + 2) {
                                                try {
                                                    target.scrollTop = maxScrollY;
                                                    if (targetIsDocument) window.scrollTo(0, maxScrollY);
                                                } catch (e) {}
                                                y = maxScrollY;
                                            }
                                        }

                                        return {
                                            target: target,
                                            isDocument: targetIsDocument,
                                            y: y,
                                            height: height,
                                            viewport: viewport,
                                            remaining: Math.max(0, height - viewport - y)
                                        };
                                    };

                                    function mbScrollReaderBy(delta) {
                                        if (!(delta > 0)) return false;
                                        var metrics = window.__mbGetReaderScrollMetrics(false);
                                        var target = metrics.target;
                                        var before = metrics.y;
                                        var nextY = Math.min(
                                            Math.max(0, metrics.height - metrics.viewport),
                                            before + delta
                                        );
                                        if (nextY <= before) return false;

                                        try {
                                            target.scrollTop = nextY;
                                            if (metrics.isDocument && (window.scrollY || 0) <= before + 1) {
                                                window.scrollTo(0, nextY);
                                            }
                                        } catch (e) {}

                                        var after = metrics.isDocument
                                            ? Math.max(window.scrollY || 0, window.pageYOffset || 0, target.scrollTop || 0)
                                            : (target.scrollTop || 0);

                                        if (after <= before + 1 && metrics.remaining > 2) {
                                            var alternate = window.__mbGetReaderScrollMetrics(true);
                                            if (alternate.target && alternate.target !== target &&
                                                alternate.remaining > 2) {
                                                try {
                                                    alternate.target.scrollTop = Math.min(
                                                        Math.max(0, alternate.height - alternate.viewport),
                                                        alternate.y + delta
                                                    );
                                                } catch (e) {}
                                                return (alternate.target.scrollTop || 0) > alternate.y + 1;
                                            }
                                        }
                                        return after > before + 1;
                                    };

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
                                    AndroidReaderBridge.onLogStep('READER: SCROLL_MODE=MANGABUFF_NATIVE_AUTOSCROLL');

                                    if (window.current_chapter) {
                                        var c = window.current_chapter;
                                        var resolvedChapterId = resolveCurrentChapterId();
                                        // Cache the real manga ID before MangaBuff can mutate window.current_chapter.
                                        var initialMangaId = String(c.manga_id || c.mangaId || '').trim();
                                        if (initialMangaId) {
                                            window.__mbExpectedMangaId = initialMangaId;
                                            AndroidReaderBridge.onLogStep('READER: MANGA_ID_CACHED id=' + initialMangaId);
                                        }
                                        AndroidReaderBridge.onCurrentChapterData(
                                            initialMangaId || String(c.id || ''),
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
                                        /*
                                         * IMPORTANT:
                                         * Never manufacture the next chapter URL from N + 1.
                                         * The chapter list/navigation DOM is the source of truth.
                                         *
                                         * The previous implementation could fall back to
                                         * buildCandidateNextUrl(), which turned a real last chapter
                                         * into a request for a non-existent "/N+1" page.
                                         */

                                        function findRealHref(el) {
                                            if (!el) return '';
                                            var href = getHref(el);
                                            return isValidNextUrl(href) ? href : '';
                                        }

                                        // 1) MangaBuff chapter list: use the real adjacent chapter
                                        // link after the active chapter.
                                        var chapterItems = Array.from(
                                            document.querySelectorAll('.reader-chapters__item')
                                        );

                                        if (chapterItems.length > 0) {
                                            var activeIndex = -1;
                                            for (var ci = 0; ci < chapterItems.length; ci++) {
                                                var item = chapterItems[ci];
                                                if (
                                                    item.classList.contains('reader-chapters__item--active') ||
                                                    item.classList.contains('active') ||
                                                    item.querySelector('.reader-chapters__item--active')
                                                ) {
                                                    activeIndex = ci;
                                                    break;
                                                }
                                            }

                                            if (activeIndex >= 0) {
                                                for (var ni = activeIndex + 1; ni < chapterItems.length; ni++) {
                                                    var nextItem = chapterItems[ni];
                                                    var nextLink = nextItem.querySelector(
                                                        'a[href], button[href], [role="link"][href]'
                                                    ) || (
                                                        nextItem.tagName === 'A' ? nextItem : null
                                                    );
                                                    var nextHref = findRealHref(nextLink);
                                                    if (nextHref) {
                                                        AndroidReaderBridge.onLogStep(
                                                            'NEXT_CHAPTER_FOUND_LIST index=' + ni +
                                                            ' text="' + text(nextItem) +
                                                            '" url=' + nextHref
                                                        );
                                                        return nextLink;
                                                    }
                                                }
                                            }
                                        }

                                        // 2) Real MangaBuff next-navigation controls.
                                        var explicit = Array.from(document.querySelectorAll(
                                            '.navigate-button, .reader-header__nav-btn--next, .reader__next, [data-next-chapter]'
                                        ));

                                        for (var j = 0; j < explicit.length; j++) {
                                            var ex = explicit[j];
                                            if (!visible(ex)) continue;
                                            if (ex.classList && ex.classList.contains('notify-new-chapter')) continue;

                                            var exHref = findRealHref(ex);
                                            if (exHref) {
                                                AndroidReaderBridge.onLogStep(
                                                    'NEXT_CHAPTER_FOUND_NAV tag=' + ex.tagName +
                                                    ' text="' + text(ex) + '" url=' + exHref
                                                );
                                                return ex;
                                            }

                                            var nested = ex.querySelector ? ex.querySelector('a[href]') : null;
                                            var nestedHref = findRealHref(nested);
                                            if (nestedHref) {
                                                AndroidReaderBridge.onLogStep(
                                                    'NEXT_CHAPTER_FOUND_NAV_NESTED text="' + text(ex) +
                                                    '" url=' + nestedHref
                                                );
                                                return nested;
                                            }
                                        }

                                        // 3) Text-based discovery is only accepted when it contains
                                        // a real, strictly increasing chapter href.
                                        var direct = Array.from(document.querySelectorAll(
                                            'a[href], button[href], [role="link"][href], [role="button"]'
                                        ));

                                        for (var i = 0; i < direct.length; i++) {
                                            var el = direct[i];
                                            if (!visible(el)) continue;
                                            if (el.classList && el.classList.contains('notify-new-chapter')) continue;

                                            var ownText = text(el);
                                            if (!isNextText(ownText)) continue;

                                            var href = findRealHref(el);
                                            if (href) {
                                                AndroidReaderBridge.onLogStep(
                                                    'NEXT_CHAPTER_FOUND_DOM tag=' + el.tagName +
                                                    ' text="' + ownText + '" url=' + href
                                                );
                                                return el;
                                            }
                                        }

                                        return null;
                                    }

                                    function isLastChapter() {
                                        /*
                                         * MangaBuff marks the real end of a manga with:
                                         *   "Таков конец..."
                                         *   "Назад к тайтлу"
                                         *   <button class="notify-new-chapter" data-id="MANGA_ID">
                                         *
                                         * Do NOT infer the end from an empty next href or from
                                         * chapter number + 1. Once this finish marker is confirmed,
                                         * it is terminal and wins over any stale/phantom next link.
                                         *
                                         * Older/newer MangaBuff layouts may use different wrapper
                                         * classes, so the textual finish marker is intentionally
                                         * detected independently of .reader__wrapper--finish.
                                         */
                                        // The end marker can be present in the DOM while its button is
                                        // temporarily outside the viewport during the final layout pass.
                                        // Do not make EOF depend on CSS visibility; the DOM marker itself is
                                        // authoritative once the page has reached the stabilized bottom.
                                        var notify = document.querySelector('.notify-new-chapter');
                                        if (!notify) {
                                            AndroidReaderBridge.onLogStep(
                                                'LAST_CHAPTER_MARKER_REJECTED reason=NOTIFY_BUTTON_NOT_FOUND'
                                            );
                                            return false;
                                        }

                                        var id = (notify.getAttribute('data-id') || '').trim();
                                        if (!id) {
                                            AndroidReaderBridge.onLogStep(
                                                'LAST_CHAPTER_MARKER_REJECTED reason=NOTIFY_ID_EMPTY'
                                            );
                                            return false;
                                        }

                                        var known = [];
                                        // Prefer the manga ID captured at chapter startup.
                                        // current_chapter.id/current_manga.id can be chapter IDs.
                                        if (window.__mbExpectedMangaId) {
                                            known.push(String(window.__mbExpectedMangaId));
                                        }
                                        var fav = document.querySelector(
                                            '.manga__favourite-btn[data-id], .favourite-send-btn[data-id]'
                                        );
                                        if (fav) known.push(String(fav.getAttribute('data-id')));

                                        if (window.manga_id) known.push(String(window.manga_id));
                                        if (window.current_manga && window.current_manga.id) {
                                            known.push(String(window.current_manga.id));
                                        }
                                        if (window.current_chapter && window.current_chapter.manga_id) {
                                            known.push(String(window.current_chapter.manga_id));
                                        }

                                        /*
                                         * The JS global current_chapter is not stable on MangaBuff:
                                         * its "id" can be the chapter id, while the native reader
                                         * context already has the authoritative mangaId from
                                         * onCurrentChapterData(). Use that native value as an
                                         * additional source of truth for the notify button.
                                         */
                                        var nativeMangaIdMatches = false;
                                        try {
                                            nativeMangaIdMatches = AndroidReaderBridge.isExpectedMangaId(id);
                                        } catch (e) {
                                            nativeMangaIdMatches = false;
                                        }

                                        var mangaIdMatches = nativeMangaIdMatches || known.indexOf(id) !== -1;
                                        if (!mangaIdMatches) {
                                            AndroidReaderBridge.onLogStep(
                                                'LAST_CHAPTER_MARKER_REJECTED reason=MANGA_ID_MISMATCH notifyId=' +
                                                id + ' known=' + known.join(',')
                                            );
                                            return false;
                                        }

                                        AndroidReaderBridge.onLogStep(
                                            'LAST_CHAPTER_MANGA_ID_CONFIRMED notifyId=' + id +
                                            ' source=' + (nativeMangaIdMatches ? 'NATIVE_CONTEXT' : 'JS_CONTEXT')
                                        );

                                        /*
                                         * Do not require one exact finish CSS class. Find the actual
                                         * visible end marker by its user-facing text. This matches the
                                         * real last-page markup even when the wrapper class changes.
                                         */
                                        var finishTextFound = false;
                                        var finishTitleFound = false;

                                        var finishCandidates = Array.from(
                                            document.querySelectorAll(
                                                '.reader__wrapper--finish, [class*="finish"], .reader__finish, .reader-finish'
                                            )
                                        );

                                        for (var fi = 0; fi < finishCandidates.length; fi++) {
                                            var candidate = finishCandidates[fi];
                                            if (!visible(candidate)) continue;

                                            var candidateText = text(candidate).toLowerCase();
                                            if (candidateText.indexOf('таков конец') !== -1) {
                                                finishTextFound = true;
                                            }
                                            if (candidateText.indexOf('назад к тайтлу') !== -1) {
                                                finishTitleFound = true;
                                            }
                                        }

                                        /*
                                         * Fallback for markup where the finish block has no stable
                                         * class at all: inspect visible DIVs containing the exact end
                                         * phrase. Limit this to the phrase itself, not the whole body,
                                         * so a random page text cannot become an EOF signal.
                                         */
                                        if (!finishTextFound) {
                                            // Fallback for the real MangaBuff markup:
                                            // <div>Таков конец...</div>
                                            // The exact wrapper/class is not stable, so inspect visible
                                            // elements by their normalized user-facing text.
                                            var endTextNodes = Array.from(document.querySelectorAll('body *'));
                                            for (var ei = 0; ei < endTextNodes.length; ei++) {
                                                var endEl = endTextNodes[ei];
                                                if (!visible(endEl)) continue;

                                                var endText = text(endEl).toLowerCase();
                                                if (
                                                    endText === 'таков конец...' ||
                                                    endText === 'таков конец…' ||
                                                    endText === 'таков конец' ||
                                                    endText.indexOf('таков конец...') !== -1 ||
                                                    endText.indexOf('таков конец…') !== -1
                                                ) {
                                                    finishTextFound = true;
                                                    break;
                                                }
                                            }
                                        }

                                        if (!finishTextFound) {
                                            AndroidReaderBridge.onLogStep(
                                                'LAST_CHAPTER_MARKER_REJECTED reason=FINISH_TEXT_NOT_FOUND'
                                            );
                                            return false;
                                        }

                                        AndroidReaderBridge.onLogStep(
                                            'LAST_CHAPTER_MARKER_CONFIRMED notify=true finishText=true' +
                                            ' backToTitle=' + finishTitleFound +
                                            ' finishClass=' + (finishCandidates.length > 0) +
                                            ' finish=true mangaId=' + id
                                        );
                                        return true;
                                    }

                                    function stopScroll(preserveMangaBuffAutoscroll) {
                                        try {
                                            AndroidReaderBridge.cancelBackgroundScroll();
                                        } catch(e) {}

                                        // Prevent an old reader run from firing after stop/reload.
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

                                        // Stop MangaBuff's own autoscroll unless this chapter startup
                                        // explicitly asks to keep it running alongside background scrolling.
                                        if (!preserveMangaBuffAutoscroll) {
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
                                        var directPlayAttempted = false;
                                        var finished = false;

                                        function visibleIcon(selector) {
                                            var icon = document.querySelector(selector);
                                            if (!icon) return null;
                                            if (visible(icon)) return icon;

                                            // Some site versions attach visibility to the button,
                                            // not to the inner icon element.
                                            var control = icon.closest('button, a, [role="button"], .reader-autoscroll');
                                            return control && visible(control) ? icon : null;
                                        }

                                        function isAutoscrollRunning() {
                                            return !!visibleIcon('.reader-autoscroll-icon-pause');
                                        }

                                        function finishSetup(ok, reason) {
                                            if (finished) return;
                                            finished = true;

                                            if (ok) {
                                                AndroidReaderBridge.onLogStep(
                                                    'READER: MANGABUFF_AUTOSCROLL_READY source=' + reason
                                                );
                                            } else {
                                                AndroidReaderBridge.onLogStep(
                                                    'READER: MANGABUFF_AUTOSCROLL_CONFIG_FAILED reason=' + reason
                                                );
                                            }

                                            done(ok);
                                        }

                                        function verifyPlayClick(beforeY, source, fallback) {
                                            setTimeout(function() {
                                                if (chapterDone || finished) return;

                                                if (isAutoscrollRunning()) {
                                                    AndroidReaderBridge.onLogStep(
                                                        'READER: MANGABUFF_AUTOSCROLL_STARTED source=' + source
                                                    );
                                                    finishSetup(true, source);
                                                    return;
                                                }

                                                setTimeout(function() {
                                                    if (chapterDone || finished) return;

                                                    if (isAutoscrollRunning()) {
                                                        AndroidReaderBridge.onLogStep(
                                                            'READER: MANGABUFF_AUTOSCROLL_STARTED source=' + source
                                                        );
                                                        finishSetup(true, source);
                                                        return;
                                                    }

                                                    var afterY = window.scrollY || window.pageYOffset || 0;
                                                    if (afterY > beforeY + 2) {
                                                        AndroidReaderBridge.onLogStep(
                                                            'READER: MANGABUFF_AUTOSCROLL_STARTED_BY_SCROLL source=' + source +
                                                            ' deltaY=' + Math.floor(afterY - beforeY)
                                                        );
                                                        finishSetup(true, source + '_SCROLL_MOVED');
                                                        return;
                                                    }

                                                    if (fallback) {
                                                        AndroidReaderBridge.onLogStep(
                                                            'READER: MANGABUFF_AUTOSCROLL_PLAY_NOT_CONFIRMED fallback=settings'
                                                        );
                                                        fallback();
                                                    } else {
                                                        finishSetup(false, 'AUTOSCROLL_DID_NOT_START');
                                                    }
                                                }, 400);
                                            }, 180);
                                        }

                                        function configureSettings() {
                                            if (chapterDone || finished) return;

                                            if (isAutoscrollRunning()) {
                                                finishSetup(true, 'ALREADY_RUNNING');
                                                return;
                                            }

                                            var popup = findSettingsPopup();
                                            if (!popup) {
                                                if (!settingsOpened) {
                                                    var settingsButton = findSettingsButton();
                                                    if (!settingsButton) {
                                                        if (Date.now() - startedAt >= maxWait) {
                                                            finishSetup(false, 'SETTINGS_BUTTON_NOT_FOUND');
                                                            return;
                                                        }
                                                        setTimeout(configureSettings, 250);
                                                        return;
                                                    }

                                                    settingsOpened = true;
                                                    AndroidReaderBridge.onLogStep('READER: MANGABUFF_SETTINGS_OPEN_CLICK');
                                                    try {
                                                        settingsButton.click();
                                                    } catch(e) {
                                                        AndroidReaderBridge.onLogStep(
                                                            'READER: MANGABUFF_SETTINGS_OPEN_ERROR ' +
                                                            (e.message || String(e))
                                                        );
                                                    }

                                                    setTimeout(configureSettings, 300);
                                                    return;
                                                }

                                                if (Date.now() - startedAt >= maxWait) {
                                                    finishSetup(false, 'SETTINGS_POPUP_NOT_FOUND');
                                                    return;
                                                }

                                                setTimeout(configureSettings, 250);
                                                return;
                                            }

                                            var speed = popup.querySelector('[data-reader-setting="speed"]');
                                            if (!speed) {
                                                finishSetup(false, 'SPEED_RANGE_NOT_FOUND');
                                                return;
                                            }

                                            try {
                                                speed.value = '450';
                                                speed.dispatchEvent(new Event('input', { bubbles: true }));
                                                speed.dispatchEvent(new Event('change', { bubbles: true }));
                                            } catch(e) {
                                                finishSetup(false, 'SPEED_SET_EXCEPTION_' + (e.message || String(e)));
                                                return;
                                            }

                                            var valueLabel = popup.querySelector('[data-reader-value="speed"]');
                                            if (valueLabel) valueLabel.textContent = '450';

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
                                                if (chapterDone || finished) return;

                                                var close = popup.querySelector('.popup__close') ||
                                                    document.querySelector('.popup__close');

                                                if (close && visible(close)) {
                                                    AndroidReaderBridge.onLogStep('READER: MANGABUFF_SETTINGS_CLOSE_CLICK');
                                                    try { close.click(); } catch(e) {}
                                                } else {
                                                    AndroidReaderBridge.onLogStep('READER: MANGABUFF_SETTINGS_CLOSE_NOT_FOUND');
                                                }

                                                setTimeout(function() {
                                                    if (chapterDone || finished) return;

                                                    // The site may already be scrolling after the settings change.
                                                    // Never click Play when Pause is present: that would stop it.
                                                    if (isAutoscrollRunning()) {
                                                        finishSetup(true, 'SETTINGS_LEFT_AUTOSCROLL_RUNNING');
                                                        return;
                                                    }

                                                    var play = visibleIcon('.reader-autoscroll-icon-play');
                                                    if (!play) {
                                                        finishSetup(false, 'AUTOSCROLL_PLAY_NOT_FOUND');
                                                        return;
                                                    }

                                                    var beforeY = window.scrollY || window.pageYOffset || 0;
                                                    AndroidReaderBridge.onLogStep(
                                                        'READER: MANGABUFF_AUTOSCROLL_PLAY_CLICK source=SETTINGS_FALLBACK'
                                                    );

                                                    try {
                                                        play.click();
                                                    } catch(e) {
                                                        finishSetup(false, 'AUTOSCROLL_PLAY_CLICK_EXCEPTION_' + (e.message || String(e)));
                                                        return;
                                                    }

                                                    verifyPlayClick(beforeY, 'SETTINGS_FALLBACK', null);
                                                }, 350);
                                            }, 350);
                                        }

                                        function configure() {
                                            if (chapterDone || finished) return;

                                            // A new chapter can reuse a WebView where MangaBuff's own
                                            // autoscroll is still active. Detect Pause first and do not
                                            // toggle it off by clicking Play again.
                                            if (isAutoscrollRunning()) {
                                                AndroidReaderBridge.onLogStep(
                                                    'READER: MANGABUFF_AUTOSCROLL_ALREADY_RUNNING_AT_CHAPTER_START'
                                                );
                                                finishSetup(true, 'ALREADY_RUNNING_AT_CHAPTER_START');
                                                return;
                                            }

                                            // Try the exact site Play control first on every chapter.
                                            // Only open settings when this fast path cannot be verified.
                                            if (!directPlayAttempted) {
                                                directPlayAttempted = true;
                                                var play = visibleIcon('.reader-autoscroll-icon-play');

                                                if (play) {
                                                    var beforeY = window.scrollY || window.pageYOffset || 0;
                                                    AndroidReaderBridge.onLogStep(
                                                        'READER: MANGABUFF_AUTOSCROLL_PLAY_CLICK source=DIRECT'
                                                    );
                                                    try {
                                                        play.click();
                                                    } catch(e) {
                                                        AndroidReaderBridge.onLogStep(
                                                            'READER: MANGABUFF_AUTOSCROLL_DIRECT_CLICK_ERROR ' +
                                                            (e.message || String(e))
                                                        );
                                                        configureSettings();
                                                        return;
                                                    }

                                                    verifyPlayClick(beforeY, 'DIRECT', configureSettings);
                                                    return;
                                                }

                                                AndroidReaderBridge.onLogStep(
                                                    'READER: MANGABUFF_AUTOSCROLL_PLAY_NOT_VISIBLE source=DIRECT'
                                                );
                                            }

                                            configureSettings();
                                        }

                                        AndroidReaderBridge.onLogStep(
                                            'READER: MANGABUFF_AUTOSCROLL_DOM_STATE' +
                                            ' play=' + !!visibleIcon('.reader-autoscroll-icon-play') +
                                            ' pause=' + !!visibleIcon('.reader-autoscroll-icon-pause') +
                                            ' settings=' + !!findSettingsButton()
                                        );
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
                                                AndroidReaderBridge.onLogStep(
                                                    'READER: NEXT_CHAPTER_DOM_TARGET_UNUSABLE reason=REAL_HREF_REQUIRED'
                                                );
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

                                        var scrollMetrics = window.__mbGetReaderScrollMetrics(false);
                                        var scrollTop = scrollMetrics.y;
                                        var docHeight = scrollMetrics.height;
                                        var viewHeight = scrollMetrics.viewport;
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

                                    // The normal JS timer remains the primary scheduler. When Android
                                    // reports a hidden/screen-off WebView, a throttled native tick may
                                    // run the same due continuation. The stored callback + generation
                                    // ensure a cleared/stale timeout cannot run it twice.
                                    var pendingReaderContinuation = null;
                                    var pendingReaderContinuationTimer = null;
                                    var pendingReaderContinuationDueAt = 0;
                                    var pendingReaderContinuationScheduledAt = 0;
                                    var pendingReaderContinuationGeneration = 0;
                                    var lastNativeReaderContinuationAt = 0;
                                    var lastNativeEndCheckAt = 0;
                                    var readerContinuationRunning = false;

                                    function cancelReaderContinuation() {
                                        pendingReaderContinuationGeneration++;
                                        if (pendingReaderContinuationTimer !== null) {
                                            clearTimeout(pendingReaderContinuationTimer);
                                            pendingReaderContinuationTimer = null;
                                        }
                                        pendingReaderContinuation = null;
                                        pendingReaderContinuationDueAt = 0;
                                        pendingReaderContinuationScheduledAt = 0;
                                    }

                                    function runPendingReaderContinuation(source) {
                                        if (chapterDone || !pendingReaderContinuation || readerContinuationRunning) {
                                            return false;
                                        }

                                        var now = Date.now();
                                        if (source === 'NATIVE') {
                                            if (now - lastNativeReaderContinuationAt < 350) return false;
                                            if (now < pendingReaderContinuationDueAt) return false;
                                            lastNativeReaderContinuationAt = now;
                                        }

                                        if (pendingReaderContinuationTimer !== null) {
                                            clearTimeout(pendingReaderContinuationTimer);
                                            pendingReaderContinuationTimer = null;
                                        }

                                        var continuation = pendingReaderContinuation;
                                        var waitedMs = Math.max(0, now - pendingReaderContinuationScheduledAt);
                                        pendingReaderContinuation = null;
                                        pendingReaderContinuationDueAt = 0;
                                        pendingReaderContinuationScheduledAt = 0;
                                        pendingReaderContinuationGeneration++;
                                        readerContinuationRunning = true;
                                        AndroidReaderBridge.onLogStep(
                                            'READER: CONTINUATION_RUN source=' + source + ' waitedMs=' + waitedMs
                                        );
                                        try {
                                            continuation();
                                        } catch (e) {
                                            cancelReaderContinuation();
                                            chapterDone = true;
                                            AndroidReaderBridge.onLogStep(
                                                'READER: CONTINUATION_ERROR_FAIL_CLOSED source=' + source +
                                                ' error=' + (e && e.message ? e.message : e),
                                                true
                                            );
                                            AndroidReaderBridge.onNextChapterUnknown();
                                        } finally {
                                            readerContinuationRunning = false;
                                        }
                                        return true;
                                    }

                                    function scheduleReaderContinuation(callback, delayMs) {
                                        if (chapterDone) return;
                                        cancelReaderContinuation();
                                        var generation = ++pendingReaderContinuationGeneration;
                                        pendingReaderContinuation = callback;
                                        pendingReaderContinuationScheduledAt = Date.now();
                                        pendingReaderContinuationDueAt = pendingReaderContinuationScheduledAt + delayMs;
                                        pendingReaderContinuationTimer = setTimeout(function() {
                                            if (generation !== pendingReaderContinuationGeneration) return;
                                            pendingReaderContinuationTimer = null;
                                            runPendingReaderContinuation('JS_TIMER');
                                        }, delayMs);
                                    }

                                    window.__mbNativeConfirmationTick = function() {
                                        try {
                                            var now = Date.now();
                                            // Keep checking end-of-document even if Chromium stopped the
                                            // requestAnimationFrame scroll loop while the screen is locked.
                                            // checkEnd still requires a stable real bottom plus MangaBuff
                                            // read/history confirmation before it can advance chapters.
                                            if (!chapterDone && now - lastNativeEndCheckAt >= 350) {
                                                lastNativeEndCheckAt = now;
                                                checkEnd();
                                            }
                                            return runPendingReaderContinuation('NATIVE');
                                        } catch (e) {
                                            AndroidReaderBridge.onLogStep(
                                                'READER: NATIVE_CONTINUATION_ERROR error=' +
                                                (e && e.message ? e.message : e)
                                            );
                                            return false;
                                        }
                                    };

                                    function waitForMangaBuffConfirmation(onSuccess) {
                                        confirmAttempt++;

                                        var state = checkMangaBuffReadState();
                                        logReadState(state);

                                        if (state.confirmed) {
                                            cancelReaderContinuation();
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
                                            cancelReaderContinuation();
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
                                        scheduleReaderContinuation(function() {
                                            waitForMangaBuffConfirmation(onSuccess);
                                        }, 350);
                                    }

                                    function checkEnd() {
                                        if (chapterDone) return;
                                        if (window.__mbNativeFingerBusy) return;

                                        var metrics = window.__mbGetReaderScrollMetrics(false);

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
                                        var finalMetrics = window.__mbGetReaderScrollMetrics(false);
                                        var finalRemaining = Math.max(
                                            0,
                                            finalMetrics.height - finalMetrics.viewport - finalMetrics.y
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

                                                /*
                                                 * A confirmed MangaBuff end marker is terminal.
                                                 * Check it BEFORE looking for any next-chapter DOM
                                                 * link so a stale/phantom "/N+1" link can never win
                                                 * over the real "Таков конец..." + "Уведомить о выходе"
                                                 * state.
                                                 */
                                                var isLastAfterSettle = isLastChapter();

                                                if (isLastAfterSettle) {
                                                    AndroidReaderBridge.onLogStep(
                                                        'FINAL_UI_DETECTED type=NOTIFY_NEW_CHAPTER confirmation=' +
                                                        (confirmationSource || 'UNKNOWN')
                                                    );
                                                    AndroidReaderBridge.onLogStep(
                                                        'LAST_CHAPTER_CONFIRMED terminal=true nextChapterSearch=SKIPPED'
                                                    );
                                                    chapterDone = true;
                                                    finish(null, true, confirmationSource);
                                                    return;
                                                }

                                                var nextAfterSettle = findNextChapter();

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

                                                // No real next href and no confirmed finish marker:
                                                // this is UNKNOWN, never an invitation to open /N+1.

                                                if (attempt < 15) {
                                                    AndroidReaderBridge.onLogStep(
                                                        'NEXT_CHAPTER_DISCOVERY_RETRY attempt=' + (attempt + 1) +
                                                        '/15 reason=DOM_NOT_READY_AFTER_MANGABUFF_CONFIRMATION'
                                                    );
                                                    scheduleReaderContinuation(function() {
                                                        resolveNextChapterAfterBottom(attempt + 1);
                                                    }, 1000);
                                                    return;
                                                }

                                                AndroidReaderBridge.onLogStep(
                                                    'NEXT_CHAPTER_DISCOVERY_GIVE_UP reason=NO_REAL_NEXT_AND_NO_CONFIRMED_FINISH_MARKER',
                                                    true
                                                );
                                                chapterDone = true;
                                                AndroidReaderBridge.onNextChapterUnknown();
                                            });
                                        }

                                        scheduleReaderContinuation(function() {
                                            resolveNextChapterAfterBottom(1);
                                        }, 1200);
                                    }

                                    function humanScroll() {
                                        if (chapterDone) return;

                                        // Keep the existing smooth visible-screen rAF path, but add a
                                        // native Android tick path for screen-off periods. Chromium can
                                        // throttle rAF heavily when the display is locked.
                                        // Keep the site's own autoscroll running alongside the
                                        // native/custom background scroll; normal stop paths still pause it.
                                        try { stopScroll(true); } catch(e) {}

                                        window.__mbBackgroundStep = function() {
                                            try {
                                                if (
                                                    chapterDone ||
                                                    !window.__mbFastScrollRunning
                                                ) {
                                                    return;
                                                }

                                                var scrollMetrics = window.__mbGetReaderScrollMetrics(false);
                                                // Prefer actual DOM measurements, but fall back to the
                                                // Android-measured viewport when a locked/detached WebView
                                                // reports clientHeight/innerHeight as zero or one pixel.
                                                var nativeViewport = Number(window.__mbNativeViewportHeight) || 0;
                                                var viewport = Math.max(
                                                    Number(scrollMetrics.viewport) || 0,
                                                    nativeViewport,
                                                    1
                                                );
                                                var height = Number(scrollMetrics.height) || 0;
                                                var y = Number(scrollMetrics.y) || 0;
                                                var remaining = Math.max(0, height - viewport - y);
                                                if (remaining <= 2) {
                                                    // The native Android pump keeps calling this function while the screen
                                                    // is locked, so it must also drive end detection when JS timers are throttled.
                                                    try { checkEnd(); } catch(ignoreEnd) {}
                                                    return;
                                                }

                                                var step = Math.min(
                                                    420,
                                                    Math.max(140, Math.floor(viewport * 0.20))
                                                );
                                                step = Math.min(step, remaining);
                                                mbScrollReaderBy(step);
                                                try { checkEnd(); } catch(ignoreEnd) {}
                                            } catch(e) {
                                                try {
                                                    AndroidReaderBridge.onLogStep(
                                                        'READER: BACKGROUND_SCROLL_STEP_ERROR ' +
                                                        (e.message || String(e))
                                                    );
                                                } catch(ignore) {}
                                            }
                                        };

                                        try {
                                            AndroidReaderBridge.startBackgroundScroll();
                                        } catch(e) {
                                            AndroidReaderBridge.onLogStep(
                                                'READER: BACKGROUND_SCROLL_BRIDGE_ERROR ' +
                                                (e.message || String(e))
                                            );
                                        }

                                        var speedPxPerSecond = 2200 + Math.floor(Math.random() * 801); // 2200..3000 px/s
                                        var lastFrame = performance.now();
                                        window.__mbFastScrollRunning = true;

                                        AndroidReaderBridge.onLogStep(
                                            'READER: FAST_SMOOTH_SCROLL_STARTED speed=' + speedPxPerSecond
                                        );

                                        // scrollHeight/clientHeight reads can force synchronous layout.
                                        // Sample dimensions every 250 ms instead of forcing layout every animation frame.
                                        var cachedViewport = 0;
                                        var cachedHeight = 0;
                                        var lastLayoutSampleAt = 0;
                                        var lastFrameGapLogAt = -5000;

                                        function tick(now) {
                                            if (chapterDone || !window.__mbFastScrollRunning) {
                                                return;
                                            }

                                            var frameDeltaMs = Math.max(0, now - lastFrame);
                                            var dt = Math.min(0.05, frameDeltaMs / 1000);
                                            lastFrame = now;

                                            if (frameDeltaMs >= 350 && now - lastFrameGapLogAt >= 5000) {
                                                lastFrameGapLogAt = now;
                                                AndroidReaderBridge.onLogStep(
                                                    'READER: SCROLL_FRAME_GAP deltaMs=' + Math.floor(frameDeltaMs) +
                                                    ' y=' + Math.floor(window.scrollY || window.pageYOffset || 0)
                                                );
                                            }

                                            var scrollMetrics = window.__mbGetReaderScrollMetrics(false);
                                            var y = scrollMetrics.y;

                                            if (
                                                cachedViewport <= 0 ||
                                                cachedHeight <= 0 ||
                                                now - lastLayoutSampleAt >= 250
                                            ) {
                                                cachedViewport = scrollMetrics.viewport;
                                                cachedHeight = scrollMetrics.height;
                                                lastLayoutSampleAt = now;
                                            }

                                            var viewport = cachedViewport;
                                            var height = cachedHeight;
                                            var remaining = Math.max(0, height - viewport - y);

                                            // Slow down only in the final viewport so the existing
                                            // bottom-stabilization logic can reliably settle.
                                            var currentSpeed = remaining < viewport * 1.5
                                                ? Math.max(450, speedPxPerSecond * (remaining / (viewport * 1.5)))
                                                : speedPxPerSecond;

                                            if (remaining > 2) {
                                                mbScrollReaderBy(currentSpeed * dt);
                                            }

                                            window.__mbFastScrollFrame = requestAnimationFrame(tick);
                                        }

                                        window.__mbFastScrollFrame = requestAnimationFrame(tick);
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

                                    function reportReaderChapterTotal(source) {
                                        try {
                                            var chapterList = document.querySelector('.reader-chapters');
                                            if (!chapterList) return 0;

                                            var links = chapterList.querySelectorAll('a.reader-chapters__item[href]');
                                            var uniqueChapterPaths = new Set();

                                            Array.prototype.forEach.call(links, function(link) {
                                                try {
                                                    var url = new URL(link.getAttribute('href') || link.href, window.location.href);
                                                    if (url.origin !== window.location.origin) return;

                                                    var chapterPath = (url.pathname || '').replace(/\/+$/, '');
                                                    if (!/^\/manga\/[^/]+\/\d+\/\d+$/.test(chapterPath)) return;

                                                    uniqueChapterPaths.add(chapterPath);
                                                } catch(ignoreLink) {}
                                            });

                                            var total = uniqueChapterPaths.size;
                                            if (total > 0) {
                                                AndroidReaderBridge.onReaderTotalChaptersFound(total);
                                                AndroidReaderBridge.onLogStep(
                                                    'READER: CHAPTER_TOTAL_FROM_READER_LIST total=' + total +
                                                    ' source=' + source
                                                );
                                            }
                                            return total;
                                        } catch(e) {
                                            AndroidReaderBridge.onLogStep(
                                                'READER: CHAPTER_TOTAL_SCAN_ERROR ' + (e.message || String(e))
                                            );
                                            return 0;
                                        }
                                    }

                                    var readerScrollStarted = false;
                                    function startReaderScrollOnce() {
                                        if (readerScrollStarted) return;
                                        readerScrollStarted = true;

                                        // The site-level autoscroll is user-toggleable from the Reading task row.
                                        // The app's own background scroll remains active in either mode.
                                        if (${siteAutoscrollEnabled}) {
                                            AndroidReaderBridge.onLogStep('READER: MANGABUFF_AUTOSCROLL_SETUP_BEGIN');
                                            configureMangaBuffAutoscroll(function(ok) {
                                                if (chapterDone) return;
                                                AndroidReaderBridge.onLogStep(
                                                    ok
                                                        ? 'READER: MANGABUFF_AUTOSCROLL_ACTIVE_WITH_BACKGROUND_SCROLL'
                                                        : 'READER: MANGABUFF_AUTOSCROLL_UNAVAILABLE_CUSTOM_SCROLL_ONLY'
                                                );
                                                humanScroll();
                                            });
                                        } else {
                                            AndroidReaderBridge.onLogStep('READER: MANGABUFF_AUTOSCROLL_DISABLED_BY_SETTING');
                                            humanScroll();
                                        }
                                    }

                                    (function scanReaderChapterTotal() {
                                        try {
                                            if (reportReaderChapterTotal('INITIAL_DOM') > 0) {
                                                startReaderScrollOnce();
                                                return;
                                            }

                                            var buttonSelector =
                                                'button.reader-menu__item.reader-menu__item--chapters[data-popup="popup--chapters-select"]';
                                            var menuButton = document.querySelector(buttonSelector);
                                            if (!menuButton) {
                                                AndroidReaderBridge.onLogStep('READER: CHAPTER_TOTAL_MENU_BUTTON_NOT_FOUND');
                                                startReaderScrollOnce();
                                                return;
                                            }

                                            var openedByScanner = false;
                                            try {
                                                menuButton.click();
                                                openedByScanner = true;
                                            } catch(ignoreOpen) {}

                                            function closeMenuIfOpened() {
                                                if (!openedByScanner) return;
                                                var buttonToClose = document.querySelector(buttonSelector);
                                                if (buttonToClose) {
                                                    try { buttonToClose.click(); } catch(ignoreClose) {}
                                                }
                                            }

                                            var attempts = 0;
                                            function pollReaderChapterList() {
                                                attempts++;
                                                if (reportReaderChapterTotal('MENU_ATTEMPT_' + attempts) > 0) {
                                                    closeMenuIfOpened();
                                                    startReaderScrollOnce();
                                                    return;
                                                }

                                                if (attempts < 6) {
                                                    setTimeout(pollReaderChapterList, 120);
                                                } else {
                                                    closeMenuIfOpened();
                                                    AndroidReaderBridge.onLogStep(
                                                        'READER: CHAPTER_TOTAL_NOT_FOUND attempts=' + attempts
                                                    );
                                                    startReaderScrollOnce();
                                                }
                                            }

                                            setTimeout(pollReaderChapterList, 100);
                                        } catch(e) {
                                            AndroidReaderBridge.onLogStep(
                                                'READER: CHAPTER_TOTAL_SCAN_ERROR ' + (e.message || String(e))
                                            );
                                            startReaderScrollOnce();
                                        }
                                    })();

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
    ): Boolean {
        val cleanTarget = targetUrl
            .substringBefore('?')
            .substringBefore('#')
            .trim()
            .ifBlank {
                webView.url.orEmpty()
                    .substringBefore('?')
                    .substringBefore('#')
                    .trim()
            }

        if (!cleanTarget.startsWith("https://mangabuff.ru/manga/")) {
            log(account.username, "COMMENT: NON_CHAPTER_URL", true)
            return false
        }

        val chapterId = Regex("/manga/[^/]+/[^/]+/(\\d+)$")
            .find(cleanTarget)
            ?.groupValues
            ?.getOrNull(1)
            ?: activeChapterContext
                ?.takeIf { it.accountId == account.id }
                ?.chapterId
                ?.takeIf { it.isNotBlank() }
            ?: cleanTarget.substringAfterLast('/')
                .takeIf { it.isNotBlank() && it.all(Char::isDigit) }

        if (chapterId.isNullOrBlank()) {
            log(
                account.username,
                "COMMENT: CHAPTER_ID_NOT_FOUND url=$cleanTarget",
                true
            )
            return false
        }

        val commentText = commentPhrases.random()
        val commentJs = com.google.gson.Gson().toJson(commentText)

        suspend fun evalJs(script: String): String? =
            suspendCancellableCoroutine { continuation ->
                mainHandler.post {
                    try {
                        webView.evaluateJavascript(script) { result ->
                            if (continuation.isActive) {
                                continuation.resume(result)
                            }
                        }
                    } catch (e: Exception) {
                        log(
                            account.username,
                            "COMMENT: JS_ERROR error=" + e.message,
                            true
                        )
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            }

        suspend fun waitForControls(timeoutMs: Long): Boolean {
            var menuClicked = false

            return withTimeoutOrNull(timeoutMs) {
                var controlsReady = false

                while (!controlsReady) {
                    coroutineContext.ensureActive()

                    val rawState = evalJs(
                        """
                        (function() {
                            try {
                                var menuButton = document.querySelector(
                                    '.reader-menu__item--comment'
                                );
                                var textarea = document.querySelector(
                                    'textarea[placeholder="Напишите что нибудь..."]'
                                );
                                var sendButton = document.querySelector(
                                    'button.comments__send-btn'
                                );

                                if (!textarea && !menuClicked) {
                                    if (menuButton) {
                                        menuButton.click();
                                        return "COMMENT_MENU_CLICKED";
                                    }
                                    return "WAIT_READER_COMMENT_UI";
                                }

                                if (!textarea || !sendButton) {
                                    return "WAIT_COMMENT_CONTROLS";
                                }

                                if (sendButton.disabled) {
                                    return "WAIT_SEND_BUTTON";
                                }

                                return "COMMENT_CONTROLS_READY";
                            } catch (e) {
                                return "ERROR:" +
                                    (e && e.message ? e.message : String(e));
                            }
                        })();
                        """.replace("!menuClicked", (!menuClicked).toString())
                            .trimIndent()
                    )

                    val state = try {
                        com.google.gson.Gson().fromJson(
                            rawState,
                            String::class.java
                        ).orEmpty()
                    } catch (_: Exception) {
                        rawState.orEmpty()
                    }

                    when (state) {
                        "COMMENT_CONTROLS_READY" -> controlsReady = true
                        "COMMENT_MENU_CLICKED" -> {
                            if (!menuClicked) {
                                menuClicked = true
                                log(
                                    account.username,
                                    "COMMENT: READER_MENU_CLICKED chapterId=$chapterId"
                                )
                            }
                        }
                        "WAIT_READER_COMMENT_UI",
                        "WAIT_COMMENT_CONTROLS",
                        "WAIT_SEND_BUTTON" -> Unit
                        else -> {
                            if (state.startsWith("ERROR:")) {
                                log(
                                    account.username,
                                    "COMMENT: READER_UI_ERROR " + state,
                                    true
                                )
                            }
                        }
                    }

                    delay(300L)
                }

                controlsReady
            } ?: false
        }

        if (!waitForControls(15_000L)) {
            log(
                account.username,
                "COMMENT: READER_COMMENT_CONTROLS_TIMEOUT chapterId=$chapterId",
                true
            )
            return false
        }

        log(
            account.username,
            "COMMENT: READER_CONTROLS_READY chapterId=$chapterId text='$commentText'"
        )

        val actionStateRaw = evalJs(
            """
            (function() {
                try {
                    var textarea = document.querySelector(
                        'textarea[placeholder="Напишите что нибудь..."]'
                    );
                    var button = document.querySelector(
                        'button.comments__send-btn'
                    );

                    if (!textarea || !button) return "WAIT";

                    textarea.click();
                    textarea.click();
                    textarea.focus();

                    var setter = Object.getOwnPropertyDescriptor(
                        HTMLTextAreaElement.prototype,
                        "value"
                    );

                    if (setter && setter.set) {
                        setter.set.call(textarea, $commentJs);
                    } else {
                        textarea.value = $commentJs;
                    }

                    textarea.dispatchEvent(
                        new Event("input", { bubbles: true })
                    );
                    textarea.dispatchEvent(
                        new Event("change", { bubbles: true })
                    );

                    if (button.disabled) return "BUTTON_DISABLED";

                    button.click();
                    return "SENT";
                } catch (e) {
                    return "ERROR:" +
                        (e && e.message ? e.message : String(e));
                }
            })();
            """.trimIndent()
        )

        val actionState = try {
            com.google.gson.Gson().fromJson(
                actionStateRaw,
                String::class.java
            ).orEmpty()
        } catch (_: Exception) {
            actionStateRaw.orEmpty()
        }

        when (actionState) {
            "SENT" -> {
                log(
                    account.username,
                    "COMMENT: READER_TEXTAREA_DOUBLE_CLICKED chapterId=$chapterId"
                )
                log(
                    account.username,
                    "COMMENT: READER_TEXT_SET chapterId=$chapterId length=${commentText.length}"
                )
                log(
                    account.username,
                    "COMMENT: READER_SEND_CLICKED chapterId=$chapterId"
                )
            }
            else -> {
                log(
                    account.username,
                    "COMMENT: READER_SEND_FAILED chapterId=$chapterId state=$actionState",
                    true
                )
                return false
            }
        }

        val confirmed = withTimeoutOrNull(7_000L) {
            var submitted = false

            while (!submitted) {
                coroutineContext.ensureActive()

                val rawState = evalJs(
                    """
                    (function() {
                        try {
                            var textarea = document.querySelector(
                                'textarea[placeholder="Напишите что нибудь..."]'
                            );
                            var button = document.querySelector(
                                'button.comments__send-btn'
                            );

                            if (!textarea || !button) return "CONFIRMED";
                            if (textarea.value.trim() === "" || button.disabled) {
                                return "CONFIRMED";
                            }

                            return "WAIT";
                        } catch (e) {
                            return "WAIT";
                        }
                    })();
                    """.trimIndent()
                )

                val state = try {
                    com.google.gson.Gson().fromJson(
                        rawState,
                        String::class.java
                    ).orEmpty()
                } catch (_: Exception) {
                    rawState.orEmpty()
                }

                if (state == "CONFIRMED") {
                    submitted = true
                } else {
                    delay(300L)
                }
            }

            submitted
        } ?: false

        if (confirmed) {
            log(
                account.username,
                "COMMENT: READER_SUBMIT_CONFIRMED chapterId=$chapterId"
            )
        } else {
            log(
                account.username,
                "COMMENT: READER_SUBMIT_NOT_CONFIRMED chapterId=$chapterId",
                true
            )
        }

        return confirmed
    }

    // =========================================================
    // SEPARATE COMMENT TASK
    // =========================================================

    private suspend fun runCommentTask(
        account: MangaBuffAccount,
        settings: GlobalSettings,
        webView: WebView
    ) {
        val requested = settings.commentCount.coerceAtLeast(0)
        val remainingDaily = (COMMENT_DAILY_LIMIT - dailyStats.comments).coerceAtLeast(0)
        val target = requested.coerceAtMost(remainingDaily)

        log(
            account.username,
            "TASK: COMMENT_START requested=" + requested +
                " dailyUsed=" + dailyStats.comments + "/" + COMMENT_DAILY_LIMIT +
                " target=" + target
        )

        if (target <= 0) {
            log(
                account.username,
                "COMMENT: DAILY_LIMIT_REACHED used=" + dailyStats.comments +
                    "/" + COMMENT_DAILY_LIMIT
            )
            return
        }

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
                addDaily(account) { it.copy(comments = it.comments + 1) }
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
    // DECK COMMENTS
    // =========================================================

    private suspend fun runDeckCommentTask(
        account: MangaBuffAccount,
        webView: WebView
    ): Int {
        val remainingDaily = (COMMENT_DAILY_LIMIT - dailyStats.comments).coerceAtLeast(0)
        if (remainingDaily <= 0) {
            log(
                account.username,
                "COMMENT: DECK_DAILY_LIMIT_REACHED used=" +
                    dailyStats.comments + "/" + COMMENT_DAILY_LIMIT
            )
            return 0
        }

        // Persist per-account/per-day deck attempts so rerunning the task cannot
        // post multiple comments to the same deck. Reserve before clicking Send:
        // an uncertain network response must not trigger a duplicate comment.
        val deckCommentPrefs = context.getSharedPreferences(
            "mangabuff_deck_comment_guard",
            Context.MODE_PRIVATE
        )
        val deckCommentDay = java.time.LocalDate.now().toString()
        val deckAccountKey = account.username
            .lowercase()
            .replace(Regex("[^a-z0-9._-]"), "_")
        val deckAttemptPrefix = "attempted_${deckCommentDay}_${deckAccountKey}_"
        val lastDeckCommentKey = "last_comment_at_${deckCommentDay}_${deckAccountKey}"
        val deckCommentGapMs = 40_000L

        suspend fun evalJs(script: String): String? =
            suspendCancellableCoroutine { continuation ->
                mainHandler.post {
                    try {
                        webView.evaluateJavascript(script) { result ->
                            if (continuation.isActive) {
                                continuation.resume(result)
                            }
                        }
                    } catch (e: Exception) {
                        log(
                            account.username,
                            "COMMENT: DECK_JS_ERROR error=" + e.message,
                            true
                        )
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            }

        suspend fun loadUrl(url: String) {
            mainHandler.post {
                try {
                    webView.loadUrl(url)
                } catch (e: Exception) {
                    log(
                        account.username,
                        "COMMENT: DECK_LOAD_URL_ERROR url=$url error=" + e.message,
                        true
                    )
                }
            }
        }

        suspend fun waitForUrl(targetUrl: String, timeoutMs: Long): Boolean {
            val normalizedTarget = targetUrl
                .substringBefore('?')
                .substringBefore('#')
                .trimEnd('/')

            return withTimeoutOrNull(timeoutMs) {
                var matched = false

                while (!matched) {
                    coroutineContext.ensureActive()

                    val raw = evalJs("location.href")
                    val current = try {
                        com.google.gson.Gson().fromJson(raw, String::class.java).orEmpty()
                    } catch (_: Exception) {
                        raw.orEmpty()
                    }

                    val normalizedCurrent = current
                        .substringBefore('?')
                        .substringBefore('#')
                        .trimEnd('/')

                    if (normalizedCurrent == normalizedTarget) {
                        matched = true
                    } else {
                        delay(250L)
                    }
                }

                matched
            } ?: false
        }

        loadUrl("https://mangabuff.ru/decks")

        if (!waitForUrl("https://mangabuff.ru/decks", 20_000L)) {
            log(account.username, "COMMENT: DECK_LIST_FAILED reason=PAGE_TIMEOUT", true)
            return 0
        }

        delay(DECK_PAGE_SETTLE_MS)
        log(
            account.username,
            "COMMENT: DECK_PAGE_SETTLE delayMs=" + DECK_PAGE_SETTLE_MS +
                " stage=LIST"
        )

        val rawDeckJson = evalJs(
            """
            (function() {
                try {
                    var nodes = Array.from(
                        document.querySelectorAll(
                            'a.manga-cards__collection-name[href^="/decks/"]'
                        )
                    );

                    var seen = {};
                    var result = [];

                    nodes.forEach(function(node) {
                        if (result.length >= 9) return;

                        var href = node.getAttribute('href') || '';
                        var url = new URL(href, location.href).href
                            .split('#')[0]
                            .split('?')[0];

                        if (
                            !url.startsWith('https://mangabuff.ru/decks/') ||
                            seen[url]
                        ) {
                            return;
                        }

                        seen[url] = true;
                        result.push({
                            url: url,
                            name: (node.textContent || '')
                                .replace(/\s+/g, ' ')
                                .trim()
                        });
                    });

                    return JSON.stringify(result);
                } catch (e) {
                    return "[]";
                }
            })();
            """.trimIndent()
        )

        val deckJson = try {
            com.google.gson.Gson().fromJson(
                rawDeckJson,
                String::class.java
            ).orEmpty()
        } catch (_: Exception) {
            ""
        }

        val deckType =
            object : com.google.gson.reflect.TypeToken<List<Map<String, String>>>() {}.type

        val decks: List<Map<String, String>> = try {
            com.google.gson.Gson().fromJson(deckJson, deckType) ?: emptyList()
        } catch (e: Exception) {
            log(
                account.username,
                "COMMENT: DECK_LIST_PARSE_ERROR error=" + e.message,
                true
            )
            emptyList()
        }

        if (decks.isEmpty()) {
            log(account.username, "COMMENT: DECK_LIST_FAILED reason=NO_DECKS", true)
            return 0
        }

        log(
            account.username,
            "COMMENT: DECK_LIST_READY found=" + decks.size +
                " dailyUsed=" + dailyStats.comments +
                "/" + COMMENT_DAILY_LIMIT
        )

        decks.forEachIndexed { index, deck ->
            log(
                account.username,
                "COMMENT: DECK_FOUND index=" + (index + 1) +
                    " name='" + deck["name"].orEmpty() +
                    "' url=" + deck["url"].orEmpty()
            )
        }

        val unattemptedDecks = decks.filter { deck ->
            val deckUrl = deck["url"].orEmpty()
            val deckId = deckUrl.substringBefore('?').trimEnd('/')
            deckId.isNotBlank() &&
                !deckCommentPrefs.getBoolean(deckAttemptPrefix + deckId.hashCode(), false)
        }
        val targetDecks = unattemptedDecks.take(remainingDaily)
        log(
            account.username,
            "COMMENT: DECK_GUARD eligible=${targetDecks.size} skippedAlreadyAttempted=${decks.size - unattemptedDecks.size} gapSec=40"
        )
        var sentCount = 0

        for ((index, deck) in targetDecks.withIndex()) {
            coroutineContext.ensureActive()

            if (dailyStats.comments >= COMMENT_DAILY_LIMIT) {
                log(
                    account.username,
                    "COMMENT: DECK_DAILY_LIMIT_STOP used=" +
                        dailyStats.comments + "/" + COMMENT_DAILY_LIMIT
                )
                break
            }

            val deckUrl = deck["url"].orEmpty()
            val deckName = deck["name"].orEmpty()
            if (deckUrl.isBlank()) continue

            // Enforce a fixed 40-second gap even if the deck task is started
            // again; the timestamp is shared through persistent preferences.
            val lastCommentAt = deckCommentPrefs.getLong(lastDeckCommentKey, 0L)
            val waitForGap = (deckCommentGapMs - (System.currentTimeMillis() - lastCommentAt))
                .coerceAtLeast(0L)
            if (waitForGap > 0L) {
                log(
                    account.username,
                    "COMMENT: DECK_RATE_LIMIT_WAIT seconds=" + ((waitForGap + 999L) / 1000L)
                )
                delay(waitForGap)
            }

            val commentText = deckCommentPhrases.random()
            val commentJs = com.google.gson.Gson().toJson(commentText)

            log(
                account.username,
                "COMMENT: DECK_COMMENT_ATTEMPT index=" +
                    (index + 1) + "/" + targetDecks.size +
                    " name='" + deckName + "' text='" + commentText + "'"
            )

            loadUrl(deckUrl)

            if (!waitForUrl(deckUrl, 20_000L)) {
                log(
                    account.username,
                    "COMMENT: DECK_PAGE_TIMEOUT name='" + deckName + "'",
                    true
                )
                continue
            }

            delay(DECK_PAGE_SETTLE_MS)
            log(
                account.username,
                "COMMENT: DECK_PAGE_SETTLE delayMs=" + DECK_PAGE_SETTLE_MS +
                    " stage=DECK name='" + deckName + "'"
            )

            var actionClicked = false

            repeat(50) { attempt ->
                if (actionClicked) return@repeat

                coroutineContext.ensureActive()

                val rawState = evalJs(
                    """
                    (function() {
                        try {
                            var textarea = document.querySelector(
                                'textarea[placeholder="Напишите что нибудь..."]'
                            );
                            var button = document.querySelector(
                                'button.comments__send-btn'
                            );

                            if (!textarea || !button) return "WAIT_CONTROLS";
                            if (button.disabled) return "WAIT_BUTTON";

                            textarea.click();
                            textarea.click();
                            textarea.focus();

                            var setter = Object.getOwnPropertyDescriptor(
                                HTMLTextAreaElement.prototype,
                                "value"
                            );

                            if (setter && setter.set) {
                                setter.set.call(textarea, $commentJs);
                            } else {
                                textarea.value = $commentJs;
                            }

                            textarea.dispatchEvent(
                                new Event("input", { bubbles: true })
                            );
                            textarea.dispatchEvent(
                                new Event("change", { bubbles: true })
                            );

                            button.click();
                            return "CLICKED";
                        } catch (e) {
                            return "ERROR:" +
                                (e && e.message ? e.message : String(e));
                        }
                    })();
                    """.trimIndent()
                )

                val state = try {
                    com.google.gson.Gson().fromJson(
                        rawState,
                        String::class.java
                    ).orEmpty()
                } catch (_: Exception) {
                    rawState.orEmpty()
                }

                when {
                    state == "WAIT_CONTROLS" -> delay(300L)
                    state == "WAIT_BUTTON" -> delay(300L)
                    state == "CLICKED" -> {
                        actionClicked = true
                        val deckId = deckUrl.substringBefore('?').trimEnd('/')
                        // Mark before confirmation so an uncertain response is never
                        // retried as a second comment on this deck.
                        deckCommentPrefs.edit()
                            .putBoolean(
                                deckAttemptPrefix + deckId.hashCode(),
                                true
                            )
                            .putLong(lastDeckCommentKey, System.currentTimeMillis())
                            .apply()

                        log(
                            account.username,
                            "COMMENT: DECK_CONTROLS_FOUND name='" +
                                deckName + "' attempt=" + attempt
                        )
                        log(
                            account.username,
                            "COMMENT: DECK_TEXTAREA_DOUBLE_CLICKED name='" +
                                deckName + "'"
                        )
                        log(
                            account.username,
                            "COMMENT: DECK_TEXT_SET name='" +
                                deckName + "' length=" + commentText.length
                        )
                        log(
                            account.username,
                            "COMMENT: DECK_SEND_CLICKED name='" +
                                deckName + "'"
                        )
                    }
                    state.startsWith("ERROR:") -> {
                        log(
                            account.username,
                            "COMMENT: DECK_ACTION_ERROR name='" +
                                deckName + "' " + state,
                            true
                        )
                    }
                }

                if (attempt == 49 && !actionClicked) {
                    log(
                        account.username,
                        "COMMENT: DECK_CONTROLS_TIMEOUT name='" + deckName + "'",
                        true
                    )
                }
            }

            if (!actionClicked) continue

            val confirmed = withTimeoutOrNull(7_000L) {
                var submitted = false

                while (!submitted) {
                    coroutineContext.ensureActive()

                    val rawState = evalJs(
                        """
                        (function() {
                            try {
                                var textarea = document.querySelector(
                                    'textarea[placeholder="Напишите что нибудь..."]'
                                );
                                var button = document.querySelector(
                                    'button.comments__send-btn'
                                );

                                if (!textarea || !button) return "CONFIRMED";
                                if (textarea.value.trim() === "" || button.disabled) {
                                    return "CONFIRMED";
                                }
                                return "WAIT";
                            } catch (e) {
                                return "WAIT";
                            }
                        })();
                        """.trimIndent()
                    )

                    val state = try {
                        com.google.gson.Gson().fromJson(
                            rawState,
                            String::class.java
                        ).orEmpty()
                    } catch (_: Exception) {
                        rawState.orEmpty()
                    }

                    if (state == "CONFIRMED") {
                        submitted = true
                    } else {
                        delay(300L)
                    }
                }

                submitted
            } ?: false

            if (confirmed) {
                sentCount++
                addDaily(account) { stats ->
                    stats.copy(
                        comments = (stats.comments + 1)
                            .coerceAtMost(COMMENT_DAILY_LIMIT)
                    )
                }

                log(
                    account.username,
                    "COMMENT: DECK_SUBMIT_CONFIRMED name='" +
                        deckName + "' daily=" + dailyStats.comments +
                        "/" + COMMENT_DAILY_LIMIT
                )
            } else {
                log(
                    account.username,
                    "COMMENT: DECK_SUBMIT_NOT_CONFIRMED name='" +
                        deckName + "'",
                    true
                )
            }

            // No random short delay here: the next iteration's persistent
            // rate-limit gate enforces exactly 40 seconds between Send clicks.
            if (
                index + 1 < targetDecks.size &&
                dailyStats.comments < COMMENT_DAILY_LIMIT
            ) {
                log(account.username, "COMMENT: DECK_NEXT_DELAY gapSec=40")
            }
        }

        log(
            account.username,
            "TASK: DECK_COMMENT_END sent=" + sentCount +
                " daily=" + dailyStats.comments +
                "/" + COMMENT_DAILY_LIMIT
        )

        return sentCount
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

            webView.webViewClient = object : AutomationWebViewClient(account.username, webView) {

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