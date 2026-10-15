package com.example.myapplication.automation

import java.util.concurrent.ConcurrentHashMap

/**
 * Immediate in-process state for the per-account deck-comment toggle.
 *
 * The persisted account model remains the source of truth across process restarts;
 * this registry lets an already-running deck coroutine react without polling disk.
 */
object DeckCommentToggleRegistry {
    private val enabledByAccount = ConcurrentHashMap<String, Boolean>()

    /**
     * Seed state when an account run starts, without overwriting a newer UI toggle
     * made while the run was waiting in the account queue.
     */
    fun initializeIfAbsent(accountId: String, enabled: Boolean) {
        if (accountId.isNotBlank()) {
            enabledByAccount.putIfAbsent(accountId, enabled)
        }
    }

    fun setEnabled(accountId: String, enabled: Boolean) {
        if (accountId.isNotBlank()) {
            enabledByAccount[accountId] = enabled
        }
    }

    fun isEnabled(accountId: String, fallback: Boolean): Boolean =
        enabledByAccount[accountId] ?: fallback

    fun remove(accountId: String) {
        enabledByAccount.remove(accountId)
    }
}
