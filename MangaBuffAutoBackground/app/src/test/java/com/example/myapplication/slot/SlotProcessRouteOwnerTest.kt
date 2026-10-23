package com.example.myapplication.slot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotProcessRouteOwnerTest {
    @Test
    fun sameRouteCanBeReusedWithoutChangingProcessOverride() {
        val owner = SlotProcessRouteOwner()
        val route = SlotRoute(SlotProxyType.HTTP, "proxy.example", 8080)
        val first = owner.reserve(route) as SlotRouteReservation.Accepted
        assertTrue(first.applyProxyOverride)
        assertTrue(owner.markProxyApplied(first.binding))

        val again = owner.reserve(route) as SlotRouteReservation.Accepted
        assertFalse(again.applyProxyOverride)
        assertTrue(again.binding === first.binding)
    }

    @Test
    fun proxyThenNoneIsRejectedEvenAfterActivityWouldClose() {
        val owner = SlotProcessRouteOwner()
        val first = owner.reserve(SlotRoute(SlotProxyType.HTTP, "proxy.example", 8080))
            as SlotRouteReservation.Accepted
        owner.markProxyApplied(first.binding)
        assertTrue(owner.reserve(SlotRoute()) is SlotRouteReservation.Rejected)
    }

    @Test
    fun noneThenProxyIsRejected() {
        val owner = SlotProcessRouteOwner()
        owner.reserve(SlotRoute()) as SlotRouteReservation.Accepted
        assertTrue(owner.reserve(SlotRoute(SlotProxyType.HTTP, "proxy.example", 8080)) is SlotRouteReservation.Rejected)
    }

    @Test
    fun differentCredentialsAreDifferentRouteBindings() {
        val owner = SlotProcessRouteOwner()
        val first = owner.reserve(SlotRoute(SlotProxyType.HTTP, "proxy.example", 8080, "user", "one"))
            as SlotRouteReservation.Accepted
        owner.markProxyApplied(first.binding)
        assertTrue(
            owner.reserve(SlotRoute(SlotProxyType.HTTP, "proxy.example", 8080, "user", "two"))
                is SlotRouteReservation.Rejected
        )
    }

    @Test
    fun failedProxyApplicationQuarantinesProcessPermanently() {
        val owner = SlotProcessRouteOwner()
        val first = owner.reserve(SlotRoute(SlotProxyType.HTTP, "proxy.example", 8080))
            as SlotRouteReservation.Accepted
        owner.quarantine(first.binding, "apply failed")
        assertTrue(owner.isQuarantinedForTest())
        assertTrue(owner.reserve(SlotRoute(SlotProxyType.HTTP, "proxy.example", 8080)) is SlotRouteReservation.Rejected)
    }
}
