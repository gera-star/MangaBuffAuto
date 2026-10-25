package com.example.myapplication.web

import com.example.myapplication.network.AppProxyConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountWebViewerCleanupPolicyTest {
    @Test
    fun cleanupFailureQuarantinesOwnerAndRejectsFurtherRouteReservations() {
        val owner = AccountWebViewerProcessRouteOwner()
        val route = AppProxyConfig(true, "proxy.example", 8080)
        val first = owner.reserve(route) as AccountWebViewerRouteReservation.Accepted
        assertTrue(first.applyProxyOverride)

        var destroyAttempted = false
        val cleaned = AccountWebViewerCleanupPolicy.destroyOrQuarantine(owner) {
            destroyAttempted = true
            throw IllegalStateException("simulated WebView.destroy failure")
        }

        assertTrue(destroyAttempted)
        assertFalse(cleaned)
        assertTrue(owner.isQuarantined())
        assertTrue(owner.reserve(route) is AccountWebViewerRouteReservation.Rejected)
    }

    @Test
    fun laterSuccessfulCleanupDoesNotClearProcessQuarantine() {
        val owner = AccountWebViewerProcessRouteOwner()
        owner.quarantine()

        val cleaned = AccountWebViewerCleanupPolicy.destroyOrQuarantine(owner) { }

        assertTrue(cleaned)
        assertTrue(owner.isQuarantined())
        assertTrue(owner.reserve(AppProxyConfig(true, "proxy.example", 8080))
            is AccountWebViewerRouteReservation.Rejected)
    }

    @Test
    fun successfulCleanupDoesNotQuarantineOwner() {
        val owner = AccountWebViewerProcessRouteOwner()
        var stopped = false
        var destroyed = false

        val cleaned = AccountWebViewerCleanupPolicy.destroyOrQuarantine(owner) {
            stopped = true
            destroyed = true
        }

        assertTrue(cleaned)
        assertTrue(stopped)
        assertTrue(destroyed)
        assertFalse(owner.isQuarantined())
    }
}
