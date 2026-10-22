package com.example.myapplication.network

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class AppProxyConfigValidationTest {
    @Test
    fun enabledInvalidProxyIsRejectedInsteadOfBecomingSystemRoute() {
        val invalid = AppProxyConfig(enabled = true, host = "", port = 8080)
        try {
            invalid.validateForRouting()
            fail("A required invalid proxy must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertEquals("PROXY_CONFIG_INVALID", expected.message)
        }
    }

    @Test
    fun disabledProxyMayUseSystemRouteWithoutHost() {
        AppProxyConfig(enabled = false, host = "", port = 0).validateForRouting()
    }
}
