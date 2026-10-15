package com.example.myapplication.automation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-local, per-account control for the currently running deck-comment task.
 *
 * disableVersion is monotonic for the lifetime of this process. A run captures its
 * version when it starts; once it observes a disable transition, turning the setting
 * back on cannot revive that already-running run. A subsequent run captures the new
 * version and can proceed normally.
 */
internal data class DeckCommentToggleSnapshot(
    val enabled: Boolean,
    val disableVersion: Long = 0L
)

internal object DeckCommentToggleState {
    private val states =
        ConcurrentHashMap<String, MutableStateFlow<DeckCommentToggleSnapshot>>()

    fun stateFor(
        accountId: String,
        initialEnabled: Boolean
    ): StateFlow<DeckCommentToggleSnapshot> =
        states.computeIfAbsent(accountId) {
            MutableStateFlow(DeckCommentToggleSnapshot(enabled = initialEnabled))
        }

    fun setEnabled(accountId: String, enabled: Boolean) {
        val state = states.computeIfAbsent(accountId) {
            MutableStateFlow(DeckCommentToggleSnapshot(enabled = enabled))
        }

        synchronized(state) {
            val previous = state.value
            if (previous.enabled == enabled) return
            val nextDisableVersion = if (previous.enabled && !enabled) {
                previous.disableVersion + 1L
            } else {
                previous.disableVersion
            }
            state.value = DeckCommentToggleSnapshot(
                enabled = enabled,
                disableVersion = nextDisableVersion
            )
        }
    }
}
