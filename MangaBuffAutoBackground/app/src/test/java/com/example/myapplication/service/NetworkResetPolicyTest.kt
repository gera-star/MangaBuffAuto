package com.example.myapplication.service

import com.example.myapplication.data.NetworkResetMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkResetPolicyTest {
    private fun shouldReset(
        enabled: Boolean = true,
        mode: NetworkResetMode = NetworkResetMode.AIRPLANE_MODE,
        runCompleted: Boolean = true,
        hasNextAccount: Boolean = true,
        stoppingExplicitly: Boolean = false,
        accountOffline: Boolean = false
    ) = NetworkResetPolicy.shouldResetBetweenAccounts(
        enabled = enabled,
        mode = mode,
        runCompleted = runCompleted,
        hasNextAccount = hasNextAccount,
        stoppingExplicitly = stoppingExplicitly,
        accountOffline = accountOffline
    )

    @Test
    fun resetRunsOnlyAfterSuccessfulTaskWhenAnotherAccountWaits() {
        assertTrue(shouldReset())
        assertFalse(shouldReset(runCompleted = false))
        assertFalse(shouldReset(hasNextAccount = false))
    }

    @Test
    fun disabledNoneStoppedAndOfflineRunsNeverReset() {
        assertFalse(shouldReset(enabled = false))
        assertFalse(shouldReset(mode = NetworkResetMode.NONE))
        assertFalse(shouldReset(stoppingExplicitly = true))
        assertFalse(shouldReset(accountOffline = true))
    }
}
