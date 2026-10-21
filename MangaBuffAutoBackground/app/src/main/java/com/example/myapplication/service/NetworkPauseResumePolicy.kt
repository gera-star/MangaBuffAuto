package com.example.myapplication.service

/**
 * A network interruption may resume a persisted task only if that same task is still intended
 * to run. Explicit user stops, OFFLINE accounts, or replaced tasks must never be auto-started.
 */
internal object NetworkPauseResumePolicy {
    const val UNVALIDATED_GRACE_MS = 20_000L

    fun shouldDelayPause(connected: Boolean, validated: Boolean): Boolean =
        connected && !validated

    fun shouldResume(
        networkPauseRequested: Boolean,
        persistedTaskMatches: Boolean,
        stoppingExplicitly: Boolean,
        accountOffline: Boolean
    ): Boolean =
        networkPauseRequested &&
            persistedTaskMatches &&
            !stoppingExplicitly &&
            !accountOffline
}
