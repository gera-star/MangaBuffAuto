package com.example.myapplication.automation

import android.content.Context
import com.example.myapplication.data.AccountRepository
import com.example.myapplication.data.MangaBuffAccount
import com.example.myapplication.service.MangaBuffForegroundService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class TradeRuleMode(val title: String) {
    STRICT("Строгий"),
    VALUE("По сумме")
}

data class TradeLogEntry(
    val time: Long = System.currentTimeMillis(),
    val message: String,
    val isError: Boolean = false,
    val accountId: String = ""
)

/**
 * Isolated trade worker. It uses the selected account's existing Cookie,
 * CSRF token and User-Agent; it does not create or change app account sessions.
 */
class TradeAutomation(context: Context) {
    private val appContext = context.applicationContext
    private val accountRepository = AccountRepository(appContext)
    private val prefs = appContext.getSharedPreferences("mangabuff_trade_mode", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Mirrors the browser plugin's serialized MangaBuff queue. The whole request
    // is inside the lock so quick consecutive catalogue lookups cannot overlap.
    private val requestQueueLock = Any()
    @Volatile private var lastRequestFinishedAt = 0L

    private val _logs = MutableStateFlow<List<TradeLogEntry>>(emptyList())
    val logs: StateFlow<List<TradeLogEntry>> = _logs.asStateFlow()
    private val logsLock = Any()
    private val processedLock = Any()
    private val logsByAccount = mutableMapOf<String, List<TradeLogEntry>>()
    @Volatile private var visibleLogAccountId: String? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    private val _waitingForExecution = MutableStateFlow(false)
    val waitingForExecution: StateFlow<Boolean> = _waitingForExecution.asStateFlow()

    private val _activeAccountId = MutableStateFlow<String?>(null)
    val activeAccountId: StateFlow<String?> = _activeAccountId.asStateFlow()

    @Volatile private var worker: Job? = null
    @Volatile private var generation: Long = 0L
    private val skippedLogged = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val suitableLogged = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    @Volatile private var activeSession: TradeSession? = null
    private class TradeSession(val account: MangaBuffAccount) {
        @Volatile var csrfToken: String = account.getSafeCsrfToken()
        @Volatile var autoAccept: Boolean = false
        @Volatile var cancelled: Boolean = false
        val activeCall = java.util.concurrent.atomic.AtomicReference<Call?>(null)
        val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        fun cancel() {
            cancelled = true
            activeCall.getAndSet(null)?.cancel()
            runCatching { client.dispatcher.cancelAll() }
            runCatching { client.connectionPool.evictAll() }
        }
    }

    private fun scopedKey(base: String, accountId: String): String = "$base::$accountId"

    fun selectLogAccount(accountId: String) {
        synchronized(logsLock) {
            visibleLogAccountId = accountId
            _logs.value = logsByAccount[accountId].orEmpty()
        }
    }

    fun savedMode(accountId: String): TradeRuleMode =
        runCatching {
            TradeRuleMode.valueOf(prefs.getString(scopedKey(KEY_MODE, accountId), TradeRuleMode.STRICT.name)!!)
        }.getOrDefault(TradeRuleMode.STRICT)

    fun saveMode(accountId: String, mode: TradeRuleMode) {
        prefs.edit().putString(scopedKey(KEY_MODE, accountId), mode.name).apply()
    }

    fun savedProfitCoefficient(accountId: String): String =
        prefs.getString(scopedKey(KEY_PROFIT, accountId), "1.5") ?: "1.5"

    fun saveProfitCoefficient(accountId: String, value: String) {
        prefs.edit().putString(scopedKey(KEY_PROFIT, accountId), value).apply()
    }

    fun savedAutoAcceptEnabled(accountId: String): Boolean =
        prefs.getBoolean(scopedKey(KEY_AUTO_ACCEPT, accountId), false)

    fun setAutoAcceptEnabled(accountId: String, enabled: Boolean) {
        prefs.edit().putBoolean(scopedKey(KEY_AUTO_ACCEPT, accountId), enabled).apply()
        activeSession?.takeIf { it.account.id == accountId }?.let { it.autoAccept = enabled }
        addLog(
            if (enabled) "Автопринятие включено пользователем."
            else "Автопринятие выключено: новые предложения только анализируются.",
            accountId = accountId
        )
    }

    fun savedSpecialInstanceWeights(accountId: String): String =
        prefs.getString(scopedKey(KEY_SPECIAL_INSTANCE_WEIGHTS, accountId), "") ?: ""

    fun saveSpecialInstanceWeights(accountId: String, value: String) {
        prefs.edit().putString(scopedKey(KEY_SPECIAL_INSTANCE_WEIGHTS, accountId), value.take(MAX_POLICY_TEXT_LENGTH)).apply()
    }

    private fun specialInstanceWeights(accountId: String): Map<String, Int> {
        val result = linkedMapOf<String, Int>()
        savedSpecialInstanceWeights(accountId).lineSequence().map(String::trim).filter(String::isNotEmpty).forEach { line ->
            val sep = line.indexOf('=')
            if (sep <= 0) return@forEach
            val id = line.substring(0, sep).trim()
            val weight = line.substring(sep + 1).trim().toIntOrNull()
            if (id.isNotBlank() && id.all(Char::isDigit) && weight != null && weight in 1..8) result[id] = weight
        }
        return result
    }

    fun start(account: MangaBuffAccount, mode: TradeRuleMode, profitCoefficient: Double) {
        stopInternal(notifyService = false)
        if (account.isOffline || !isAccountAvailable(account.id)) {
            addLog("Мониторинг не запущен: аккаунт удалён или находится в OFFLINE.", true, account.id)
            MangaBuffForegroundService.setTradeMonitorActive(appContext, false)
            return
        }
        if (account.getSafeCookiesJson().isBlank()) {
            addLog("У аккаунта нет сохранённых Cookie. Авторизуй аккаунт обычным способом.", true, account.id)
            MangaBuffForegroundService.setTradeMonitorActive(appContext, false)
            return
        }
        val coefficient = if (profitCoefficient.isFinite()) profitCoefficient.coerceIn(1.0, 10.0) else 1.5
        val session = TradeSession(account).apply { autoAccept = savedAutoAcceptEnabled(account.id) }
        activeSession = session
        _activeAccountId.value = account.id
        _isRunning.value = true
        _waitingForExecution.value = true
        saveMode(account.id, mode)
        saveProfitCoefficient(account.id, coefficient.toString())
        selectLogAccount(account.id)
        addLog("Мониторинг запущен для «${account.username}».", accountId = account.id)
        if (!MangaBuffForegroundService.setTradeMonitorActive(appContext, true)) {
            session.cancel()
            activeSession = null
            _isRunning.value = false
            _waitingForExecution.value = false
            _activeAccountId.value = null
            addLog("Не удалось запустить foreground-сервис; мониторинг обменов остановлен.", true, account.id)
            return
        }
        val runId = synchronized(this) { generation += 1L; generation }
        worker = scope.launch {
            var csrfInitialized = false
            try {
                while (isActive && canRun(session, runId)) {
                    var executionPermit: AccountExecutionGate.Permit? = null
                    try {
                        _waitingForExecution.value = true
                        executionPermit = AccountExecutionGate.acquire()
                        if (!canRun(session, runId)) return@launch
                        _waitingForExecution.value = false
                        addLog("Эксклюзивный слот получен; проверяю предложения.", accountId = account.id)
                        if (!csrfInitialized || session.csrfToken.isBlank()) {
                            refreshCsrf(session)
                            csrfInitialized = true
                        }
                        if (!canRun(session, runId)) return@launch
                        scanPendingTrades(session, mode, coefficient, runId)
                    } finally {
                        executionPermit?.release()
                        if (canRun(session, runId)) _waitingForExecution.value = false
                    }
                    if (canRun(session, runId)) delay(POLL_INTERVAL_MS)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!session.cancelled) addLog(
                    "Цикл автообмена остановлен: ${e.message ?: e.javaClass.simpleName}",
                    true,
                    account.id
                )
            } finally {
                session.cancel()
                if (generation == runId) {
                    _isRunning.value = false
                    _waitingForExecution.value = false
                    _activeAccountId.value = null
                    if (activeSession === session) activeSession = null
                    MangaBuffForegroundService.setTradeMonitorActive(appContext, false)
                }
            }
        }
    }

    private fun isAccountAvailable(accountId: String): Boolean = runCatching {
        accountRepository.getAccounts().any { it.id == accountId && !it.isOffline }
    }.getOrDefault(false)

    private fun canRun(session: TradeSession, runId: Long) =
        !session.cancelled && activeSession === session && generation == runId && isAccountAvailable(session.account.id)

    fun stop() = stopInternal(notifyService = true)

    private fun stopInternal(notifyService: Boolean) {
        synchronized(this) { generation += 1L }
        val old = activeSession
        activeSession = null
        old?.cancel()
        worker?.cancel()
        worker = null
        _isRunning.value = false
        _waitingForExecution.value = false
        _activeAccountId.value = null
        if (notifyService) MangaBuffForegroundService.setTradeMonitorActive(appContext, false)
    }

    fun stopForAccount(accountId: String) {
        if (activeSession?.account?.id == accountId) stop()
    }

    fun forgetAccount(accountId: String) {
        stopForAccount(accountId)
        prefs.edit()
            .remove(scopedKey(KEY_MODE, accountId))
            .remove(scopedKey(KEY_PROFIT, accountId))
            .remove(scopedKey(KEY_AUTO_ACCEPT, accountId))
            .remove(scopedKey(KEY_SPECIAL_INSTANCE_WEIGHTS, accountId))
            .remove(scopedKey(KEY_PROCESSED, accountId))
            .remove(scopedKey(KEY_PROCESSED_TTL, accountId))
            .apply()
        synchronized(logsLock) {
            logsByAccount.remove(accountId)
            if (visibleLogAccountId == accountId) {
                visibleLogAccountId = null
                _logs.value = emptyList()
            }
        }
        skippedLogged.removeAll { it.startsWith("$accountId:") }
        suitableLogged.removeAll { it.startsWith("$accountId:") }
    }

    fun close() {
        stop()
        scope.coroutineContext[Job]?.cancel()
    }

    private fun addLog(message: String, isError: Boolean = false, accountId: String? = null) {
        val ownerId = accountId ?: activeSession?.account?.id ?: visibleLogAccountId.orEmpty()
        synchronized(logsLock) {
            val next = (logsByAccount[ownerId].orEmpty() +
                TradeLogEntry(message = message, isError = isError, accountId = ownerId)).takeLast(MAX_LOGS)
            logsByAccount[ownerId] = next
            if (visibleLogAccountId == ownerId) _logs.value = next
        }
    }

    private suspend fun scanPendingTrades(
        session: TradeSession, mode: TradeRuleMode, coefficient: Double, expectedGeneration: Long
    ) {
        val offers = linkedMapOf<String, String>()
        var page = 1
        var maxPage = 1
        while (page <= maxPage && page <= MAX_TRADE_PAGES && canRun(session, expectedGeneration)) {
            val listUrl = if (page == 1) "$BASE_URL/trades" else "$BASE_URL/trades?page=$page"
            val html = requestHtml(session, listUrl)
            if (html == null) {
                if (canRun(session, expectedGeneration)) addLog("Не удалось загрузить список предложений (страница $page). Проверю позже.", true, session.account.id)
                break
            }
            if (looksLikeLogin(html)) {
                addLog("Сессия «${session.account.username}» не авторизована или истекла. Проверь её в обычном меню.", true, session.account.id)
                return
            }
            val doc = Jsoup.parse(html, listUrl)
            doc.select("a.trade__list-item[href]").forEach { a ->
                val id = TRADE_PATH_REGEX.find(a.attr("href"))?.groupValues?.getOrNull(1) ?: return@forEach
                if (!offers.containsKey(id)) offers[id] = a.selectFirst(".trade__list-name")?.text()?.trim().orEmpty()
            }
            val lastPage = doc.select(".pagination__button a[href]").mapNotNull {
                Regex("[?&]page=(\\d+)").find(it.attr("href"))?.groupValues?.getOrNull(1)?.toIntOrNull()
            }.maxOrNull() ?: page
            if (lastPage > maxPage) maxPage = lastPage
            page++
        }
        if (!canRun(session, expectedGeneration)) return
        if (offers.isEmpty()) { addLog("Новых предложений на странице не найдено.", accountId = session.account.id); return }
        for ((tradeId, sender) in offers) {
            if (!canRun(session, expectedGeneration) || !currentCoroutineContext().isActive) return
            val sessionTradeKey = "${session.account.id}:$tradeId"
            if (isProcessed(session.account.id, tradeId)) continue
            val detailUrl = "$BASE_URL/trades/$tradeId"
            val html = requestHtml(session, detailUrl)
            if (html == null) {
                if (canRun(session, expectedGeneration)) addLog("Обмен #$tradeId: не удалось загрузить детали; проверю позже.", true, session.account.id)
                continue
            }
            val doc = Jsoup.parse(html, detailUrl)
            if (doc.selectFirst(".trade__accepted-btn") == null) {
                if (skippedLogged.add(sessionTradeKey)) addLog("Обмен #$tradeId: кнопка принятия не найдена; действие пропущено.", accountId = session.account.id)
                continue
            }
            val result = analyzeTrade(session, doc, tradeId, mode, coefficient)
            if (!canRun(session, expectedGeneration) || !currentCoroutineContext().isActive) return
            if (!result.accept) {
                if (skippedLogged.add(sessionTradeKey)) addLog("Обмен #$tradeId ${sender}: пропуск — ${result.reason}", accountId = session.account.id)
                suitableLogged.remove(sessionTradeKey)
                continue
            }
            skippedLogged.remove(sessionTradeKey)
            if (!session.autoAccept) {
                if (suitableLogged.add(sessionTradeKey)) addLog("Обмен #$tradeId ${sender}: подходит, получаешь ${result.receivedCount} карты. Только анализ, запрос не отправлялся.", accountId = session.account.id)
                continue
            }
            suitableLogged.remove(sessionTradeKey)
            if (!canRun(session, expectedGeneration) || !currentCoroutineContext().isActive) return
            if (session.csrfToken.isBlank()) refreshCsrf(session)
            if (session.cancelled || !canRun(session, expectedGeneration)) return
            if (session.csrfToken.isBlank()) {
                if (suitableLogged.add(sessionTradeKey)) addLog("Обмен #$tradeId: CSRF-токен отсутствует, принятие не отправлено.", true, session.account.id)
                continue
            }
            markProcessed(session.account.id, tradeId) // avoid replay only for this account
            addLog("Обмен #$tradeId: условия подходят. Отправляю одну попытку принятия.", accountId = session.account.id)
            when (acceptTrade(session, detailUrl, tradeId)) {
                AcceptResult.ACCEPTED -> addLog("Обмен #$tradeId: сервер подтвердил принятие.", accountId = session.account.id)
                AcceptResult.REJECTED -> addLog("Обмен #$tradeId: сервер отклонил запрос. Повторного POST не будет; проверь вручную.", true, session.account.id)
                AcceptResult.UNKNOWN -> addLog("Обмен #$tradeId: ответ не распознан. Повторный POST запрещён; проверь статус вручную.", true, session.account.id)
                AcceptResult.CANCELLED -> addLog("Обмен #$tradeId: запрос прерван остановкой; проверь статус вручную.", true, session.account.id)
            }
        }
    }
    private data class TradeCard(
        val catalogId: String, val instanceId: String?, val rank: String?, val lockState: LockState,
        val ownerTradeLocked: Boolean, val markedProtectedOrFavorite: Boolean
    )
    private enum class LockState { LOCKED, UNLOCKED, UNKNOWN }
    private data class TradeAnalysis(val accept: Boolean, val reason: String, val receivedCount: Int = 0)
    private enum class AcceptResult { ACCEPTED, REJECTED, UNKNOWN, CANCELLED }
    private fun analyzeTrade(session: TradeSession, doc: Document, tradeId: String, mode: TradeRuleMode, coefficient: Double): TradeAnalysis {
        var giveBlock: Element? = null
        var receiveBlock: Element? = null
        for (block in doc.select(".trade__main-items")) {
            val labels = buildString {
                listOf(block.previousElementSibling(), block.parent()?.previousElementSibling(),
                    block.parent()?.selectFirst(".trade__main-title, .trade__main-label, h2, h3, h4"),
                    block.parent()?.parent()?.selectFirst(".trade__main-title, .trade__main-label, h2, h3, h4"),
                    block.parent()?.parent()?.previousElementSibling())
                    .filterNotNull().forEach { append(' ').append(it.text()) }
            }.lowercase()
            when {
                labels.contains("вы отдадите") -> giveBlock = block
                labels.contains("вам предлагают") || labels.contains("вы получите") -> receiveBlock = block
            }
        }
        if (giveBlock == null || receiveBlock == null || giveBlock === receiveBlock) return TradeAnalysis(false, "не удалось однозначно определить стороны обмена по подписям")
        val give = giveBlock!!.select("a.trade__main-item")
        val receive = receiveBlock!!.select("a.trade__main-item")
        if (give.size != 1) return TradeAnalysis(false, "нужно отдавать ровно одну карту, найдено: ${give.size}")
        if (receive.size < 2) return TradeAnalysis(false, "получается меньше двух карт")
        val offered = give.map { parseTradeCard(session, it) }
        val incoming = receive.map { parseTradeCard(session, it) }
        for (c in offered) {
            if (c.catalogId.isBlank()) return TradeAnalysis(false, "не найден catalog ID отдаваемой карты")
            if (c.instanceId.isNullOrBlank()) return TradeAnalysis(false, "не найден card_user_id конкретного экземпляра")
            if (c.ownerTradeLocked) return TradeAnalysis(false, "у владельца карты отмечен запрет обменов")
            if (c.markedProtectedOrFavorite) return TradeAnalysis(false, "карта помечена на странице как избранная/защищённая")
            if (c.lockState == LockState.LOCKED) return TradeAnalysis(false, "отдаваемая карта заблокирована")
            if (c.lockState == LockState.UNKNOWN) return TradeAnalysis(false, "невозможно уверенно определить состояние замка")
        }
        val g = offered.single()
        val rank = g.rank ?: return TradeAnalysis(false, "не удалось определить ранг отдаваемой карты")
        val receives = incoming.map { c ->
            val r = c.rank ?: return TradeAnalysis(false, "не удалось определить ранг получаемой карты")
            TradeValueCard(r, c.catalogId, c.instanceId)
        }
        val reason = TradePolicy.rejectionReason(TradeValueCard(rank, g.catalogId, g.instanceId),
            receives, mode, coefficient, specialInstanceWeights(session.account.id))
        return if (reason == null) TradeAnalysis(true, "подходит", receive.size) else TradeAnalysis(false, reason)
    }
    private fun parseTradeCard(session: TradeSession, element: Element): TradeCard {
        val href = element.attr("href")
        val catalogId = element.attr("data-card-id").ifBlank { CARD_PATH_REGEX.find(href)?.groupValues?.getOrNull(1).orEmpty() }
        val ownerHref = element.selectFirst("a.card-show__owner[href*='card_user_id='], a[href*='card_user_id=']")?.attr("href").orEmpty()
        val instanceId = CARD_USER_ID_REGEX.find(ownerHref)?.groupValues?.getOrNull(1)
            ?: element.selectFirst("[data-card-user-id]")?.attr("data-card-user-id")?.takeIf { it.isNotBlank() }
        val lock = element.selectFirst(".lock-card-btn, .card-actions__btn--lock")
        val icon = lock?.selectFirst("i")
        val title = lock?.attr("title").orEmpty().ifBlank { lock?.attr("aria-label").orEmpty() }
        val classes = (lock?.classNames().orEmpty() + icon?.classNames().orEmpty()).joinToString(" ").lowercase()
        val aria = lock?.attr("aria-pressed")
        val state = when {
            aria == "true" || title.contains("разблокировать", true) || classes.contains("icon-lock") || classes.contains("--locked") -> LockState.LOCKED
            aria == "false" || title.contains("заблокировать", true) || classes.contains("icon-unlock") || classes.contains("--unlocked") -> LockState.UNLOCKED
            else -> LockState.UNKNOWN
        }
        val favorite = element.selectFirst(".card-show__favorite, .card-show__protected, .manga-cards__favorite, .is-favorite, [data-is-favorite='1'], [data-is-favorite='true'], [data-favorite='true'], [aria-label*='Избранное']") != null ||
            element.attr("data-is-favorite").equals("1") || element.attr("data-is-favorite").equals("true", true) || element.attr("data-protected").equals("true", true)
        val tradeLocked = element.selectFirst(".card-show__owner-icon--trade-lock") != null
        val rank = if (catalogId.isNotBlank()) resolveCatalogRank(session, catalogId) else null
        return TradeCard(catalogId, instanceId, rank, state, tradeLocked, favorite)
    }

    private fun resolveCatalogRank(session: TradeSession, cardId: String): String? {
        if (session.cancelled) return null
        val key = RANK_CACHE_PREFIX + cardId
        val cached = prefs.getString(key, null)
        if (cached != null && cached != NO_RANK) return cached
        if (cached == NO_RANK) {
            if (System.currentTimeMillis() - prefs.getLong(key + "_time", 0L) < NEGATIVE_RANK_TTL_MS) return null
            prefs.edit().remove(key).remove(key + "_time").apply()
        }
        val mangas = linkedSetOf<String>()
        for (url in listOf("$BASE_URL/cards/$cardId", "$BASE_URL/cards/$cardId/users")) {
            if (session.cancelled) return null
            val html = requestHtml(session, url) ?: continue
            val doc = Jsoup.parse(html, url)
            rankForCardInDocument(doc, cardId)?.let { return rememberRank(cardId, it) }
            doc.select("a[href*='/manga/']").take(MAX_MANGA_CANDIDATES).forEach { MANGA_PATH_REGEX.find(it.attr("href"))?.groupValues?.getOrNull(1)?.let(mangas::add) }
            doc.selectFirst("[data-manga-id]")?.attr("data-manga-id")?.takeIf { it.isNotBlank() }?.let(mangas::add)
        }
        for (manga in mangas.take(MAX_MANGA_CANDIDATES)) {
            if (session.cancelled) return null
            rankFromMangaCatalog(session, manga, cardId)?.let { return rememberRank(cardId, it) }
            var url: String? = "$BASE_URL/manga/$manga/cards"
            val seen = hashSetOf<String>()
            var pages = 0
            while (url != null && pages < MAX_CATALOG_PAGES && seen.add(url) && !session.cancelled) {
                val html = requestHtml(session, url) ?: break
                val doc = Jsoup.parse(html, url)
                rankForCardInDocument(doc, cardId)?.let { return rememberRank(cardId, it) }
                val next = doc.selectFirst(".manga-cards[data-next-page-url]")?.attr("data-next-page-url").orEmpty()
                url = next.takeIf { it.isNotBlank() && it.startsWith("$BASE_URL/") }
                pages++
            }
        }
        if (!session.cancelled) prefs.edit().putString(key, NO_RANK).putLong(key + "_time", System.currentTimeMillis()).apply()
        return null
    }
    private fun rankFromMangaCatalog(session: TradeSession, mangaId: String, cardId: String): String? {
        var url = "$BASE_URL/manga/$mangaId/cards"
        val seen = hashSetOf<String>()
        var pages = 0
        while (pages < MAX_CATALOG_PAGES && seen.add(url) && !session.cancelled) {
            val body = FormBody.Builder().add("sort", "asc").build()
            val response = request(session, url, "POST", body, "$BASE_URL/manga/$mangaId") ?: return null
            if (response.code !in 200..299) return null
            val json = runCatching { JSONObject(response.body) }.getOrNull()
            val html = json?.optString("content")?.takeIf { it.isNotBlank() } ?: response.body
            val doc = Jsoup.parse(html, response.url)
            rankForCardInDocument(doc, cardId)?.let { return it }
            val next = json?.optString("next_page_url").orEmpty()
            url = when { next.startsWith("$BASE_URL/") -> next; next.startsWith("/") -> BASE_URL + next; else -> return null }
            pages++
        }
        return null
    }
    private fun rankForCardInDocument(doc: Document, cardId: String): String? =
        CatalogRankParser.findRank(doc, cardId)

    private fun rememberRank(cardId: String, rank: String): String {
        prefs.edit().putString(RANK_CACHE_PREFIX + cardId, rank).apply()
        return rank
    }

    private fun acceptTrade(session: TradeSession, detailUrl: String, tradeId: String): AcceptResult {
        if (session.cancelled || !isAccountAvailable(session.account.id)) return AcceptResult.CANCELLED
        if (session.csrfToken.isBlank()) refreshCsrf(session)
        if (session.cancelled) return AcceptResult.CANCELLED
        if (session.csrfToken.isBlank()) {
            addLog("Обмен #$tradeId: CSRF-токен не получен; запрос не отправлен.", true, session.account.id)
            return AcceptResult.REJECTED
        }
        val response = request(session, "$BASE_URL/trades/$tradeId/accept", "POST", ByteArray(0).toRequestBody(null),
            detailUrl, csrfHeaderOnly = true)
            ?: return if (session.cancelled) AcceptResult.CANCELLED else AcceptResult.UNKNOWN
        if (session.cancelled) return AcceptResult.CANCELLED
        if (response.code !in 200..299 || looksLikeLogin(response.body)) {
            addLog("Обмен #$tradeId: HTTP ${response.code}; повторный POST отключён.", true, session.account.id)
            return AcceptResult.REJECTED
        }
        val json = runCatching { JSONObject(response.body) }.getOrNull() ?: return AcceptResult.UNKNOWN
        val status = json.optString("trade_status").trim()
        return when {
            status.equals("accepted", true) -> AcceptResult.ACCEPTED
            status.isNotBlank() -> AcceptResult.REJECTED
            json.optString("message").contains("вы приняли обмен", true) -> AcceptResult.ACCEPTED
            else -> AcceptResult.UNKNOWN
        }
    }
    private fun refreshCsrf(session: TradeSession) {
        if (session.cancelled) return
        val html = requestHtml(session, BASE_URL) ?: return
        if (session.cancelled) return
        val token = Jsoup.parse(html, BASE_URL).selectFirst("meta[name=csrf-token]")?.attr("content")
        if (!token.isNullOrBlank()) session.csrfToken = token
    }
    private data class HttpResult(val code: Int, val url: String, val body: String)
    private fun requestHtml(session: TradeSession, url: String): String? =
        request(session, url, "GET", null, BASE_URL)?.takeIf { it.code in 200..399 }?.body
    private fun request(
        session: TradeSession,
        url: String,
        method: String,
        body: RequestBody?,
        referer: String,
        csrfHeaderOnly: Boolean = false
    ): HttpResult? = synchronized(requestQueueLock) {
        if (session.cancelled || !isAccountAvailable(session.account.id)) return@synchronized null
        try {
            // At least 500 ms between finished MangaBuff requests, like the plugin queue.
            val elapsed = System.currentTimeMillis() - lastRequestFinishedAt
            val waitMs = REQUEST_DELAY_MS - elapsed
            if (lastRequestFinishedAt > 0L && waitMs > 0L) {
                Thread.sleep(waitMs)
            }
            if (session.cancelled || !isAccountAvailable(session.account.id) || Thread.currentThread().isInterrupted) {
                return@synchronized null
            }

            val builder = Request.Builder()
                .url(url)
                .headers(baseHeaders(session, referer, csrfHeaderOnly))
            if (method == "POST") {
                builder.post(body ?: ByteArray(0).toRequestBody(null))
            } else {
                builder.get()
            }

            val call = session.client.newCall(builder.build())
            session.activeCall.set(call)
            try {
                // stop() can happen while waiting for a request slot.
                if (session.cancelled || !isAccountAvailable(session.account.id)) {
                    call.cancel()
                    return@synchronized null
                }
                call.execute().use { response ->
                    HttpResult(
                        response.code,
                        response.request.url.toString(),
                        response.body?.string().orEmpty()
                    )
                }
            } finally {
                session.activeCall.compareAndSet(call, null)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } finally {
            lastRequestFinishedAt = System.currentTimeMillis()
        }
    }

    private fun baseHeaders(session: TradeSession, referer: String, csrfHeaderOnly: Boolean): Headers {
        val account = session.account
        val b = Headers.Builder()
            .add("User-Agent", account.getSafeUserAgent())
            .add("Accept", if (csrfHeaderOnly) "*/*" else "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
            .add("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.7,en;q=0.6")
            .add("Origin", BASE_URL).add("Referer", referer).add("X-Requested-With", "XMLHttpRequest")
        account.getSafeCookiesJson().takeIf { it.isNotBlank() }?.let { b.add("Cookie", it) }
        session.csrfToken.takeIf { it.isNotBlank() }?.let {
            b.add("X-CSRF-TOKEN", it)
            if (!csrfHeaderOnly) b.add("X-XSRF-TOKEN", it)
        }
        return b.build()
    }
    private fun looksLikeLogin(docOrHtml: String): Boolean {
        val doc = Jsoup.parse(docOrHtml, BASE_URL)
        return doc.selectFirst("form[action*='/login'], input[name='email'], input[name='password']") != null ||
            doc.title().contains("Войти", ignoreCase = true) ||
            doc.location().contains("/login")
    }

    private fun isProcessed(accountId: String, tradeId: String): Boolean = synchronized(processedLock) {
        loadProcessedRecordsLocked(accountId, System.currentTimeMillis()).containsKey(tradeId)
    }

    private fun markProcessed(accountId: String, tradeId: String) = synchronized(processedLock) {
        val now = System.currentTimeMillis()
        val records = loadProcessedRecordsLocked(accountId, now)
        records[tradeId] = now
        storeProcessedRecordsLocked(accountId, records)
    }

    /** Durable per-account markers survive restarts and use a TTL instead of evicting by recency. */
    private fun loadProcessedRecordsLocked(accountId: String, now: Long): MutableMap<String, Long> {
        val key = scopedKey(KEY_PROCESSED_TTL, accountId)
        val stored = prefs.getStringSet(key, null)
        val cutoff = now - PROCESSED_TTL_MS
        val records = linkedMapOf<String, Long>()
        stored.orEmpty().forEach { item ->
            val separator = item.lastIndexOf('|')
            if (separator <= 0 || separator >= item.lastIndex) return@forEach
            val id = item.substring(0, separator)
            val timestamp = item.substring(separator + 1).toLongOrNull() ?: return@forEach
            if (id.isNotBlank() && timestamp in cutoff..now) records[id] = timestamp
        }

        var mustStore = stored != null && records.size != stored.size
        val legacyKey = scopedKey(KEY_PROCESSED, accountId)
        val legacy = prefs.getStringSet(legacyKey, null)
        if (legacy != null) {
            legacy.filter { it.isNotBlank() && it.all(Char::isDigit) }
                .forEach { id -> if (!records.containsKey(id)) records[id] = now }
            prefs.edit().remove(legacyKey).apply()
            mustStore = true
        }
        if (mustStore) storeProcessedRecordsLocked(accountId, records)
        return records
    }

    private fun storeProcessedRecordsLocked(accountId: String, records: Map<String, Long>) {
        // TTL is the eviction policy; do not evict recent safety markers just because
        // the number of attempted trades crossed an arbitrary limit.
        val durable = records.map { (id, timestamp) -> id + "|" + timestamp }.toSet()
        prefs.edit().putStringSet(scopedKey(KEY_PROCESSED_TTL, accountId), durable).apply()
    }

    companion object {
        @Volatile private var singleton: TradeAutomation? = null

        fun getInstance(context: Context): TradeAutomation =
            singleton ?: synchronized(this) {
                singleton ?: TradeAutomation(context.applicationContext).also { singleton = it }
            }

        private const val BASE_URL = "https://mangabuff.ru"
        private const val KEY_MODE = "rule_mode"
        private const val KEY_PROFIT = "profit_coefficient"
        private const val KEY_PROCESSED = "processed_trade_ids"
        private const val KEY_PROCESSED_TTL = "processed_trade_markers_v2"
        private const val KEY_AUTO_ACCEPT = "auto_accept_enabled"
        private const val KEY_SPECIAL_INSTANCE_WEIGHTS = "special_instance_weights"
        private const val RANK_CACHE_PREFIX = "catalog_rank_"
        private const val NO_RANK = "__unknown__"
        private const val POLL_INTERVAL_MS = 45_000L
        private const val REQUEST_DELAY_MS = 500L
        private const val NEGATIVE_RANK_TTL_MS = 30 * 60 * 1000L
        private const val MAX_CATALOG_PAGES = 30
        private const val MAX_MANGA_CANDIDATES = 12
        private const val MAX_TRADE_PAGES = 20
        private const val MAX_LOGS = 120
        private const val MAX_POLICY_TEXT_LENGTH = 8000
        private const val PROCESSED_TTL_MS = 90L * 24 * 60 * 60 * 1000
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        private val TRADE_PATH_REGEX = Regex("/trades/(\\d+)")
        private val CARD_PATH_REGEX = Regex("/cards/(\\d+)")
        private val MANGA_PATH_REGEX = Regex("/manga/([^/?#]+)")
        private val CARD_USER_ID_REGEX = Regex("(?:[?&])card_user_id=(\\d+)")
    }
}
