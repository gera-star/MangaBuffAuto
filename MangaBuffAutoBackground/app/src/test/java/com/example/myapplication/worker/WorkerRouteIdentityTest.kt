package com.example.myapplication.worker

import com.example.myapplication.network.AppProxyConfig
import com.example.myapplication.service.AccountExecutionModePolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerRouteIdentityTest {
    @Test
    fun acceptsOnlyRouteKeyDerivedFromTheSuppliedProxyConfig() {
        val config = AppProxyConfig(
            enabled = true,
            host = "proxy.example",
            port = 8080,
            username = "not-logged",
            password = "not-logged"
        )
        val expected = AccountExecutionModePolicy.proxyRouteKey(config)
        assertTrue(WorkerRouteIdentityPolicy.matches(expected, config))
        assertFalse(WorkerRouteIdentityPolicy.matches("proxy:proxy.example:8081", config))
        assertFalse(WorkerRouteIdentityPolicy.matches("system", config))
        assertFalse(WorkerRouteIdentityPolicy.matches("", config))
    }

    @Test
    fun disabledProxyRequiresSystemRouteKey() {
        val config = AppProxyConfig(enabled = false)
        assertTrue(WorkerRouteIdentityPolicy.matches("system", config))
        assertFalse(WorkerRouteIdentityPolicy.matches("proxy:example:8080", config))
    }
}
