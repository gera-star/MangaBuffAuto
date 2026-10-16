package com.example.myapplication.automation

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Resolves the rank of one exact catalogue card. The authoritative value is data-rank
 * attached to the card's .manga-cards__item-wrapper (or one of its ancestors), as seen
 * in MangaBuff's /manga/{id}/cards response. Never borrow a rank from a different card
 * on the same page.
 */
internal object CatalogRankParser {
    private const val CARD_SELECTORS =
        ".manga-cards__item[data-card-id], .lootbox__card[data-id], " +
        ".deck__item[data-card-id], .manga-cards__item-wrapper[data-id], [data-card-id]"

    private const val RANK_BADGE_SELECTORS =
        ".card-show__rank, .card-rank, .card-show__rank-name"

    fun findRank(doc: Document, cardId: String): String? {
        val wantedId = cardId.trim()
        if (wantedId.isEmpty() || !wantedId.all(Char::isDigit)) return null

        val exactNodes = exactCardNodes(doc, wantedId)

        for (candidate in exactNodes) {
            // In the catalogue response this climbs from .manga-cards__item to
            // .manga-cards__item-wrapper[data-rank]. Match the ID before reading rank.
            var node: Element? = candidate
            repeat(8) {
                val current = node ?: return@repeat
                val rank = normalizeKnownRank(current.attr("data-rank"))
                if (rank != null) return rank
                node = current.parent()
            }

            val badge = candidate.selectFirst(RANK_BADGE_SELECTORS)
            normalizeKnownRank(badge?.text().orEmpty())?.let { return it }
        }

        // A single-card detail/users page may expose only a rank badge. Use it only
        // when the page URL itself identifies the requested catalogue card; never
        // take the first badge from an unrelated listing or trade page.
        // Document.location() is an absolute URL (for example https://mangabuff.ru/cards/222),
        // so compare the URL path rather than comparing the full URL to a path literal.
        val path = runCatching {
            java.net.URI(doc.location()).path.orEmpty().trimEnd('/')
        }.getOrDefault("")
        if (path == "/cards/$wantedId" || path == "/cards/$wantedId/users") {
            val badge = doc.selectFirst(RANK_BADGE_SELECTORS)
            normalizeKnownRank(badge?.text().orEmpty())?.let { return it }
        }

        return null
    }

    /**
     * Check catalogue IDs only. In particular, do not treat lock-card-btn[data-id]
     * or data-card-user-id instance IDs as a catalogue card ID.
     */
    fun containsExactCard(doc: Document, cardId: String): Boolean {
        val wantedId = cardId.trim()
        if (wantedId.isEmpty() || !wantedId.all(Char::isDigit)) return false
        return exactCardNodes(doc, wantedId).isNotEmpty()
    }

    private fun exactCardNodes(doc: Document, wantedId: String): List<Element> =
        doc.select(CARD_SELECTORS).filter { node ->
            node.attr("data-card-id").trim() == wantedId ||
                node.attr("data-id").trim() == wantedId
        }

    private fun normalizeKnownRank(value: String): String? {
        val rank = value.trim().uppercase()
        return rank.takeIf {
            it in TradePolicy.STANDARD_RANKS || it in TradePolicy.SPECIAL_RANKS
        }
    }
}
