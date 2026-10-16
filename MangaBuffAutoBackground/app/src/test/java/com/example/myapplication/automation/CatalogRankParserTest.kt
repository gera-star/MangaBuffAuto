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

    @Test
    fun readsRankFromRealOwnerCardMarkupWithoutConfusingInstanceId() {
        val doc = Jsoup.parse(
            """
            <div class="manga-cards__item-wrapper" data-rank="P" data-is-special="0">
              <div class="manga-cards__item" data-card-id="68318">
                <div class="card-actions" data-card-id="68318" data-card-user-id="675286385">
                  <button data-action="lock" data-card-id="68318" data-card-user-id="675286385"
                    aria-pressed="false" title="Заблокировать карту"></button>
                </div>
                <div class="lock-card-btn" data-id="675286385"><i class="icon icon-unlock"></i></div>
              </div>
            </div>
            <div class="manga-cards__item-wrapper" data-rank="P" data-is-special="0">
              <div class="manga-cards__item" data-card-id="392525">
                <div class="card-actions" data-card-id="392525" data-card-user-id="687366753">
                  <button data-action="lock" data-card-id="392525" data-card-user-id="687366753"
                    aria-pressed="false" title="Заблокировать карту"></button>
                </div>
                <div class="lock-card-btn" data-id="687366753"><i class="icon icon-unlock"></i></div>
              </div>
            </div>
            """.trimIndent(),
            "https://mangabuff.ru/users/168879/cards"
        )

        assertEquals("P", CatalogRankParser.findRank(doc, "68318"))
        assertEquals("P", CatalogRankParser.findRank(doc, "392525"))
        assertNull(CatalogRankParser.findRank(doc, "675286385"))
        assertNull(CatalogRankParser.findRank(doc, "687366753"))
    }
}
