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
    }

    fun getAccounts(): List<MangaBuffAccount> {
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

    fun saveAccounts(accounts: List<MangaBuffAccount>) {
        // При сохранении в SharedPreferences флаги выполнения всегда сохраняем как false
        val sanitized = accounts.map { acc ->
            acc.copy(isRunning = false, taskProgress = 0f)
        }
        val json = gson.toJson(sanitized)
        prefs.edit().putString(KEY_ACCOUNTS, json).apply()
    }

    fun saveAccount(account: MangaBuffAccount) {
        val list = getAccounts().toMutableList()
        val index = list.indexOfFirst { it.id == account.id }
        if (index >= 0) {
            list[index] = account
        } else {
            list.add(account)
        }
        saveAccounts(list)
    }

    fun deleteAccount(accountId: String) {
        val list = getAccounts().filterNot { it.id == accountId }
        saveAccounts(list)
    }

    fun getSettings(): GlobalSettings {
        val json = prefs.getString(KEY_SETTINGS, null) ?: return GlobalSettings()
        return try {
            gson.fromJson(json, GlobalSettings::class.java) ?: GlobalSettings()
        } catch (e: Exception) {
            GlobalSettings()
        }
    }

    fun saveSettings(settings: GlobalSettings) {
        val json = gson.toJson(settings)
        prefs.edit().putString(KEY_SETTINGS, json).apply()
    }
}
