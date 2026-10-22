package com.example.myapplication.service

import com.example.myapplication.network.AppProxyConfig
import org.junit.Assert.assertFalse
import org.junit.Test

class AccountExecutionModePolicyTest {
    @Test
    fun queueModeNeverRunsAccountsInParallel() {
        assertFalse(
            AccountExecutionModePolicy.shouldUseParallel(
                requestedParallel = false,
                configs = listOf(AppProxyConfig(), AppProxyConfig())
            )
        )
    }

    @Test
    fun strictModeBlocksParallelEvenWithoutCustomProxies() {
        assertFalse(AccountExecutionModePolicy.shouldUseParallel(
            requestedParallel = true,
            configs = listOf(AppProxyConfig(), AppProxyConfig())
        ))
    }

    @Test
    fun strictModeBlocksParallelForTheSameCustomProxyRouteToo() {
        assertFalse(
            AccountExecutionModePolicy.shouldUseParallel(
                requestedParallel = true,
                configs = listOf(
                    AppProxyConfig(enabled = true, host = "proxy.example", port = 8080,
                        username = "user-a", password = "secret-a"),
                    AppProxyConfig(enabled = true, host = "PROXY.EXAMPLE", port = 8080,
                        username = "user-b", password = "secret-b")
                )
            )
        )
    }

    @Test
    fun differentProxyRoutesForceSequentialExecution() {
        assertFalse(
            AccountExecutionModePolicy.shouldUseParallel(
                requestedParallel = true,
                configs = listOf(
                    AppProxyConfig(enabled = true, host = "proxy-a.example", port = 8080),
                    AppProxyConfig(enabled = true, host = "proxy-b.example", port = 8080)
                )
            )
        )
    }

    @Test
    fun mixedSystemAndCustomProxyRoutesForceSequentialExecution() {
        assertFalse(
            AccountExecutionModePolicy.shouldUseParallel(
                requestedParallel = true,
                configs = listOf(
                    AppProxyConfig(),
                    AppProxyConfig(enabled = true, host = "proxy.example", port = 8080)
                )
            )
        )
    }
}
