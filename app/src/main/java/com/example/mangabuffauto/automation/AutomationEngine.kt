package com.example.mangabuffauto.automation

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.example.mangabuffauto.AndroidBotLog
import com.example.mangabuffauto.task.AutomationTask
import com.example.mangabuffauto.task.TaskExecution
import com.example.mangabuffauto.task.TaskResult
import org.json.JSONObject

/**
 * Главный исполнитель автоматизации.
 *
 * ВАЖНО: здесь больше нет очереди задач.
 * В каждый момент времени может выполняться только ОДНА независимо
 * запущенная задача. После её завершения двигатель переходит в IDLE.
 * Следующая задача запускается только явным вызовом startTask().
 */
class AutomationEngine {

    private val handler = Handler(Looper.getMainLooper())
    private var readNativeTickRunnable: Runnable? = null
    private var backgroundHeartbeatRunnable: Runnable? = null
    @Volatile
    private var backgroundMode: Boolean = false
    private var webView: WebView? = null
    private var persistentState: PersistentAutomationState? = null
    private var currentProfileName: String? = null
    private val taskManager = com.example.mangabuffauto.task.TaskManager()
    private val currentExecution: TaskExecution?
        get() = taskManager.current()

    private val _state = mutableStateOf(AutomationState.IDLE)
    val state: State<AutomationState> = _state

    private val _stats = mutableStateOf(MangaBuffStats())
    val stats: State<MangaBuffStats> = _stats

    private val _readingSpeed = mutableStateOf(1.0f)
    val readingSpeed: State<Float> = _readingSpeed

    /** Переключает скорость чтения между 1x и 2x. */
    fun setReadingSpeed(speed: Float) {
        val next = if (speed >= 1.5f) 2.0f else 1.0f
        _readingSpeed.value = next
        currentProfileName?.let { persistentState?.saveReadingSpeed(it, next) }
        webView?.evaluateJavascript(
            "if(window.__mangaBuffSetSpeed){window.__mangaBuffSetSpeed($next);}else{window.__mangaBuffSpeedMultiplier=$next;}",
            null
        )
    }

    fun toggleReadingSpeed() {
        val next = if (_readingSpeed.value >= 1.5f) 1.0f else 2.0f
        _readingSpeed.value = next
        currentProfileName?.let { persistentState?.saveReadingSpeed(it, next) }
        webView?.evaluateJavascript(
            "if(window.__mangaBuffSetSpeed){window.__mangaBuffSetSpeed($next);}else{window.__mangaBuffSpeedMultiplier=$next;}",
            null
        )
        log("SPEED: ${next}x")
    }

    // Каждая новая загрузка документа получает новый ID.
    private var currentDocumentId: Long = 0L
    private var lastInjectedDocumentId: Long = -1L

    private var timeoutRunnable: Runnable? = null
    private var retryRunnable: Runnable? = null


    var onTaskFinished: ((AutomationTask, TaskResult) -> Unit)? = null

    private val taskFinishedListeners = mutableSetOf<(AutomationTask, TaskResult) -> Unit>()

    fun addTaskFinishedListener(listener: (AutomationTask, TaskResult) -> Unit) {
        synchronized(taskFinishedListeners) { taskFinishedListeners.add(listener) }
    }

    fun removeTaskFinishedListener(listener: (AutomationTask, TaskResult) -> Unit) {
        synchronized(taskFinishedListeners) { taskFinishedListeners.remove(listener) }
    }

    private fun notifyTaskFinished(task: AutomationTask, result: TaskResult) {
        onTaskFinished?.invoke(task, result)
        val listeners = synchronized(taskFinishedListeners) { taskFinishedListeners.toList() }
        listeners.forEach { listener ->
            try { listener(task, result) } catch (e: Exception) {
                log("TASK_FINISH_LISTENER_ERROR: ${e.message}")
            }
        }
    }

    /** Called when a real task execution starts/stops. Used by runtime-only resources
     * such as the temporary read WakeLock. Retries keep the execution active. */
    var onTaskExecutionActiveChanged: ((AutomationTask?, Boolean) -> Unit)? = null

    fun start(webView: WebView, profileName: String? = currentProfileName, stateStore: PersistentAutomationState? = persistentState) {
        // Сначала корректно останавливаем старый WebView, если профиль/страница
        // была заменена. Нельзя терять старую ссылку до stopInternal().
        if (this.webView !== webView) {
            stopInternal(reason = "Engine Start / WebView Replaced")
        } else {
            stopInternal(reason = "Engine Start")
        }

        this.webView = webView
        this.currentProfileName = profileName
        this.persistentState = stateStore
        val storedSpeed = profileName?.let { stateStore?.readingSpeed(it) } ?: 1.0f
        _readingSpeed.value = storedSpeed
        log("=== ENGINE READY ===")

        webView.removeJavascriptInterface("AndroidBot")
        webView.addJavascriptInterface(AndroidBridge(), "AndroidBot")

        _state.value = AutomationState.IDLE
    }

    /**
     * Запускает только переданную задачу.
     * Никаких enqueue/runNext/цепочек здесь нет.
     */
    fun startTask(task: AutomationTask) {
        startTaskInternal(task, attempt = 0, forceReload = false, reason = "Starting new task: ${task.spec.id}")
    }

    /**
     * Explicit user restart. This is different from an ordinary task start:
     * the current WebView document is discarded by loading the task target
     * again. That prevents an old ReadTask document from keeping delayed JS
     * callbacks alive while a new run starts.
     */
    fun restartTask(task: AutomationTask) {
        startTaskInternal(
            task = task,
            attempt = 0,
            forceReload = true,
            reason = "Manual task restart: ${task.spec.id}"
        )
    }

    fun hasTaskInFlight(): Boolean {
        return currentExecution != null || retryRunnable != null
    }

    private fun startTaskInternal(
        task: AutomationTask,
        attempt: Int,
        forceReload: Boolean,
        reason: String
    ) {
        val wv = webView ?: run {
            log("ENGINE: startTask ignored - WebView is null")
            return
        }

        // Любая предыдущая задача полностью инвалидируется перед новой.
        // При ручном restart дополнительно принудительно перезагружаем target URL,
        // чтобы уничтожить старый JS-документ, а не оставлять его таймеры жить
        // внутри той же страницы.
        stopInternal(reason = reason)

        val execution = taskManager.start(task, attempt)
        onTaskExecutionActiveChanged?.invoke(task, true)
        if (task.spec.id == "mangabuff_read" && backgroundMode) {
            startNativeReadTick()
        }
        lastInjectedDocumentId = -1L
        _state.value = AutomationState.STARTING

        log(if (attempt == 0) "=== START TASK: ${task.spec.id} ===" else "=== RETRY TASK: ${task.spec.id} ===")
        log("RUN ID: ${execution.runId}")
        log("ATTEMPT: ${execution.attempt}")
        currentProfileName?.let { profile ->
            persistentState?.markTaskStarted(profile, task.spec.id, execution.runId, execution.attempt)
        }
        log("TARGET URL: ${task.spec.targetUrl}")

        scheduleTimeout(execution)

        val currentUrl = wv.url.orEmpty()
        if (!forceReload && task.isUrlExpected(currentUrl)) {
            log("SKIP loadUrl - ALREADY ON TARGET: $currentUrl")
            notifyPageFinished(currentUrl)
        } else {
            if (forceReload) {
                log("FORCE loadUrl for clean task restart: ${task.spec.targetUrl}")
            }
            wv.loadUrl(task.spec.targetUrl)
        }
    }

    private fun scheduleTimeout(execution: TaskExecution) {
        cancelTimeout()

        val runnable = Runnable {
            if (isCurrent(execution)) {
                log("TIMEOUT: ${execution.runId}")
                handleResult(TaskResult.Timeout)
            }
        }

        timeoutRunnable = runnable
        handler.postDelayed(runnable, execution.task.spec.timeoutMs)
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let(handler::removeCallbacks)
        timeoutRunnable = null
    }

    private fun cancelRetry() {
        retryRunnable?.let(handler::removeCallbacks)
        retryRunnable = null
    }

    fun setBackgroundMode(enabled: Boolean) {
        backgroundMode = enabled

        val isReading = currentExecution?.task?.spec?.id == "mangabuff_read"
        if (enabled && isReading) {
            startNativeReadTick()
        } else {
            stopNativeReadTick()
        }

        if (enabled) {
            startBackgroundHeartbeat()
        } else {
            stopBackgroundHeartbeat()
        }

        webView?.evaluateJavascript(
            "if(window.__mangaBuffSetBackgroundMode){window.__mangaBuffSetBackgroundMode(" + enabled + ");}",
            null
        )
        log("READ: BACKGROUND_MODE=" + enabled + " heartbeat=" + enabled)
    }

    /**
     * Native heartbeat used while the screen is locked. Android/HyperOS may
     * throttle page timers when a WebView is not visible. This heartbeat does
     * not pretend to replace the renderer; it simply keeps the active document
     * reachable and records whether Chromium is still answering callbacks.
     */
    private fun startBackgroundHeartbeat() {
        stopBackgroundHeartbeat()
        val runnable = object : Runnable {
            override fun run() {
                if (!backgroundMode || currentExecution == null) {
                    backgroundHeartbeatRunnable = null
                    return
                }
                val wv = webView
                if (wv != null) {
                    val runId = currentExecution?.runId
                    wv.evaluateJavascript(
                        "(function(){return 'MB_BG_HEARTBEAT:' + document.readyState + ':' + location.href;})()",
                    ) { result ->
                        if (runId != currentExecution?.runId) return@evaluateJavascript
                        log("BG: WEBVIEW_HEARTBEAT ${result?.take(160) ?: "null"}")
                    }
                }
                backgroundHeartbeatRunnable = this
                handler.postDelayed(this, 5000L)
            }
        }
        backgroundHeartbeatRunnable = runnable
        handler.post(runnable)
        log("BG: NATIVE_HEARTBEAT_STARTED")
    }

    private fun stopBackgroundHeartbeat() {
        backgroundHeartbeatRunnable?.let(handler::removeCallbacks)
        if (backgroundHeartbeatRunnable != null) log("BG: NATIVE_HEARTBEAT_STOPPED")
        backgroundHeartbeatRunnable = null
    }

    /**
     * Background-reader heartbeat. WebView JavaScript timers/requestAnimationFrame
     * can be throttled or paused when the WebView is not visible. This native
     * heartbeat therefore drives only the existing reader callbacks and a small
     * native scroll step; it does not change the reader's site selectors/flow.
     */
    private fun startNativeReadTick() {
        stopNativeReadTick()

        val runnable = object : Runnable {
            override fun run() {
                val execution = currentExecution
                if (execution?.task?.spec?.id != "mangabuff_read") {
                    readNativeTickRunnable = null
                    return
                }

                if (backgroundMode) {
                    webView?.evaluateJavascript(
                        "if(window.__mangaBuffNativeReadTick){window.__mangaBuffNativeReadTick();}",
                        null
                    )
                }

                handler.postDelayed(this, 500L)
            }
        }

        readNativeTickRunnable = runnable
        handler.post(runnable)
        log("READ: NATIVE_BACKGROUND_HEARTBEAT_STARTED")
    }

    private fun stopNativeReadTick() {
        readNativeTickRunnable?.let(handler::removeCallbacks)
        if (readNativeTickRunnable != null) {
            log("READ: NATIVE_BACKGROUND_HEARTBEAT_STOPPED")
        }
        readNativeTickRunnable = null
    }

    fun notifyPageStarted(url: String) {
        val execution = currentExecution
        val oldDocumentId = currentDocumentId
        currentDocumentId++
        lastInjectedDocumentId = -1L

        log("PAGE STARTED: $url")
        log("DOCUMENT CHANGED: old=$oldDocumentId new=$currentDocumentId")


        if (execution != null) {
            // Таймаут задачи работает как watchdog одного документа, а не как
            // общий лимит всей длинной цепочки чтения. Каждый новый документ
            // (например, следующая глава) даёт странице новый интервал.
            // Если навигация реально зависла более чем на timeoutMs, сработает
            // обычный timeout/retry.
            scheduleTimeout(execution)
            log("TIMEOUT WATCHDOG RESET: ${execution.task.spec.id} docId=$currentDocumentId")

            _state.value = AutomationState.LOADING
            log("TASK=${execution.task.spec.id} RUN ID=${execution.runId}")

            if (execution.task.spec.id == "mangabuff_read") {
                log("READ: READ_NAVIGATION url=$url docId=$currentDocumentId")
                if (url.contains("/read/") || url.contains("/manga/")) {
                    log("READ: READER_PAGE_DETECTED")
                }
            }
        }
    }

    fun notifyPageFinished(url: String) {
        injectGeneralFixes()

        val execution = currentExecution ?: run {
            val blockState = ProtectionGuard.checkBlock(url, null)
            if (blockState != null) {
                log("PROTECTION WITHOUT TASK: $blockState")
                _state.value = blockState
            }
            return
        }

        val blockState = ProtectionGuard.checkBlock(url, null)
        if (blockState != null) {
            log("PROTECTION TRIGGERED: $blockState")
            _state.value = blockState
            stopInternal(reason = "Protection Triggered: $blockState")
            return
        }

        if (!execution.task.isUrlExpected(url)) {
            log("URL MISMATCH: expected=${execution.task.spec.targetUrl} actual=$url")
            return
        }

        if (lastInjectedDocumentId == currentDocumentId && !execution.task.shouldInjectForDocument(url)) {
            log("INJECTION SKIPPED: docId=$currentDocumentId")
            return
        }

        lastInjectedDocumentId = currentDocumentId
        _state.value = AutomationState.RUNNING
        log("PAGE READY: Doc $currentDocumentId")

        if (execution.task.spec.id == "mangabuff_read") {
            log("READ: READ_PAGE_READY docId=$currentDocumentId")
        }

        // Даём странице немного времени на отрисовку DOM.
        val runId = execution.runId
        val docId = currentDocumentId
        handler.postDelayed({
            val current = currentExecution
            if (current?.runId != runId || currentDocumentId != docId || lastInjectedDocumentId != docId) {
                log("INJECTION CANCELLED: stale run/doc")
                return@postDelayed
            }
            executeCurrentScript()
        }, 1000L)
    }

    fun injectGeneralFixes() {
        val wv = webView ?: return
        val fixScript = """
            (function() {
                if (!window.__installMangaBuffFixes) {
                    window.__installMangaBuffFixes = function() {
                        const trigger = document.querySelector('.header-profile');
                        if (trigger && trigger._tippy) {
                            const instance = trigger._tippy;
                            if (instance.props.appendTo !== document.body) {
                                console.log('MB_FIX: Applying Tippy fix');
                                instance.setProps({
                                    appendTo: () => document.body,
                                    popperOptions: Object.assign({}, instance.props.popperOptions, {
                                        strategy: 'fixed'
                                    })
                                });
                                if (!document.getElementById('mb-tippy-minimal')) {
                                    const s = document.createElement('style');
                                    s.id = 'mb-tippy-minimal';
                                    s.innerHTML = '[data-tippy-root], .tippy-box, .tippy-content { z-index: 2147483647 !important; height: auto !important; max-height: none !important; overflow: visible !important; }';
                                    document.head.appendChild(s);
                                }
                            }
                        }
                    };
                }
                window.__installMangaBuffFixes();
                if (!window.__mbTippyFixTimersScheduled) {
                    window.__mbTippyFixTimersScheduled = true;
                    window.__mbTippyFixTimer1 = setTimeout(window.__installMangaBuffFixes, 2000);
                    window.__mbTippyFixTimer2 = setTimeout(window.__installMangaBuffFixes, 5000);
                }
            })();
        """.trimIndent()
        wv.evaluateJavascript(fixScript, null)
    }

    fun notifyHttpError(code: Int) {
        val blockState = ProtectionGuard.checkHttpStatus(code) ?: return
        log("HTTP ERROR: $code -> $blockState")
        _state.value = blockState
        stopInternal(reason = "HTTP Error $code")
    }

    fun notifyRendererCrashed() {
        log("RENDERER CRASHED")
        stopInternal(reason = "Renderer Crashed")
        _state.value = AutomationState.ERROR
    }

    private fun executeCurrentScript() {
        val execution = currentExecution ?: return
        val wv = webView ?: return

        val runId = execution.runId
        val docId = currentDocumentId

        log("INJECTING SCRIPT: ${execution.task.spec.id}")

        val envScript = """
            (function() {
                try {
                    const oldFix = document.getElementById('mb-style-fix');
                    if (oldFix) oldFix.remove();

                    if (!window.__installMangaBuffFixes) {
                        window.__installMangaBuffFixes = function() {
                            const trigger = document.querySelector('.header-profile');
                            if (trigger && trigger._tippy) {
                                const instance = trigger._tippy;
                                if (instance.props.appendTo !== document.body ||
                                    (instance.props.popperOptions && instance.props.popperOptions.strategy !== 'fixed')) {
                                    instance.setProps({
                                        appendTo: () => document.body,
                                        popperOptions: Object.assign({}, instance.props.popperOptions, {
                                            strategy: 'fixed'
                                        })
                                    });
                                }
                            }
                        };
                    }

                    window.__mangaBuffRunId = ${jsString(runId)};
                    window.__mangaBuffDocId = $docId;
                    window.__mangaBuffSpeedMultiplier = ${_readingSpeed.value};
                    window.__mangaBuffTaskRunning = true;
                    window.__mangaBuffBackgroundMode = ${backgroundMode};

                    window.__installMangaBuffFixes();
                    console.log('BOT_READY:' + window.__mangaBuffRunId);
                } catch (e) {
                    console.error('ENV_ERROR: ' + (e && e.message || e));
                    AndroidBot.log('ENV_ERROR: ' + (e && e.message || e));
                }
            })();
        """.trimIndent()

        wv.evaluateJavascript(envScript) {
            val current = currentExecution
            if (current?.runId != runId || currentDocumentId != docId) {
                log("SCRIPT INJECTION ABORTED: stale run/doc")
                return@evaluateJavascript
            }

            val wrappedScript = """
                try {
                    ${execution.task.spec.script}
                } catch (e) {
                    console.error("TASK_SCRIPT_ERROR: " + (e && (e.stack || e.message) || e));
                    AndroidBot.log("TASK_SCRIPT_ERROR: " + (e && (e.stack || e.message) || e));
                    if (window.__mangaBuffRunId === ${jsString(runId)}) {
                        window.__mangaBuffTaskRunning = false;
                        AndroidBot.finish(${jsString(execution.task.spec.id)}, ${jsString(runId)}, false);
                    }
                }
            """.trimIndent()

            wv.evaluateJavascript(wrappedScript) { result ->
                log("TASK SCRIPT EVAL RESULT: $result")
            }
            }
    }

    fun stop(reason: String = "Manual") {
        stopInternal(reason)
        _state.value = AutomationState.STOPPED
    }

    private fun stopInternal(reason: String) {
        cancelTimeout()
        cancelRetry()
        stopNativeReadTick()
        stopBackgroundHeartbeat()

        taskManager.clear()
        onTaskExecutionActiveChanged?.invoke(null, false)
        lastInjectedDocumentId = -1L

        webView?.let { wv ->
            wv.stopLoading()
            wv.evaluateJavascript(
                "window.__mangaBuffTaskRunning=false;window.__mangaBuffRunId=null;window.__mangaBuffDocId=null;if(window.__mangaBuffNextChapterTimer){clearTimeout(window.__mangaBuffNextChapterTimer);window.__mangaBuffNextChapterTimer=null;}if(window.__mbTippyFixTimer1){clearTimeout(window.__mbTippyFixTimer1);window.__mbTippyFixTimer1=null;}if(window.__mbTippyFixTimer2){clearTimeout(window.__mbTippyFixTimer2);window.__mbTippyFixTimer2=null;}window.__mbTippyFixTimersScheduled=false;if(window.__mangaBuffSetBackgroundMode){window.__mangaBuffSetBackgroundMode(false);}",
                null
            )
        }

        log("ENGINE STOP: $reason")
    }

    /**
     * Полностью отсоединяет текущий WebView, но оставляет Engine пригодным
     * для подключения нового WebView-профиля.
     */
    /** Сбрасывает только отображаемую статистику. */
    fun resetStats() {
        _stats.value = MangaBuffStats()
    }

    fun detachWebView() {
        stopInternal("Detach WebView")
        webView?.removeJavascriptInterface("AndroidBot")
        webView = null
        currentProfileName = null
        lastInjectedDocumentId = -1L
        currentDocumentId++
    }

    fun destroy() {
        synchronized(taskFinishedListeners) { taskFinishedListeners.clear() }
        stopInternal("Destroy")
        webView?.removeJavascriptInterface("AndroidBot")
        webView = null
        handler.removeCallbacksAndMessages(null)
    }

    private fun isCurrent(execution: TaskExecution): Boolean {
        val current = currentExecution ?: return false
        return current.runId == execution.runId && current.task.spec.id == execution.task.spec.id
    }

    private fun handleResult(result: TaskResult) {
        val execution = currentExecution ?: run {
            log("RESULT IGNORED: no active task -> $result")
            return
        }

        log("RESULT: ${execution.task.spec.id} -> $result")
        cancelTimeout()

        when (result) {
            is TaskResult.Success,
            TaskResult.LimitReached -> {
                taskManager.clear()
                onTaskExecutionActiveChanged?.invoke(execution.task, false)
                _state.value = AutomationState.IDLE
                currentProfileName?.let { persistentState?.markTaskFinished(it) }
                notifyTaskFinished(execution.task, result)
                clearTaskJsState(execution.runId)
                // Никакого запуска следующей задачи.
            }

            is TaskResult.Failure -> {
                if (result.canRetry && execution.attempt < execution.task.spec.maxRetries) {
                    val nextAttempt = execution.attempt + 1
                    log("RETRY: ${execution.task.spec.id}, attempt=$nextAttempt")
                    taskManager.clear()
                    _state.value = AutomationState.IDLE
                    notifyTaskFinished(execution.task, result)
                    clearTaskJsState(execution.runId)

                    val retryTask = execution.task
                    val runnable = Runnable {
                        retryRunnable = null
                        startTaskWithAttempt(retryTask, nextAttempt)
                    }
                    retryRunnable = runnable
                    handler.postDelayed(runnable, 1500L)
                } else {
                    taskManager.clear()
                    onTaskExecutionActiveChanged?.invoke(execution.task, false)
                    _state.value = AutomationState.IDLE
                    notifyTaskFinished(execution.task, result)
                    clearTaskJsState(execution.runId)
                }
            }

            TaskResult.Blocked -> {
                _state.value = AutomationState.BLOCKED
                taskManager.clear()
                onTaskExecutionActiveChanged?.invoke(execution.task, false)
                clearTaskJsState(execution.runId)
                currentProfileName?.let { persistentState?.markTaskFinished(it) }
                notifyTaskFinished(execution.task, result)
            }

            TaskResult.Captcha -> {
                _state.value = AutomationState.CAPTCHA
                taskManager.clear()
                onTaskExecutionActiveChanged?.invoke(execution.task, false)
                clearTaskJsState(execution.runId)
                currentProfileName?.let { persistentState?.markTaskFinished(it) }
                notifyTaskFinished(execution.task, result)
            }

            TaskResult.Timeout -> {
                // Timeout is converted to an ordinary retryable failure.
                handleResult(TaskResult.Failure("Timeout", canRetry = true))
            }
        }
    }

    private fun startTaskWithAttempt(task: AutomationTask, attempt: Int) {
        startTaskInternal(
            task = task,
            attempt = attempt,
            forceReload = true,
            reason = "Retry: ${task.spec.id}"
        )
    }

    private fun clearTaskJsState(runId: String) {
        webView?.evaluateJavascript(
            "if(window.__mangaBuffRunId===${jsString(runId)}){window.__mangaBuffTaskRunning=false;window.__mangaBuffRunId=null;window.__mangaBuffDocId=null;}",
            null
        )
    }

    private fun log(message: String) {
        Log.i("MangaBuffAuto", message)
        AndroidBotLog.log(message)
    }

    fun logConsole(level: String, message: String) {
        log("[CONSOLE] $level: $message")
    }

    fun requestStatsUpdate() {
        val wv = webView ?: return
        val exec = currentExecution
        val runId = exec?.runId?.let(::jsString) ?: "null"
        val script = "var currentRunId=$runId;var currentDocId=$currentDocumentId;" + StatsInspector.SCRIPT
        wv.evaluateJavascript(script, null)
    }

    private fun jsString(value: String): String =
        "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029") + "\""

    private fun dispatchNativeTap(x: Float, y: Float) {
        val wv = webView ?: return
        val density = wv.resources.displayMetrics.density
        val time = SystemClock.uptimeMillis()
        val px = x * density
        val py = y * density
        wv.dispatchTouchEvent(MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, px, py, 0))
        wv.dispatchTouchEvent(MotionEvent.obtain(time, time + 50L, MotionEvent.ACTION_UP, px, py, 0))
    }

    private fun dispatchNativeSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val wv = webView ?: return
        val density = wv.resources.displayMetrics.density
        val sx1 = x1 * density
        val sy1 = y1 * density
        val sx2 = x2 * density
        val sy2 = y2 * density
        val downTime = SystemClock.uptimeMillis()

        wv.dispatchTouchEvent(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, sx1, sy1, 0))
        val steps = 15
        for (i in 1..steps) {
            val f = i.toFloat() / steps
            val curX = sx1 + (sx2 - sx1) * f
            val curY = sy1 + (sy2 - sy1) * f
            wv.dispatchTouchEvent(MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, curX, curY, 0))
            SystemClock.sleep((durationMs / steps).coerceAtLeast(1L))
        }
        wv.dispatchTouchEvent(MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, sx2, sy2, 0))
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun tap(x: Float, y: Float, runId: String?, docId: Long) {
            handler.post {
                if (currentExecution?.runId == runId && currentDocumentId == docId) {
                    dispatchNativeTap(x, y)
                } else {
                    log("STALE_RESULT_IGNORED: tap run=$runId doc=$docId")
                }
            }
        }

        @JavascriptInterface
        fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long, runId: String?, docId: Long) {
            handler.post {
                if (currentExecution?.runId == runId && currentDocumentId == docId) {
                    dispatchNativeSwipe(x1, y1, x2, y2, duration)
                } else {
                    log("STALE_RESULT_IGNORED: swipe run=$runId doc=$docId")
                }
            }
        }

        @JavascriptInterface
        fun log(message: String?) {
            handler.post { log(message.orEmpty()) }
        }

        @JavascriptInterface
        fun reportLimit(taskId: String?, runId: String?) {
            handler.post {
                val current = currentExecution ?: return@post
                if (current.task.spec.id == taskId && current.runId == runId) {
                    handleResult(TaskResult.LimitReached)
                } else {
                    log("STALE_RESULT_IGNORED: limit task=$taskId run=$runId")
                }
            }
        }

        @JavascriptInterface
        fun reportBlock(type: String?, runId: String?, docId: Long) {
            handler.post {
                val current = currentExecution
                if (current == null || current.runId != runId || currentDocumentId != docId) {
                    log("STALE_RESULT_IGNORED: block run=$runId doc=$docId")
                    return@post
                }
                val result = if (type.equals("CAPTCHA", ignoreCase = true)) {
                    TaskResult.Captcha
                } else {
                    TaskResult.Blocked
                }
                log("JS PROTECTION: ${current.task.spec.id} type=$type")
                handleResult(result)
            }
        }

        @JavascriptInterface
        fun updateStats(json: String?, docId: Long) {
            handler.post {
                if (currentDocumentId != docId) {
                    // Stats can be updated even if no task is running, but only for current doc.
                    return@post
                }
                // ... rest of the code is unchanged in replacement Content but I need to include it or use multiple chunks

                try {
                    val obj = JSONObject(json ?: "{}")
                    fun optInt(key: String): Int? = if (obj.isNull(key)) null else obj.optInt(key)

                    val old = _stats.value
                    val merged = MangaBuffStats(
                        diamonds = optInt("diamonds") ?: old.diamonds,
                        mineOre = optInt("mineOre") ?: old.mineOre,
                        cardsCurrent = optInt("cardsCurrent") ?: old.cardsCurrent,
                        cardsMax = optInt("cardsMax") ?: old.cardsMax,
                        adsCurrent = optInt("adsCurrent") ?: old.adsCurrent,
                        adsMax = optInt("adsMax") ?: old.adsMax,
                        commentsCurrent = optInt("commentsCurrent") ?: old.commentsCurrent,
                        commentsMax = optInt("commentsMax") ?: old.commentsMax,
                    )

                    if (old != merged) {
                        _stats.value = merged
                        log("STATS: diamonds=${merged.diamonds ?: 0} cards=${merged.cardsCurrent ?: 0}/${merged.cardsMax ?: 0} ads=${merged.adsCurrent ?: 0}/${merged.adsMax ?: 0} comments=${merged.commentsCurrent ?: 0}/${merged.commentsMax ?: 0}")
                    }
                } catch (e: Exception) {
                    log("STATS ERROR: ${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun finish(taskId: String?, runId: String?, success: Boolean) {
            handler.post {
                val current = currentExecution
                if (current == null) {
                    log("STALE_RESULT_IGNORED: finish no_task task=$taskId run=$runId")
                    return@post
                }

                if (current.task.spec.id != taskId || current.runId != runId) {
                    log("STALE_RESULT_IGNORED: finish mismatch task=$taskId run=$runId")
                    return@post
                }

                if (success) {
                    handleResult(TaskResult.Success("JS finished"))
                } else {
                    handleResult(TaskResult.Failure("JS reported failure"))
                }
            }
        }
    }
}
