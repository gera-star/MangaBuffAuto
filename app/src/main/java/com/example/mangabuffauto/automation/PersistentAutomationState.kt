package com.example.mangabuffauto.automation

import android.content.Context

/**
 * Small durable checkpoint store. It intentionally contains no cookies,
 * passwords or authentication tokens. WebView Profile owns browser data.
 */
class PersistentAutomationState(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Snapshot(
        val profile: String,
        val lastUrl: String,
        val autoMode: Boolean,
        val readingSpeed: Float,
        val taskId: String?,
        val taskRunId: String?,
        val taskAttempt: Int
    )

    fun lastUrl(profile: String): String? =
        prefs.getString(key(profile, "last_url"), null)

    fun saveLastUrl(profile: String, url: String) {
        if (url.isBlank()) return
        prefs.edit().putString(key(profile, "last_url"), url).apply()
    }

    fun saveAutoMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_MODE, enabled).apply()
    }

    fun autoModeEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_MODE, false)

    fun saveReadingSpeed(profile: String, speed: Float) {
        prefs.edit().putFloat(key(profile, "reading_speed"), speed).apply()
    }

    fun readingSpeed(profile: String): Float =
        prefs.getFloat(key(profile, "reading_speed"), 1.0f)

    fun markTaskStarted(profile: String, taskId: String, runId: String, attempt: Int) {
        prefs.edit()
            .putString(key(profile, "task_id"), taskId)
            .putString(key(profile, "task_run_id"), runId)
            .putInt(key(profile, "task_attempt"), attempt)
            .putLong(key(profile, "task_started_at"), System.currentTimeMillis())
            .apply()
    }

    fun markTaskFinished(profile: String) {
        prefs.edit()
            .remove(key(profile, "task_id"))
            .remove(key(profile, "task_run_id"))
            .remove(key(profile, "task_attempt"))
            .remove(key(profile, "task_started_at"))
            .apply()
    }

    fun interruptedTask(profile: String): Snapshot? {
        val taskId = prefs.getString(key(profile, "task_id"), null) ?: return null
        return Snapshot(
            profile = profile,
            lastUrl = lastUrl(profile).orEmpty(),
            autoMode = autoModeEnabled(),
            readingSpeed = readingSpeed(profile),
            taskId = taskId,
            taskRunId = prefs.getString(key(profile, "task_run_id"), null),
            taskAttempt = prefs.getInt(key(profile, "task_attempt"), 0)
        )
    }

    fun clearInterruptedTask(profile: String) = markTaskFinished(profile)

    private fun key(profile: String, suffix: String) = "profile_${profile}_$suffix"

    companion object {
        private const val PREFS = "mangabuff_persistent_state"
        private const val KEY_AUTO_MODE = "auto_mode"
    }
}
