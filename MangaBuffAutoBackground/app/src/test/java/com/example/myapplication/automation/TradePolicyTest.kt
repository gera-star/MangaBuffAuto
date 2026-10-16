package com.example.myapplication.automation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
class TradePolicyTest {
    private val none = emptySet<String>()
    private val noWeights = emptyMap<String, Int>()
    private fun c(rank: String, id: String, instance: String? = "inst-$id") = TradeValueCard(rank, id, instance)
    @Test fun standardRanksFollowObservedOrder() {
        assertEquals(1, TradePolicy.standardWeight("E")); assertEquals(4, TradePolicy.standardWeight("B"))
        assertEquals(8, TradePolicy.standardWeight("S")); assertNull(TradePolicy.standardWeight("H")); assertNull(TradePolicy.standardWeight("?"))
    }
    @Test fun strictModeChecksEveryReceivedCard() {
        assertNotNull(TradePolicy.rejectionReason(c("B","10"),listOf(c("B","11"),c("C","12")),TradeRuleMode.STRICT,1.5,none,noWeights))
        assertNull(TradePolicy.rejectionReason(c("B","10"),listOf(c("B","11"),c("S","12")),TradeRuleMode.STRICT,1.5,none,noWeights))
    }
    @Test fun cumulativeModeUsesCoefficient() {
        assertNull(TradePolicy.rejectionReason(c("B","10"),listOf(c("P","11"),c("D","12")),TradeRuleMode.VALUE,1.5,none,noWeights))
        assertNotNull(TradePolicy.rejectionReason(c("B","10"),listOf(c("C","11"),c("E","12")),TradeRuleMode.VALUE,1.5,none,noWeights))
    }
    @Test fun rankXMayBeReceivedButNeverGiven() {
        assertNotNull(TradePolicy.rejectionReason(c("X","10"),listOf(c("S","11"),c("S","12")),TradeRuleMode.STRICT,1.5,none,noWeights))
        assertNull(TradePolicy.rejectionReason(c("B","10"),listOf(c("X","11"),c("S","12")),TradeRuleMode.STRICT,1.5,none,noWeights))
    }
    @Test fun protectedCatalogOrInstanceIdRejectsGiveAway() {
        assertNotNull(TradePolicy.rejectionReason(c("B","10"),listOf(c("B","11"),c("S","12")),TradeRuleMode.STRICT,1.5,setOf("10"),noWeights))
        assertNotNull(TradePolicy.rejectionReason(c("B","10","instance-10"),listOf(c("B","11"),c("S","12")),TradeRuleMode.STRICT,1.5,setOf("instance-10"),noWeights))
    }
    @Test fun specialRanksRequireIndividualWeight() {
        val special=c("H","10","690490862")
        assertNotNull(TradePolicy.rejectionReason(special,listOf(c("S","11"),c("S","12")),TradeRuleMode.STRICT,1.5,none,noWeights))
        assertNull(TradePolicy.rejectionReason(special,listOf(c("S","11"),c("S","12")),TradeRuleMode.STRICT,1.5,none,mapOf("690490862" to 6)))
    }
    @Test fun specialReceivedRankWithoutWeightIsRejected() {
        assertNotNull(TradePolicy.rejectionReason(c("B","10"),listOf(c("B","11"),c("V","12","instance-v")),TradeRuleMode.STRICT,1.5,none,noWeights))
    }
}
