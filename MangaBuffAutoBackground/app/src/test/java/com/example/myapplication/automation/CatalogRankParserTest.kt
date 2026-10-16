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
    fun ownerCardInventoryCanFindExactCatalogueIdWithoutConfusingInstanceId() {
        val doc = Jsoup.parse(
            """
            <div class="manga-cards__item-wrapper" data-rank="P">
              <div class="manga-cards__item" data-card-id="68318">
                <div class="lock-card-btn" data-id="675286385"></div>
                <div data-card-user-id="675286385"></div>
              </div>
            </div>
            """.trimIndent(),
            "https://mangabuff.ru/users/1539681/cards"
        )

        assertEquals("P", CatalogRankParser.findRank(doc, "68318"))
        assertEquals(true, CatalogRankParser.containsExactCard(doc, "68318"))
        assertEquals(false, CatalogRankParser.containsExactCard(doc, "675286385"))
    }

    @Test
    fun readsOnlyRankFiltersFromOwnerCardTabsInSiteOrder() {
        val doc = Jsoup.parse(
            """
            <div class="tabs__nav">
              <a href="https://mangabuff.ru/users/1539681/cards">Все</a>
              <a href="https://mangabuff.ru/users/1539681/cards?type=x">X</a>
              <a href="https://mangabuff.ru/users/1539681/cards?type=s">S</a>
              <a href="https://mangabuff.ru/users/1539681/cards?type=a">A</a>
              <a href="https://mangabuff.ru/users/1539681/cards?type=e">E</a>
              <a href="https://mangabuff.ru/users/1539681/cards?is_animated=1">GIF</a>
              <a href="https://mangabuff.ru/users/1539681/cards?is_fragment=1">A+</a>
              <a href="https://mangabuff.ru/users/1539681/cards?is_enchant=1">Заточенные</a>
              <a href="https://mangabuff.ru/users/1539681/cards?lock=1">Замок</a>
              <a href="https://mangabuff.ru/users/1539681/cards?sort=new">Новые</a>
              <a href="https://mangabuff.ru/users/1539681/cards?duplicates=1">Дубли</a>
              <a href="https://mangabuff.ru/users/1539681/cards?type=w">W</a>
            </div>
            """.trimIndent(),
            "https://mangabuff.ru/users/1539681/cards"
        )

        assertEquals(listOf("X", "S", "A", "E", "W"), CatalogRankParser.userCardRankFilters(doc))
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
