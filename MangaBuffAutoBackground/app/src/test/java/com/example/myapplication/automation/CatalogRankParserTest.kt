package com.example.myapplication.automation

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogRankParserTest {
    @Test
    fun readsRankFromDataRankWrapperForExactCardId() {
        val doc = Jsoup.parse(
            """
            <div class="manga-cards__item-wrapper" data-rank="E">
              <div class="manga-cards__item" data-card-id="111"></div>
            </div>
            <div class="manga-cards__item-wrapper" data-rank="S">
              <div class="manga-cards__item" data-card-id="222"></div>
            </div>
            """.trimIndent(),
            "https://mangabuff.ru/manga/38914/cards"
        )

        assertEquals("S", CatalogRankParser.findRank(doc, "222"))
        assertEquals("E", CatalogRankParser.findRank(doc, "111"))
    }

    @Test
    fun doesNotUseAnotherCardsRankWhenRequestedCardIsMissing() {
        val doc = Jsoup.parse(
            """<div class="manga-cards__item-wrapper" data-rank="S">
                 <div class="manga-cards__item" data-card-id="111"></div>
               </div>""",
            "https://mangabuff.ru/manga/38914/cards"
        )

        assertNull(CatalogRankParser.findRank(doc, "222"))
    }

    @Test
    fun readsRankFromExactCardDetailBadge() {
        val doc = Jsoup.parse(
            """<div class="card-show__rank">A</div>""",
            "https://mangabuff.ru/cards/222"
        )

        assertEquals("A", CatalogRankParser.findRank(doc, "222"))
    }

    @Test
    fun rejectsUnknownRankInsteadOfGuessing() {
        val doc = Jsoup.parse(
            """<div class="manga-cards__item-wrapper" data-rank="Z">
                 <div class="manga-cards__item" data-card-id="222"></div>
               </div>""",
            "https://mangabuff.ru/manga/38914/cards"
        )

        assertNull(CatalogRankParser.findRank(doc, "222"))
    }

    @Test
    fun acceptsKnownSpecialRanksWhenExplicitlyPresent() {
        val doc = Jsoup.parse(
            """<div class="manga-cards__item-wrapper" data-rank="H">
                 <div class="manga-cards__item" data-card-id="222"></div>
               </div>""",
            "https://mangabuff.ru/manga/38914/cards"
        )

        assertEquals("H", CatalogRankParser.findRank(doc, "222"))
    }
}
