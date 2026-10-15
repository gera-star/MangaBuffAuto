package com.example.myapplication.automation

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

data class DeckCommentToggleSnapshot(
    val enabled: Boolean,
    /**
     * Increases whenever ON transitions to OFF. Active runs capture this value at
     * start, so a rapid OFF -> ON cannot accidentally revive an already-cancelled run.
     */
    val disableVersion: Long = 0L,
    /** Monotonic timestamp of the most recent ON -> OFF transition in this process. */
    val disabledAtElapsedRealtimeMs: Long = 0L
)

/**
 * Immediate in-process state for the per-account deck-comment toggle.
 *
 * The persisted account model remains the source of truth across process restarts;
 * this registry lets an already-running deck coroutine react without polling disk.
 */
object DeckCommentToggleRegistry {
    private val enabledByAccount =
        ConcurrentHashMap<String, MutableStateFlow<DeckCommentToggleSnapshot>>()

    private fun mutableStateFor(
        accountId: String,
        initialEnabled: Boolean
    ): MutableStateFlow<DeckCommentToggleSnapshot> =
        enabledByAccount.computeIfAbsent(accountId) {
            MutableStateFlow(DeckCommentToggleSnapshot(enabled = initialEnabled))
        }

    fun stateFor(
        accountId: String,
        fallback: Boolean
    ): StateFlow<DeckCommentToggleSnapshot> =
        mutableStateFor(accountId, fallback).asStateFlow()

    /**
     * Seed state when an account run starts, without overwriting a newer UI toggle
     * made while the run was waiting in the account queue.
     */
    fun initializeIfAbsent(accountId: String, enabled: Boolean) {
        if (accountId.isNotBlank()) {
            enabledByAccount.putIfAbsent(
                accountId,
                MutableStateFlow(DeckCommentToggleSnapshot(enabled = enabled))
            )
        }
    }

    fun setEnabled(accountId: String, enabled: Boolean) {
        if (accountId.isBlank()) return
        val state = mutableStateFor(accountId, enabled)
        synchronized(state) {
            val previous = state.value
            if (previous.enabled == enabled) return
            val disablingNow = previous.enabled && !enabled
            val nextDisableVersion = if (disablingNow) {
                previous.disableVersion + 1L
            } else {
                previous.disableVersion
            }
            state.value = DeckCommentToggleSnapshot(
                enabled = enabled,
                disableVersion = nextDisableVersion,
                disabledAtElapsedRealtimeMs = if (disablingNow) {
                    SystemClock.elapsedRealtime()
                } else {
                    // Preserve the last disable timestamp through a quick OFF -> ON.
                    previous.disabledAtElapsedRealtimeMs
                }
            )
        }
    }

    fun isEnabled(accountId: String, fallback: Boolean): Boolean =
        stateFor(accountId, fallback).value.enabled

    fun remove(accountId: String) {
        enabledByAccount.remove(accountId)
    }
}
