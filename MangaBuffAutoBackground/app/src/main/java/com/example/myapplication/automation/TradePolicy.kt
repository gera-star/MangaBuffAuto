package com.example.myapplication.automation

/** Pure valuation/safety rules based on the observed MangaBuff rank order. */
data class TradeValueCard(val rank: String, val catalogId: String, val instanceId: String?)
object TradePolicy {
    val STANDARD_ORDER = listOf("E", "D", "C", "B", "G", "P", "A", "S")
    val STANDARD_RANKS: Set<String> = STANDARD_ORDER.toSet()
    val SPECIAL_RANKS: Set<String> = setOf("H", "N", "V", "Q", "L", "K", "W", "X")
    fun standardWeight(rank: String): Int? = STANDARD_ORDER.indexOf(rank.trim().uppercase()).takeIf { it >= 0 }?.plus(1)
    fun rejectionReason(given: TradeValueCard, received: List<TradeValueCard>, mode: TradeRuleMode, coefficient: Double,
        protectedIds: Set<String>, specialInstanceWeights: Map<String, Int>): String? {
        if (received.size < 2) return "получается меньше двух карт"
        if (given.catalogId.isBlank()) return "нет catalog ID отдаваемой карты"
        if (given.rank.trim().uppercase() == "X") return "карты ранга X нельзя отдавать автоматически"
        if (given.catalogId in protectedIds || given.instanceId?.let(protectedIds::contains) == true) return "ID отдаваемой карты есть в пользовательском списке защиты"
        val rank = given.rank.trim().uppercase()
        val customGiven = given.instanceId?.let(specialInstanceWeights::get)
        val gv = standardWeight(rank) ?: when {
            rank !in SPECIAL_RANKS -> return "неизвестный ранг отдаваемой карты: $rank"
            rank == "X" -> return "карты ранга X нельзя отдавать автоматически"
            given.instanceId.isNullOrBlank() -> return "для специального ранга $rank не найден card_user_id"
            customGiven != null && customGiven in 1..8 -> customGiven
            else -> return "специальный ранг $rank не разрешён: задай card_user_id=вес (1–8)"
        }
        if (!coefficient.isFinite() || coefficient !in 1.0..10.0) return "коэффициент должен быть от 1 до 10"
        val weights = mutableListOf<Int>()
        for (c in received) {
            if (c.catalogId.isBlank()) return "у получаемой карты отсутствует catalog ID"
            val rr = c.rank.trim().uppercase()
            val custom = c.instanceId?.let(specialInstanceWeights::get)
            val v = standardWeight(rr) ?: when {
                rr !in SPECIAL_RANKS -> return "неизвестный ранг получаемой карты: $rr"
                rr == "X" -> 9
                c.instanceId.isNullOrBlank() -> return "для получаемого специального ранга $rr не найден card_user_id"
                custom != null && custom in 1..8 -> custom
                else -> return "у получаемой специальной карты $rr нет явно заданного веса"
            }
            weights += v
        }
        when (mode) {
            TradeRuleMode.STRICT -> {
                val lower = weights.indexOfFirst { it < gv }
                if (lower >= 0) return "строгий режим: вес получаемой карты №${lower + 1} ниже отдаваемой (${gv})"
            }
            TradeRuleMode.VALUE -> {
                val total = weights.sum()
                val needed = gv * coefficient
                if (total + 0.0001 < needed) return "сумма весов ${total} ниже требуемой ${ "%.2f".format(java.util.Locale.US, needed) }"
            }
        }
        return null
    }
}
