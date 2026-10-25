package com.example.myapplication.web

import com.example.myapplication.network.AppProxyConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountWebViewerProcessRouteOwnerTest {
    @Test
    fun firstRouteMustBeAppliedAndSameRouteCanThenBeReused() {
        val owner = AccountWebViewerProcessRouteOwner()
        val route = AppProxyConfig(true, "proxy.example", 8080, "user", "secret")

        val first = owner.reserve(route) as AccountWebViewerRouteReservation.Accepted
        assertTrue(first.applyProxyOverride)
        assertTrue(owner.markApplied(first.key))

        val again = owner.reserve(route.copy(host = "PROXY.EXAMPLE")) as AccountWebViewerRouteReservation.Accepted
        assertFalse(again.applyProxyOverride)
        assertTrue(owner.isAppliedForTest())
    }

    @Test
    fun changingProxyOrCredentialsIsRejectedForLifetimeOfProcess() {
        val owner = AccountWebViewerProcessRouteOwner()
        val first = owner.reserve(AppProxyConfig(true, "proxy.example", 8080, "user", "secret"))
            as AccountWebViewerRouteReservation.Accepted
        assertTrue(owner.markApplied(first.key))

        assertTrue(owner.reserve(AppProxyConfig(true, "other.example", 8080, "user", "secret"))
            is AccountWebViewerRouteReservation.Rejected)
        assertTrue(owner.reserve(AppProxyConfig(true, "proxy.example", 8080, "user", "different"))
            is AccountWebViewerRouteReservation.Rejected)
        assertTrue(owner.reserve(AppProxyConfig()).let { it is AccountWebViewerRouteReservation.Rejected })
    }

    @Test
    fun pendingApplyCannotBeReusedAndQuarantinesProcess() {
        val owner = AccountWebViewerProcessRouteOwner()
        owner.reserve(AppProxyConfig(true, "proxy.example", 8080))
            as AccountWebViewerRouteReservation.Accepted

        assertTrue(owner.reserve(AppProxyConfig(true, "proxy.example", 8080))
            is AccountWebViewerRouteReservation.Rejected)
        assertTrue(owner.isQuarantined())
        assertTrue(owner.reserve(AppProxyConfig(true, "proxy.example", 8080))
            is AccountWebViewerRouteReservation.Rejected)
    }

    @Test
    fun failedApplyPermanentlyQuarantinesProcess() {
        val owner = AccountWebViewerProcessRouteOwner()
        owner.reserve(AppProxyConfig(true, "proxy.example", 8080))
        owner.quarantine()

        assertTrue(owner.isQuarantined())
        assertTrue(owner.reserve(AppProxyConfig(true, "proxy.example", 8080))
            is AccountWebViewerRouteReservation.Rejected)
    }

    @Test
    fun invalidProxyDoesNotBindRoute() {
        val owner = AccountWebViewerProcessRouteOwner()
        assertTrue(owner.reserve(AppProxyConfig(true, "", 8080))
            is AccountWebViewerRouteReservation.Rejected)

        val valid = owner.reserve(AppProxyConfig(true, "proxy.example", 8080))
            as AccountWebViewerRouteReservation.Accepted
        assertTrue(valid.applyProxyOverride)
    }
}
