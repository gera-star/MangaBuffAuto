package com.example.myapplication.service

import com.example.myapplication.data.TaskType
import org.junit.Assert.assertEquals
import org.junit.Test

class PersistedRunOrderTest {
    @Test
    fun restoresRunsInConfiguredAccountOrderRatherThanMapIterationOrder() {
        val persisted = linkedMapOf(
            "account-C" to TaskType.READER,
            "account-A" to TaskType.ALL,
            "account-B" to TaskType.TOWER
        )

        assertEquals(
            listOf(
                "account-B" to TaskType.TOWER,
                "account-C" to TaskType.READER,
                "account-A" to TaskType.ALL
            ),
            PersistedRunOrder.order(
                persisted,
                configuredAccountIds = listOf("account-B", "account-C", "account-A")
            )
        )
    }

    @Test
    fun unknownPersistedAccountsSortAfterConfiguredAccountsDeterministically() {
        val persisted = linkedMapOf(
            "deleted-Z" to TaskType.BATTLE,
            "account-B" to TaskType.ALL,
            "deleted-A" to TaskType.TOWER
        )

        assertEquals(
            listOf(
                "account-B" to TaskType.ALL,
                "deleted-A" to TaskType.TOWER,
                "deleted-Z" to TaskType.BATTLE
            ),
            PersistedRunOrder.order(persisted, listOf("account-B", "account-A"))
        )
    }
}
