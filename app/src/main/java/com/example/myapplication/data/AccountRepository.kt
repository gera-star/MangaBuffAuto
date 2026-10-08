package com.example.myapplication.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class AccountRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("mangabuff_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val KEY_ACCOUNTS = "key_accounts"
        private const val KEY_SETTINGS = "key_settings"
        private const val KEY_MINE_AUTO_EXCHANGE_MIGRATED = "key_mine_auto_exchange_manual_migrated"
        private const val KEY_AUTOMATION_SESSION_SETTINGS_MIGRATED = "key_automation_session_settings_migrated"
    }

    private val accountsLock = Any()

    fun getAccounts(): List<MangaBuffAccount> = synchronized(accountsLock) {
        val json = prefs.getString(KEY_ACCOUNTS, null) ?: return emptyList()
        val type = object : TypeToken<List<MangaBuffAccount>>() {}.type
        return try {
            val list: List<MangaBuffAccount>? = gson.fromJson(json, type)
            list?.map { acc ->
                val rawStatus = acc.getSafeStatusMessage()
                val cleanStatus = if (acc.isRunning || rawStatus.contains("Инициализация") || rawStatus.contains("Квиз") || rawStatus.contains("Реклама") || rawStatus.contains("Майнинг") || rawStatus.contains("Чтение") || rawStatus.contains("Комментарий")) {
                    "Готов"
                } else {
                    rawStatus
                }
                val day = currentStatsDay()
                val statsFresh = acc.dailyStatsDay == day
                acc.copy(
                    dailyStatsDay = if (statsFresh) acc.dailyStatsDay else day,
                    dailyBattles = if (statsFresh) acc.dailyBattles else 0,
                    dailyQuiz = if (statsFresh) acc.dailyQuiz else 0,
                    dailyAds = if (statsFresh) acc.dailyAds else 0,
                    dailyMineOre = if (statsFresh) acc.dailyMineOre else 0,
                    dailyMineExchangeOre = if (statsFresh) acc.dailyMineExchangeOre else 0,
                    dailyMineDiamonds = if (statsFresh) acc.dailyMineDiamonds else 0,
                    dailyReaderChapters = if (statsFresh) acc.dailyReaderChapters else 0,
                    dailyComments = if (statsFresh) acc.dailyComments else 0,
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

    fun saveAccounts(accounts: List<MangaBuffAccount>) = synchronized(accountsLock) {
        // При сохранении в SharedPreferences флаги выполнения всегда сохраняем как false
        val sanitized = accounts.map { acc ->
            acc.copy(isRunning = false, taskProgress = 0f)
        }
        val json = gson.toJson(sanitized)
        prefs.edit().putString(KEY_ACCOUNTS, json).apply()
    }

    fun saveAccount(account: MangaBuffAccount) = synchronized(accountsLock) {
        val list = getAccounts().toMutableList()
        val index = list.indexOfFirst { it.id == account.id }
        if (index >= 0) {
            list[index] = account
        } else {
            list.add(account)
        }
        saveAccounts(list)
    }

    fun updateAccount(
        accountId: String,
        transform: (MangaBuffAccount) -> MangaBuffAccount
    ): MangaBuffAccount? = synchronized(accountsLock) {
        val list = getAccounts().toMutableList()
        val index = list.indexOfFirst { it.id == accountId }
        if (index < 0) return null

        val updated = transform(list[index])
        list[index] = updated
        saveAccounts(list)
        updated
    }

    fun deleteAccount(accountId: String) = synchronized(accountsLock) {
        val list = getAccounts().filterNot { it.id == accountId }
        saveAccounts(list)
    }

    fun getSettings(): GlobalSettings {
        val json = prefs.getString(KEY_SETTINGS, null) ?: return GlobalSettings()
        return try {
            val parsed = gson.fromJson(json, GlobalSettings::class.java) ?: GlobalSettings()

            val needsAutomationMigration =
                !prefs.getBoolean(KEY_AUTOMATION_SESSION_SETTINGS_MIGRATED, false)

            if (needsAutomationMigration) {
                val migrated = parsed.copy(
                    // Sequential mode is now the supported all-account execution model.
                    sequentialAccounts = true,
                    // Preserve the new safe default for existing installations.
                    keepScreenOn = true,
                    // Never enable a network reset silently on an existing installation.
                    networkResetEnabled = false,
                    networkResetMode = NetworkResetMode.NONE
                )
                prefs.edit()
                    .putString(KEY_SETTINGS, gson.toJson(migrated))
                    .putBoolean(KEY_AUTOMATION_SESSION_SETTINGS_MIGRATED, true)
                    .apply()
                migrated
            } else {
                parsed.copy(sequentialAccounts = true)
            }
        } catch (e: Exception) {
            GlobalSettings()
        }
    }

    fun saveSettings(settings: GlobalSettings) {
        val json = gson.toJson(settings)
        prefs.edit()
            .putString(KEY_SETTINGS, json)
            .putBoolean(KEY_MINE_AUTO_EXCHANGE_MIGRATED, true)
            .apply()
    }
}
