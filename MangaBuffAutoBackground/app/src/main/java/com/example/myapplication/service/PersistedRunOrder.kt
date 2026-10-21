package com.example.myapplication.service

import com.example.myapplication.data.TaskType

/** Restores runs in the user's configured account order, not SharedPreferences StringSet iteration order. */
internal object PersistedRunOrder {
    fun order(
        persistedRuns: Map<String, TaskType>,
        configuredAccountIds: List<String>
    ): List<Pair<String, TaskType>> {
        val configuredIndex = configuredAccountIds.withIndex().associate { it.value to it.index }
        return persistedRuns.entries
            .sortedWith(
                compareBy<Map.Entry<String, TaskType>>(
                    { configuredIndex[it.key] ?: Int.MAX_VALUE },
                    { it.key }
                )
            )
            .map { it.key to it.value }
    }
}
