package com.example.mangabuffauto.automation

import android.content.Context
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature

/**
 * Управляет изолированными WebView-профилями.
 *
 * Профиль MangaBuff — это отдельная браузерная сессия WebView:
 * cookies, localStorage и WebStorage не смешиваются между профилями.
 * Пароли приложение не хранит.
 */
class MultiProfileManager(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val profileStore: ProfileStore? =
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            ProfileStore.getInstance()
        } else {
            null
        }

    fun isSupported(): Boolean = profileStore != null

    fun profiles(): List<String> {
        val store = profileStore ?: return listOf(DEFAULT_PROFILE)
        return store.getAllProfileNames()
            .filter { it != "Default" }
            .ifEmpty { listOf(DEFAULT_PROFILE) }
    }

    fun activeProfile(): String =
        prefs.getString(KEY_ACTIVE_PROFILE, DEFAULT_PROFILE) ?: DEFAULT_PROFILE

    fun setActiveProfile(name: String) {
        require(name.isNotBlank())
        prefs.edit().putString(KEY_ACTIVE_PROFILE, name).apply()
        ensureProfile(name)
    }

    fun ensureProfile(name: String) {
        if (!isSupported()) return
        profileStore?.getOrCreateProfile(name)
    }

    fun createProfile(name: String): Boolean {
        val clean = name.trim()
        if (clean.isEmpty() || clean == "Default") return false
        if (!isSupported()) return false
        profileStore?.getOrCreateProfile(clean)
        return true
    }

    companion object {
        const val DEFAULT_PROFILE = "account_1"
        private const val PREFS_NAME = "mangabuff_profiles"
        private const val KEY_ACTIVE_PROFILE = "active_profile"
    }
}
