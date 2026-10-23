package com.example.myapplication.automation

/**
 * Merge every runtime/job/engine account with any WebView still owned by the store.
 * A WebView without a matching runtime/job must still be stopped before the worker emits ACK.
 */
internal object AccountRuntimeCleanupPlan {
    fun merge(vararg accountGroups: Collection<String>): Set<String> =
        accountGroups.asSequence().flatten().filter { it.isNotBlank() }.toSet()
}
