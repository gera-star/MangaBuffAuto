package com.example.myapplication.automation

import android.content.Context
import com.example.myapplication.data.MangaBuffAccount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    val isError: Boolean = false
)

/**
 * Isolated trade worker. It uses the selected account's existing Cookie,
 * CSRF token and User-Agent; it does not create or change app account sessions.
 */
class TradeAutomation(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("mangabuff_trade_mode", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // Mirrors the browser plugin's serialized MangaBuff queue. The whole request
    // is inside the lock so quick consecutive catalogue lookups cannot overlap.
    private val requestQueueLock = Any()
    @Volatile private var lastRequestFinishedAt = 0L

    private val _logs = MutableStateFlow<List<TradeLogEntry>>(emptyList())
    val logs: StateFlow<List<TradeLogEntry>> = _logs.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

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
        fun cancel() { cancelled = true; activeCall.get()?.cancel() }
    }

    fun savedMode(): TradeRuleMode =
        runCatching { TradeRuleMode.valueOf(prefs.getString(KEY_MODE, TradeRuleMode.STRICT.name)!!) }
            .getOrDefault(TradeRuleMode.STRICT)

    fun saveMode(mode: TradeRuleMode) {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
    }

    fun savedProfitCoefficient(): String =
        prefs.getString(KEY_PROFIT, "1.5") ?: "1.5"

    fun saveProfitCoefficient(value: String) {
        prefs.edit().putString(KEY_PROFIT, value).apply()
    }

    fun savedAutoAcceptEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_ACCEPT, false)
    fun setAutoAcceptEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_ACCEPT, enabled).apply()
        activeSession?.let { it.autoAccept = enabled }
        addLog(if (enabled) "Автопринятие включено пользователем." else "Автопринятие выключено: новые предложения только анализируются.")
    }
    fun savedProtectedCardIds(): String = prefs.getString(KEY_PROTECTED_CARD_IDS, "") ?: ""
    fun saveProtectedCardIds(value: String) { prefs.edit().putString(KEY_PROTECTED_CARD_IDS, value.take(MAX_POLICY_TEXT_LENGTH)).apply() }
    fun savedSpecialInstanceWeights(): String = prefs.getString(KEY_SPECIAL_INSTANCE_WEIGHTS, "") ?: ""
    fun saveSpecialInstanceWeights(value: String) { prefs.edit().putString(KEY_SPECIAL_INSTANCE_WEIGHTS, value.take(MAX_POLICY_TEXT_LENGTH)).apply() }
    private fun protectedCardIds(): Set<String> =
        savedProtectedCardIds().split(',', ';', '\n', '\r', ' ', '\t').map(String::trim)
            .filter { it.isNotEmpty() && it.all(Char::isDigit) }.toSet()
    private fun specialInstanceWeights(): Map<String, Int> {
        val result = linkedMapOf<String, Int>()
        savedSpecialInstanceWeights().lineSequence().map(String::trim).filter(String::isNotEmpty).forEach { line ->
            val sep = line.indexOf('=')
            if (sep <= 0) return@forEach
            val id = line.substring(0, sep).trim()
            val weight = line.substring(sep + 1).trim().toIntOrNull()
            if (id.isNotBlank() && id.all(Char::isDigit) && weight != null && weight in 1..8) result[id] = weight
        }
        return result
    }
    fun start(account: MangaBuffAccount, mode: TradeRuleMode, profitCoefficient: Double) {
        stop()
        if (account.getSafeCookiesJson().isBlank()) {
            addLog("У аккаунта нет сохранённых Cookie. Авторизуй аккаунт обычным способом.", true)
            return
        }
        val coefficient = if (profitCoefficient.isFinite()) profitCoefficient.coerceIn(1.0, 10.0) else 1.5
        val session = TradeSession(account).apply { autoAccept = savedAutoAcceptEnabled() }
        activeSession = session
        _activeAccountId.value = account.id
        _isRunning.value = true
        saveMode(mode)
        saveProfitCoefficient(coefficient.toString())
        addLog("Мониторинг запущен для «${account.username}». " +
            if (session.autoAccept) "Автопринятие включено." else "Режим анализа — запрос принятия не отправляется.")
        val runId = synchronized(this) { generation += 1L; generation }
        worker = scope.launch {
            try {
                refreshCsrf(session)
                while (isActive && canRun(session, runId)) {
                    scanPendingTrades(session, mode, coefficient, runId)
                    if (canRun(session, runId)) delay(POLL_INTERVAL_MS)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!session.cancelled) addLog("Цикл автообмена остановлен: ${e.message ?: e.javaClass.simpleName}", true)
            } finally {
                if (generation == runId) {
                    _isRunning.value = false
                    _activeAccountId.value = null
                    if (activeSession === session) activeSession = null
                }
            }
        }
    }
    private fun canRun(session: TradeSession, runId: Long) =
        !session.cancelled && activeSession === session && generation == runId
    fun stop() {
        synchronized(this) { generation += 1L }
        val old = activeSession
        activeSession = null
        old?.cancel()
        worker?.cancel()
        worker = null
        _isRunning.value = false
        _activeAccountId.value = null
    }
    fun close() { stop(); scope.coroutineContext[Job]?.cancel() }
    private fun addLog(message: String, isError: Boolean = false) {
        _logs.update { old -> (old + TradeLogEntry(message = message, isError = isError)).takeLast(MAX_LOGS) }
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
                if (canRun(session, expectedGeneration)) addLog("Не удалось загрузить список предложений (страница $page). Проверю позже.", true)
                break
            }
            if (looksLikeLogin(html)) {
                addLog("Сессия «${session.account.username}» не авторизована или истекла. Проверь её в обычном меню.", true)
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
        if (offers.isEmpty()) { addLog("Новых предложений на странице не найдено."); return }
        for ((tradeId, sender) in offers) {
            if (!canRun(session, expectedGeneration) || !currentCoroutineContext().isActive) return
            if (isProcessed(tradeId)) continue
            val detailUrl = "$BASE_URL/trades/$tradeId"
            val html = requestHtml(session, detailUrl)
            if (html == null) {
                if (canRun(session, expectedGeneration)) addLog("Обмен #$tradeId: не удалось загрузить детали; проверю позже.", true)
                continue
            }
            val doc = Jsoup.parse(html, detailUrl)
            if (doc.selectFirst(".trade__accepted-btn") == null) {
                if (skippedLogged.add(tradeId)) addLog("Обмен #$tradeId: кнопка принятия не найдена; действие пропущено.")
                continue
            }
            val result = analyzeTrade(session, doc, tradeId, mode, coefficient)
            if (!canRun(session, expectedGeneration) || !currentCoroutineContext().isActive) return
            if (!result.accept) {
                if (skippedLogged.add(tradeId)) addLog("Обмен #$tradeId ${sender}: пропуск — ${result.reason}")
                suitableLogged.remove(tradeId)
                continue
            }
            skippedLogged.remove(tradeId)
            if (!session.autoAccept) {
                if (suitableLogged.add(tradeId)) addLog("Обмен #$tradeId ${sender}: подходит, получаешь ${result.receivedCount} карты. Только анализ, запрос не отправлялся.")
                continue
            }
            suitableLogged.remove(tradeId)
            if (!canRun(session, expectedGeneration) || !currentCoroutineContext().isActive) return
            if (session.csrfToken.isBlank()) refreshCsrf(session)
            if (session.cancelled || !canRun(session, expectedGeneration)) return
            if (session.csrfToken.isBlank()) {
                if (suitableLogged.add(tradeId)) addLog("Обмен #$tradeId: CSRF-токен отсутствует, принятие не отправлено.", true)
                continue
            }
            markProcessed(tradeId) // avoid replay if response is lost
            addLog("Обмен #$tradeId: условия подходят. Отправляю одну попытку принятия.")
            when (acceptTrade(session, detailUrl, tradeId)) {
                AcceptResult.ACCEPTED -> addLog("Обмен #$tradeId: сервер подтвердил принятие.")
                AcceptResult.REJECTED -> addLog("Обмен #$tradeId: сервер отклонил запрос. Повторного POST не будет; проверь вручную.", true)
                AcceptResult.UNKNOWN -> addLog("Обмен #$tradeId: ответ не распознан. Повторный POST запрещён; проверь статус вручную.", true)
                AcceptResult.CANCELLED -> addLog("Обмен #$tradeId: запрос прерван остановкой; проверь статус вручную.", true)
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
        if (doc.selectFirst(".card-show__owner-icon--trade-lock") != null) return TradeAnalysis(false, "у пользователя найден признак запрета обменов")
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
            val protected = protectedCardIds()
            if (c.catalogId in protected || c.instanceId?.let(protected::contains) == true) return TradeAnalysis(false, "ID карты присутствует в пользовательском списке защиты")
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
            receives, mode, coefficient, protectedCardIds(), specialInstanceWeights())
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
    private fun rankForCardInDocument(doc: Document, cardId: String): String? {
        val target = doc.selectFirst("[data-card-id='$cardId']")
        var node: Element? = target
        repeat(8) {
            val rank = node?.attr("data-rank")?.trim()?.uppercase()
            if (!rank.isNullOrBlank()) return knownRank(rank)
            node = node?.parent()
        }

        // Some card detail templates show the catalog rank in a dedicated badge.
        val badge = doc.selectFirst(".card-show__rank, .card-rank, .card-show__rank-name")
        return badge?.text()?.trim()?.uppercase()?.let(::knownRank)
    }

    private fun knownRank(rank: String): String? =
        rank.takeIf { it in TradePolicy.STANDARD_RANKS || it in TradePolicy.SPECIAL_RANKS }

    private fun rememberRank(cardId: String, rank: String): String {
        prefs.edit().putString(RANK_CACHE_PREFIX + cardId, rank).apply()
        return rank
    }

    private fun acceptTrade(session: TradeSession, detailUrl: String, tradeId: String): AcceptResult {
        if (session.cancelled) return AcceptResult.CANCELLED
        if (session.csrfToken.isBlank()) refreshCsrf(session)
        if (session.cancelled) return AcceptResult.CANCELLED
        if (session.csrfToken.isBlank()) {
            addLog("Обмен #$tradeId: CSRF-токен не получен; запрос не отправлен.", true)
            return AcceptResult.REJECTED
        }
        val response = request(session, "$BASE_URL/trades/$tradeId/accept", "POST", ByteArray(0).toRequestBody(null),
            detailUrl, csrfHeaderOnly = true)
            ?: return if (session.cancelled) AcceptResult.CANCELLED else AcceptResult.UNKNOWN
        if (session.cancelled) return AcceptResult.CANCELLED
        if (response.code !in 200..299 || looksLikeLogin(response.body)) {
            addLog("Обмен #$tradeId: HTTP ${response.code}; повторный POST отключён.", true)
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
        if (session.cancelled) return@synchronized null
        try {
            // At least 500 ms between finished MangaBuff requests, like the plugin queue.
            val elapsed = System.currentTimeMillis() - lastRequestFinishedAt
            val waitMs = REQUEST_DELAY_MS - elapsed
            if (lastRequestFinishedAt > 0L && waitMs > 0L) {
                Thread.sleep(waitMs)
            }
            if (session.cancelled || Thread.currentThread().isInterrupted) {
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

            val call = client.newCall(builder.build())
            session.activeCall.set(call)
            try {
                // stop() can happen while waiting for a request slot.
                if (session.cancelled) {
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

    private fun isProcessed(tradeId: String): Boolean =
        prefs.getStringSet(KEY_PROCESSED, emptySet())?.contains(tradeId) == true

    private fun markProcessed(tradeId: String) {
        val copy = prefs.getStringSet(KEY_PROCESSED, emptySet())?.toMutableSet() ?: mutableSetOf()
        copy.add(tradeId)
        // Keep a bounded set so the preference doesn't grow indefinitely.
        val bounded = copy.toList().takeLast(MAX_PROCESSED_IDS).toSet()
        prefs.edit().putStringSet(KEY_PROCESSED, bounded).apply()
    }

    companion object {
        private const val BASE_URL = "https://mangabuff.ru"
        private const val KEY_MODE = "rule_mode"
        private const val KEY_PROFIT = "profit_coefficient"
        private const val KEY_PROCESSED = "processed_trade_ids"
        private const val KEY_AUTO_ACCEPT = "auto_accept_enabled"
        private const val KEY_PROTECTED_CARD_IDS = "protected_card_ids"
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
        private const val MAX_PROCESSED_IDS = 400
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        private val TRADE_PATH_REGEX = Regex("/trades/(\\d+)")
        private val CARD_PATH_REGEX = Regex("/cards/(\\d+)")
        private val MANGA_PATH_REGEX = Regex("/manga/([^/?#]+)")
        private val CARD_USER_ID_REGEX = Regex("(?:[?&])card_user_id=(\\d+)")
    }
}
