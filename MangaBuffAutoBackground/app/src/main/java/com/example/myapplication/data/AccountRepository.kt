package com.example.myapplication.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.reflect.TypeToken

class AccountRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("mangabuff_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private val ACCOUNTS_LOCK = Any()
        private const val KEY_ACCOUNTS = "key_accounts"
        private const val KEY_SETTINGS = "key_settings"
        private const val KEY_MINE_AUTO_EXCHANGE_MIGRATED = "key_mine_auto_exchange_manual_migrated"
        private const val KEY_TASK_DEFAULTS_MIGRATED = "key_task_defaults_20261019_migrated"
        private const val KEY_DECK_COMMENTS_DEFAULTS_MIGRATED = "key_deck_comments_default_on_migrated"
    }

    fun getAccounts(): List<MangaBuffAccount> = synchronized(ACCOUNTS_LOCK) {
        readAccountsUnsafe()
    }

    private fun readAccountsUnsafe(): List<MangaBuffAccount> {
        val json = prefs.getString(KEY_ACCOUNTS, null) ?: run {
            // No existing accounts to migrate; new accounts already use the current defaults.
            prefs.edit().putBoolean(KEY_DECK_COMMENTS_DEFAULTS_MIGRATED, true).apply()
            return emptyList()
        }
        val type = object : TypeToken<List<MangaBuffAccount>>() {}.type
        return try {
            // Migrate the account's previous quiz setting to the new Tower setting.
            val root = JsonParser.parseString(json)
            val migrateDeckCommentsOn = !prefs.getBoolean(KEY_DECK_COMMENTS_DEFAULTS_MIGRATED, false)
            val normalizedJson = if (root.isJsonArray) {
                var changed = false
                root.asJsonArray.forEach { element ->
                    if (element.isJsonObject) {
                        val accountJson = element.asJsonObject
                        if (!accountJson.has("towerEnabled")) {
                            accountJson.add("towerEnabled", accountJson.get("quizEnabled") ?: JsonPrimitive(false))
                            changed = true
                        }
                        if (accountJson.has("quizEnabled")) {
                            accountJson.remove("quizEnabled")
                            changed = true
                        }
                        if (!accountJson.has("deckCommentEnabled") ||
                            (migrateDeckCommentsOn && !accountJson.get("deckCommentEnabled").isJsonNull &&
                                !accountJson.get("deckCommentEnabled").asBoolean)
                        ) {
                            accountJson.add("deckCommentEnabled", JsonPrimitive(true))
                            changed = true
                        }
                    }
                }
                if (changed) prefs.edit().putString(KEY_ACCOUNTS, root.toString()).apply()
                root.toString()
            } else {
                json
            }
            if (migrateDeckCommentsOn) {
                prefs.edit().putBoolean(KEY_DECK_COMMENTS_DEFAULTS_MIGRATED, true).apply()
            }
            val list: List<MangaBuffAccount>? = gson.fromJson(normalizedJson, type)
            list?.map { acc ->
                val rawStatus = acc.getSafeStatusMessage()
                val cleanStatus = if (acc.isRunning || rawStatus.contains("Инициализация") || rawStatus.contains("Башня") || rawStatus.contains("Реклама") || rawStatus.contains("Майнинг") || rawStatus.contains("Чтение") || rawStatus.contains("Комментарий")) {
                    "Готов"
                } else {
                    rawStatus
                }
                val day = currentStatsDay()
                val statsFresh = acc.dailyStatsDay == day
                acc.copy(
                    dailyStatsDay = if (statsFresh) acc.dailyStatsDay else day,
                    dailyBattles = if (statsFresh) acc.dailyBattles else 0,
                    dailyBattleAttempts = if (statsFresh) acc.dailyBattleAttempts else 0,
                    dailyQuiz = if (statsFresh) acc.dailyQuiz else 0,
                    dailyAds = if (statsFresh) acc.dailyAds else 0,
                    dailyMineOre = if (statsFresh) acc.dailyMineOre else 0,
                    dailyMineExchangeOre = if (statsFresh) acc.dailyMineExchangeOre else 0,
                    dailyMineDiamonds = if (statsFresh) acc.dailyMineDiamonds else 0,
                    dailyReaderChapters = if (statsFresh) acc.dailyReaderChapters else 0,
                    dailyComments = if (statsFresh) acc.dailyComments else 0,
                    dailyDeckComments = if (statsFresh) acc.dailyDeckComments else 0,
                    isRunning = false, // При перезапуске приложения статус выполнения всегда сбрасывается в false
                    taskProgress = 0f,
                    statusMessage = cleanStatus,
                    cookiesJson = acc.getSafeCookiesJson(),
                    csrfToken = acc.getSafeCsrfToken(),
                    userAgent = acc.getSafeUserAgent(),
                    currentTask = acc.getSafeCurrentTask(),
                    activeMangaUrl = acc.getSafeActiveMangaUrl(),
                    activeMangaTitle = acc.getSafeActiveMangaTitle()
                )
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveAccounts(accounts: List<MangaBuffAccount>) = synchronized(ACCOUNTS_LOCK) {
        writeAccountsUnsafe(accounts)
    }

    fun saveAccount(account: MangaBuffAccount) = synchronized(ACCOUNTS_LOCK) {
        val list = readAccountsUnsafe().toMutableList()
        val index = list.indexOfFirst { it.id == account.id }
        if (index >= 0) {
            list[index] = account
        } else {
            list.add(account)
        }
        writeAccountsUnsafe(list)
    }

    fun updateAccount(
        accountId: String,
        transform: (MangaBuffAccount) -> MangaBuffAccount
    ): MangaBuffAccount? = synchronized(ACCOUNTS_LOCK) {
        val list = readAccountsUnsafe().toMutableList()
        val index = list.indexOfFirst { it.id == accountId }
        if (index < 0) return@synchronized null

        val updated = transform(list[index])
        list[index] = updated
        writeAccountsUnsafe(list)
        updated
    }

    fun deleteAccount(accountId: String) = synchronized(ACCOUNTS_LOCK) {
        val list = readAccountsUnsafe().filterNot { it.id == accountId }
        writeAccountsUnsafe(list)
    }

    private fun writeAccountsUnsafe(accounts: List<MangaBuffAccount>) {
        // При сохранении в SharedPreferences флаги выполнения всегда сохраняем как false
        val sanitized = accounts.map { acc ->
            acc.copy(isRunning = false, taskProgress = 0f)
        }
        val json = gson.toJson(sanitized)
        prefs.edit().putString(KEY_ACCOUNTS, json).apply()
    }

    fun getSettings(): GlobalSettings {
        val json = prefs.getString(KEY_SETTINGS, null) ?: run {
            // The model defaults are authoritative for a fresh installation.
            prefs.edit().putBoolean(KEY_TASK_DEFAULTS_MIGRATED, true).apply()
            return GlobalSettings()
        }
        return try {
            val parsed = gson.fromJson(json, GlobalSettings::class.java) ?: GlobalSettings()
            var migrated = parsed
            var shouldSave = false
            val editor = prefs.edit()

            val safeReaderEngineMode = ReaderEngineModes.normalize(migrated.readerEngineMode)
            if (safeReaderEngineMode != migrated.readerEngineMode) {
                migrated = migrated.copy(readerEngineMode = safeReaderEngineMode)
                shouldSave = true
            }

            // Older Gson payloads can leave new enum properties null because constructors are bypassed.
            val safeNetworkResetMode = runCatching {
                NetworkResetMode.valueOf(migrated.networkResetMode.name)
            }.getOrDefault(NetworkResetMode.NONE)
            if (safeNetworkResetMode != migrated.networkResetMode) {
                migrated = migrated.copy(networkResetMode = safeNetworkResetMode)
                shouldSave = true
            }

            if (!prefs.getBoolean(KEY_MINE_AUTO_EXCHANGE_MIGRATED, false)) {
                migrated = migrated.copy(mineAutoExchange = false)
                editor.putBoolean(KEY_MINE_AUTO_EXCHANGE_MIGRATED, true)
                shouldSave = true
            }

            if (!prefs.getBoolean(KEY_TASK_DEFAULTS_MIGRATED, false)) {
                // Move only values that matched the previous factory defaults; retain custom counts.
                migrated = migrated.copy(
                    battleTargetCount = if (migrated.battleTargetCount == 20 || migrated.battleTargetCount <= 0) {
                        25
                    } else migrated.battleTargetCount,
                    // Preserve the established default of 10 and any user-selected target.
                    // Only repair invalid stored values; do not silently rewrite 10 to 45.
                    readerChapters = if (migrated.readerChapters <= 0) {
                        10
                    } else migrated.readerChapters,
                    readerSiteAutoscroll = false,
                    eventsEnabled = true
                )
                editor.putBoolean(KEY_TASK_DEFAULTS_MIGRATED, true)
                shouldSave = true
            }

            if (shouldSave) {
                editor.putString(KEY_SETTINGS, gson.toJson(migrated)).apply()
            }
            migrated
        } catch (e: Exception) {
            GlobalSettings()
        }
    }

    fun saveSettings(settings: GlobalSettings) {
        val json = gson.toJson(settings.copy(
            readerEngineMode = ReaderEngineModes.normalize(settings.readerEngineMode)
        ))
        prefs.edit()
            .putString(KEY_SETTINGS, json)
            .putBoolean(KEY_MINE_AUTO_EXCHANGE_MIGRATED, true)
            .apply()
    }
}
