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
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
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

    private val _logs = MutableStateFlow<List<TradeLogEntry>>(emptyList())
    val logs: StateFlow<List<TradeLogEntry>> = _logs.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _activeAccountId = MutableStateFlow<String?>(null)
    val activeAccountId: StateFlow<String?> = _activeAccountId.asStateFlow()

    @Volatile private var worker: Job? = null
    @Volatile private var generation: Long = 0L
    private val skippedLogged = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    @Volatile private var activeAccount: MangaBuffAccount? = null
    @Volatile private var csrfToken: String = ""

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

    fun start(account: MangaBuffAccount, mode: TradeRuleMode, profitCoefficient: Double) {
        stop()
        if (account.getSafeCookiesJson().isBlank()) {
            addLog("У аккаунта нет сохранённых Cookie. Авторизуй аккаунт обычным способом.", true)
            return
        }
        activeAccount = account
        csrfToken = account.getSafeCsrfToken()
        _activeAccountId.value = account.id
        _isRunning.value = true
        saveMode(mode)
        saveProfitCoefficient(profitCoefficient.toString())
        addLog("Автообмен включён для аккаунта «${account.username}». Авторизация используется существующая.")
        val thisGeneration = synchronized(this) {
            generation += 1L
            generation
        }
        worker = scope.launch {
            try {
                refreshCsrf()
                while (isActive && generation == thisGeneration) {
                    scanPendingTrades(account, mode, profitCoefficient)
                    delay(POLL_INTERVAL_MS)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                addLog("Цикл автообмена остановлен: ${e.message ?: e.javaClass.simpleName}", true)
            } finally {
                if (generation == thisGeneration) {
                    _isRunning.value = false
                    _activeAccountId.value = null
                    activeAccount = null
                }
            }
        }
    }

    fun stop() {
        synchronized(this) { generation += 1L }
        worker?.cancel()
        worker = null
        _isRunning.value = false
        _activeAccountId.value = null
        activeAccount = null
    }

    fun close() {
        stop()
        scope.coroutineContext[Job]?.cancel()
    }

    private fun addLog(message: String, isError: Boolean = false) {
        _logs.update { old -> (old + TradeLogEntry(message = message, isError = isError)).takeLast(MAX_LOGS) }
    }

    private suspend fun scanPendingTrades(
        account: MangaBuffAccount,
        mode: TradeRuleMode,
        coefficient: Double
    ) {
        val page = requestHtml(BASE_URL + "/trades") ?: run {
            addLog("Не удалось загрузить список предложений. Проверю повторно.", true)
            return
        }
        if (looksLikeLogin(page)) {
            addLog("Сессия аккаунта не авторизована или истекла. Перезайди через существующее меню аккаунтов.", true)
            return
        }

        val offers = page.select("a.trade__list-item[href]")
            .mapNotNull { element ->
                val href = element.attr("href")
                val match = TRADE_PATH_REGEX.find(href) ?: return@mapNotNull null
                val id = match.groupValues[1]
                val name = element.selectFirst(".trade__list-name")?.text()?.trim().orEmpty()
                id to name
            }
            .distinctBy { it.first }

        if (offers.isEmpty()) {
            addLog("Новых предложений на странице не найдено.")
            return
        }

        for ((tradeId, sender) in offers) {
            if (!currentCoroutineContext().isActive) return
            if (isProcessed(tradeId)) continue
            val detailUrl = "$BASE_URL/trades/$tradeId"
            val detail = requestHtml(detailUrl)
            if (detail == null) {
                addLog("Обмен #$tradeId: не удалось загрузить детали; повторю позже.", true)
                continue
            }
            val doc = Jsoup.parse(detail, detailUrl)
            if (doc.selectFirst(".trade__accepted-btn") == null) {
                // Fail closed: a changed page layout must not silently mark a live offer as handled.
                if (skippedLogged.add(tradeId)) {
                    addLog("Обмен #$tradeId: кнопка принятия не найдена; автоматическое действие пропущено.")
                }
                continue
            }

            val analysis = analyzeTrade(doc, tradeId, mode, coefficient)
            if (!analysis.accept) {
                if (skippedLogged.add(tradeId)) {
                    addLog("Обмен #$tradeId ${sender.ifBlank { "" }}: пропуск — ${analysis.reason}")
                }
                continue
            }
            skippedLogged.remove(tradeId)

            addLog("Обмен #$tradeId: условия подходят (${analysis.receivedCount} карт получаем). Пробую принять.")
            val accepted = acceptTrade(doc, detailUrl, tradeId)
            if (accepted) {
                addLog("Обмен #$tradeId: принят.")
                markProcessed(tradeId)
            } else {
                addLog("Обмен #$tradeId: сайт не подтвердил принятие. Повторю проверку позже.", true)
            }
        }
    }

    private data class TradeCard(
        val catalogId: String,
        val instanceId: String?,
        val rank: String?,
        val lockState: LockState
    )

    private enum class LockState { LOCKED, UNLOCKED, UNKNOWN }

    private data class TradeAnalysis(
        val accept: Boolean,
        val reason: String,
        val receivedCount: Int = 0
    )

    private fun analyzeTrade(
        doc: Document,
        tradeId: String,
        mode: TradeRuleMode,
        coefficient: Double
    ): TradeAnalysis {
        if (doc.selectFirst(".trade__block, .card-show__owner-icon--trade-lock") != null) {
            return TradeAnalysis(false, "на странице отмечено ограничение обменов у собеседника")
        }
        var giveBlock: Element? = null
        var receiveBlock: Element? = null

        for (block in doc.select(".trade__main-items")) {
            val labels = buildString {
                block.previousElementSibling()?.let { append(" ").append(it.text()) }
                block.parent()?.selectFirst(".trade__main-title, .trade__main-label, h2, h3, h4")
                    ?.let { append(" ").append(it.text()) }
                block.parent()?.parent()?.selectFirst(".trade__main-title, .trade__main-label, h2, h3, h4")
                    ?.let { append(" ").append(it.text()) }
            }.lowercase()

            when {
                labels.contains("вы отдадите") -> giveBlock = block
                labels.contains("вам предлагают") || labels.contains("вы получите") -> receiveBlock = block
            }
        }

        // Do not infer sides if the page wording/layout has changed.
        if (giveBlock == null || receiveBlock == null) {
            return TradeAnalysis(false, "не удалось однозначно определить стороны обмена")
        }

        val giveElements = giveBlock!!.select("a.trade__main-item")
        val receiveElements = receiveBlock!!.select("a.trade__main-item")
        if (giveElements.size != 1) {
            return TradeAnalysis(false, "нужно отдавать ровно одну карту, найдено: ${giveElements.size}")
        }
        if (receiveElements.size < 2) {
            return TradeAnalysis(false, "получается меньше двух карт")
        }

        val offered = giveElements.map { parseTradeCard(it) }
        val received = receiveElements.map { parseTradeCard(it) }

        for (card in offered) {
            if (card.instanceId.isNullOrBlank()) {
                return TradeAnalysis(false, "не найден ID экземпляра отдаваемой карты")
            }
            if (card.lockState == LockState.LOCKED) {
                return TradeAnalysis(false, "отдаваемая карта заблокирована замком")
            }
            if (card.lockState == LockState.UNKNOWN) {
                return TradeAnalysis(false, "не удалось надёжно определить состояние замка карты")
            }
        }

        // Resolve catalog ranks first, without image/OCR. Unknown ranks fail closed.
        val giveRank = offered.single().rank
            ?: return TradeAnalysis(false, "не удалось получить ранг отдаваемой карты из каталога")
        if (giveRank == "X") return TradeAnalysis(false, "карты ранга X нельзя отдавать автоматически")
        if (giveRank !in STANDARD_RANKS) {
            return TradeAnalysis(false, "специальный ранг $giveRank пока не разрешён для автообмена")
        }

        val receivedRanks = received.map { it.rank
            ?: return TradeAnalysis(false, "не удалось получить ранг одной из получаемых карт из каталога") }
        val unknownSpecial = receivedRanks.firstOrNull { it !in STANDARD_RANKS }
        if (unknownSpecial != null) {
            return TradeAnalysis(false, "получаемая карта имеет специальный/неподдерживаемый ранг $unknownSpecial")
        }

        val givenValue = rankValue(giveRank)
        val receivedValues = receivedRanks.map(::rankValue)
        when (mode) {
            TradeRuleMode.STRICT -> {
                val lower = receivedRanks.firstOrNull { rankValue(it) < givenValue }
                if (lower != null) return TradeAnalysis(false, "строгий режим: получаемая карта ранга $lower ниже $giveRank")
            }
            TradeRuleMode.VALUE -> {
                val total = receivedValues.sum()
                val required = givenValue * coefficient
                if (total + 0.0001 < required) {
                    return TradeAnalysis(
                        false,
                        "сумма рангов $total ниже требуемой ${"%.2f".format(java.util.Locale.US, required)}"
                    )
                }
            }
        }
        return TradeAnalysis(true, "подходит", received.size)
    }

    private fun parseTradeCard(element: Element): TradeCard {
        val href = element.attr("href")
        val catalogId = element.attr("data-card-id").ifBlank {
            CARD_PATH_REGEX.find(href)?.groupValues?.getOrNull(1).orEmpty()
        }
        val lockIndicator = element.selectFirst(".lock-card-btn")
        val instanceId = lockIndicator?.attr("data-id")
            ?.takeIf { it.isNotBlank() }
            ?: element.selectFirst("[data-card-user-id]")?.attr("data-card-user-id")?.takeIf { it.isNotBlank() }

        val actionsLock = element.selectFirst(".card-actions__btn--lock")
        val icon = lockIndicator?.selectFirst("i")
        val lockState = when {
            actionsLock?.attr("aria-pressed") == "true" -> LockState.LOCKED
            actionsLock?.attr("aria-pressed") == "false" -> LockState.UNLOCKED
            actionsLock?.attr("title")?.contains("Разблокировать", ignoreCase = true) == true -> LockState.LOCKED
            actionsLock?.attr("title")?.contains("Заблокировать", ignoreCase = true) == true -> LockState.UNLOCKED
            icon?.classNames()?.contains("icon-lock") == true -> LockState.LOCKED
            icon?.classNames()?.contains("icon-unlock") == true -> LockState.UNLOCKED
            else -> LockState.UNKNOWN
        }
        val rank = if (catalogId.isNotBlank()) resolveCatalogRank(catalogId) else null
        return TradeCard(catalogId, instanceId, rank, lockState)
    }

    /**
     * First choice: the card's catalog HTML and its manga catalog entry,
     * where .manga-cards__item-wrapper exposes data-rank. No visual recognition.
     */
    private fun resolveCatalogRank(cardId: String): String? {
        val cacheKey = RANK_CACHE_PREFIX + cardId
        val cached = prefs.getString(cacheKey, null)
        if (cached != null && cached != NO_RANK) return cached
        if (cached == NO_RANK) {
            val timestamp = prefs.getLong(cacheKey + "_time", 0L)
            if (System.currentTimeMillis() - timestamp < NEGATIVE_RANK_TTL_MS) return null
            prefs.edit().remove(cacheKey).remove(cacheKey + "_time").apply()
        }

        var mangaId: String? = null
        val detailUrls = listOf("$BASE_URL/cards/$cardId", "$BASE_URL/cards/$cardId/users")
        for (url in detailUrls) {
            val html = requestHtml(url) ?: continue
            val doc = Jsoup.parse(html, url)
            val direct = rankForCardInDocument(doc, cardId)
            if (direct != null) return rememberRank(cardId, direct)

            val mangaHref = doc.select("a[href*='/manga/']").firstOrNull()?.attr("href").orEmpty()
            mangaId = MANGA_PATH_REGEX.find(mangaHref)?.groupValues?.getOrNull(1)
                ?: doc.selectFirst("[data-manga-id]")?.attr("data-manga-id")
            if (mangaId != null) break
        }

        if (mangaId != null) {
            val ajaxRank = rankFromMangaCatalog(mangaId, cardId)
            if (ajaxRank != null) return rememberRank(cardId, ajaxRank)

            var url: String? = "$BASE_URL/manga/$mangaId/cards"
            var pages = 0
            val visited = HashSet<String>()
            while (url != null && pages < MAX_CATALOG_PAGES && visited.add(url)) {
                val html = requestHtml(url) ?: break
                val doc = Jsoup.parse(html, url)
                val rank = rankForCardInDocument(doc, cardId)
                if (rank != null) return rememberRank(cardId, rank)

                val next = doc.selectFirst(".manga-cards[data-next-page-url]")
                    ?.attr("data-next-page-url").orEmpty()
                url = next.takeIf { it.isNotBlank() && it.startsWith(BASE_URL) }
                pages++
            }
        }

        // A negative cache has a short TTL, so a new site layout can recover after restart.
        prefs.edit().putString(RANK_CACHE_PREFIX + cardId, NO_RANK)
            .putLong(RANK_CACHE_PREFIX + cardId + "_time", System.currentTimeMillis()).apply()
        return null
    }

    /**
     * MangaBuff's card catalogue is loaded through POST /manga/{id}/cards
     * with sort=asc. Its JSON response contains the catalogue HTML and
     * next_page_url; inspect data-rank on the matching catalogue wrapper.
     */
    private fun rankFromMangaCatalog(mangaId: String, cardId: String): String? {
        var url = "$BASE_URL/manga/$mangaId/cards"
        val visited = HashSet<String>()
        var pages = 0
        while (pages < MAX_CATALOG_PAGES && visited.add(url)) {
            val body = FormBody.Builder().add("sort", "asc").build()
            val response = request(url, "POST", body, "$BASE_URL/manga/$mangaId") ?: return null
            if (response.code !in 200..299) return null
            val json = runCatching { JSONObject(response.body) }.getOrNull()
            val html = json?.optString("content")?.takeIf { it.isNotBlank() } ?: response.body
            val doc = Jsoup.parse(html, response.url)
            val rank = rankForCardInDocument(doc, cardId)
            if (rank != null) return rank

            val next = json?.optString("next_page_url").orEmpty()
            url = when {
                next.startsWith("$BASE_URL/") -> next
                next.startsWith("/") -> BASE_URL + next
                else -> return null
            }
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
        rank.takeIf { it in STANDARD_RANKS || it in SPECIAL_RANKS }

    private fun rememberRank(cardId: String, rank: String): String {
        prefs.edit().putString(RANK_CACHE_PREFIX + cardId, rank).apply()
        return rank
    }

    private fun acceptTrade(doc: Document, detailUrl: String, tradeId: String): Boolean {
        val acceptButton = doc.selectFirst(".trade__accepted-btn") ?: return false
        var form: Element? = acceptButton
        while (form != null && form.tagName() != "form") form = form.parent()
        val actionAttr = form?.attr("action").orEmpty()
        val buttonAction = acceptButton.attr("formaction").ifBlank { acceptButton.attr("data-url") }
        val action = when {
            actionAttr.isNotBlank() -> actionAttr
            buttonAction.startsWith("/") -> buttonAction
            else -> "/trades/$tradeId/accept"
        }
        val url = if (action.startsWith("http")) action else BASE_URL + if (action.startsWith("/")) action else "/$action"
        if (!url.startsWith(BASE_URL + "/")) {
            addLog("Обмен #$tradeId: отклонён небезопасный URL подтверждения.", true)
            return false
        }

        val bodyBuilder = FormBody.Builder()
        val included = HashSet<String>()
        form?.select("input[name]")?.forEach { input ->
            val name = input.attr("name")
            if (name.isNotBlank()) {
                bodyBuilder.add(name, input.attr("value"))
                included.add(name)
            }
        }
        if (!included.contains("_token") && csrfToken.isNotBlank()) bodyBuilder.add("_token", csrfToken)
        if (acceptButton.hasAttr("name")) {
            bodyBuilder.add(acceptButton.attr("name"), acceptButton.attr("value"))
        }

        var result = request(url, "POST", bodyBuilder.build(), detailUrl)
        if (result?.code == 419 || result?.code == 403) {
            refreshCsrf()
            val retryBuilder = FormBody.Builder()
            form?.select("input[name]")?.forEach { input ->
                val name = input.attr("name")
                if (name.isNotBlank()) retryBuilder.add(name, input.attr("value"))
            }
            if (form?.selectFirst("input[name=_token]") == null && csrfToken.isNotBlank()) {
                retryBuilder.add("_token", csrfToken)
            }
            if (acceptButton.hasAttr("name")) retryBuilder.add(acceptButton.attr("name"), acceptButton.attr("value"))
            result = request(url, "POST", retryBuilder.build(), detailUrl)
        }

        val response = result ?: return false
        if (response.code !in 200..399 || looksLikeLogin(response.body)) return false
        val responseDoc = Jsoup.parse(response.body, response.url)
        val bodyText = responseDoc.text().lowercase()
        val stillHasAcceptButton = responseDoc.selectFirst(".trade__accepted-btn") != null
        val explicitSuccess = listOf("обмен принят", "обмен успешно принят", "успешно принят", "trade accepted")
            .any { bodyText.contains(it) } ||
            response.body.contains("\"success\":true", ignoreCase = true)
        return explicitSuccess || (!stillHasAcceptButton && !response.url.contains("/login"))
    }

    private fun refreshCsrf() {
        val html = requestHtml(BASE_URL) ?: return
        val token = Jsoup.parse(html, BASE_URL).selectFirst("meta[name=csrf-token]")?.attr("content")
        if (!token.isNullOrBlank()) csrfToken = token
    }

    private data class HttpResult(val code: Int, val url: String, val body: String)

    private fun requestHtml(url: String): String? =
        request(url, "GET", null, BASE_URL)?.takeIf { it.code in 200..399 }?.body

    private fun request(
        url: String,
        method: String,
        body: RequestBody?,
        referer: String
    ): HttpResult? {
        return try {
            val builder = Request.Builder().url(url).headers(baseHeaders(referer))
            when (method) {
                "POST" -> builder.post(body ?: FormBody.Builder().build())
                else -> builder.get()
            }
            client.newCall(builder.build()).execute().use { response ->
                HttpResult(response.code, response.request.url.toString(), response.body?.string().orEmpty())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun baseHeaders(referer: String): Headers {
        val account = activeAccount
        val builder = Headers.Builder()
            .add("User-Agent", account?.getSafeUserAgent() ?: DEFAULT_USER_AGENT)
            .add("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
            .add("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.7,en;q=0.6")
            .add("Origin", BASE_URL)
            .add("Referer", referer)
            .add("X-Requested-With", "XMLHttpRequest")
        account?.getSafeCookiesJson()?.takeIf { it.isNotBlank() }?.let { builder.add("Cookie", it) }
        csrfToken.takeIf { it.isNotBlank() }?.let {
            builder.add("X-CSRF-TOKEN", it)
            builder.add("X-XSRF-TOKEN", it)
        }
        return builder.build()
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
        private const val RANK_CACHE_PREFIX = "catalog_rank_"
        private const val NO_RANK = "__unknown__"
        private const val POLL_INTERVAL_MS = 45_000L
        private const val NEGATIVE_RANK_TTL_MS = 30 * 60 * 1000L
        private const val MAX_CATALOG_PAGES = 30
        private const val MAX_LOGS = 120
        private const val MAX_PROCESSED_IDS = 400
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        private val STANDARD_RANKS = setOf("E", "D", "C", "B", "G", "P", "A", "S")
        private val SPECIAL_RANKS = setOf("H", "N", "V", "Q", "L", "K", "W", "X")
        private val RANK_ORDER = listOf("E", "D", "C", "B", "G", "P", "A", "S")
        private val TRADE_PATH_REGEX = Regex("/trades/(\\d+)")
        private val CARD_PATH_REGEX = Regex("/cards/(\\d+)")
        private val MANGA_PATH_REGEX = Regex("/manga/(\\d+)")
        private fun rankValue(rank: String): Int = RANK_ORDER.indexOf(rank).let { if (it < 0) 0 else it + 1 }
    }
}
