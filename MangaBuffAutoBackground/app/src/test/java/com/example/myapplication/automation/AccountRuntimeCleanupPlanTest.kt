package com.example.myapplication.automation

import org.junit.Assert.assertEquals
import org.junit.Test

class AccountRuntimeCleanupPlanTest {
    @Test
    fun includesWebViewThatHasNoRuntimeEngineOrJob() {
        val ids = AccountRuntimeCleanupPlan.merge(
            listOf("account-runtime"),
            listOf("account-engine"),
            listOf("account-job"),
            listOf("orphan-webview"),
            emptyList()
        )
        assertEquals(
            setOf("account-runtime", "account-engine", "account-job", "orphan-webview"),
            ids
        )
    }

    @Test
    fun mergesDuplicatesAndFiltersInvalidIds() {
        assertEquals(
            setOf("account-1", "account-2"),
            AccountRuntimeCleanupPlan.merge(
                listOf("account-1", "", "account-2"),
                listOf("account-1", "  "),
                emptyList()
            )
        )
    }
}
