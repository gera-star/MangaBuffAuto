package com.example.mangabuffauto.automation

/**
 * Читает текущие значения статистики прямо из DOM MangaBuff.
 *
 * Скрипт безопасный: ничего не нажимает, не отправляет и не меняет на сайте.
 * Если конкретного блока на текущей странице нет, возвращается null, а
 * AutomationEngine сохраняет последнее известное значение.
 */
object StatsInspector {
    const val SCRIPT = """
        (function() {
            var docId = window.__mangaBuffDocId ||
                (typeof currentDocId !== 'undefined' ? currentDocId : null);

            function getInt(selector) {
                var el = document.querySelector(selector);
                if (!el) return null;

                var text = el.innerText || el.textContent || '';
                var match = text.replace(/\s+/g, '').match(/\d+/);
                return match ? parseInt(match[0], 10) : null;
            }

            function getFraction(selector) {
                var el = document.querySelector(selector);
                if (!el) return { cur: null, max: null };

                var b = el.querySelector('b');
                var text = b ? (b.innerText || b.textContent || '') :
                    (el.innerText || el.textContent || '');

                var match = text.match(/(\d+)\s*\/\s*(\d+)/);
                if (!match) return { cur: null, max: null };

                return {
                    cur: parseInt(match[1], 10),
                    max: parseInt(match[2], 10)
                };
            }

            function getCards() {
                var el = document.querySelector('.wallet-panel__drop-text');
                if (!el) return { cur: null, max: null };

                var text = el.innerText || el.textContent || '';
                var match = text.match(/(\d+)\s+из\s+(\d+)/i);
                if (!match) return { cur: null, max: null };

                return {
                    cur: parseInt(match[1], 10),
                    max: parseInt(match[2], 10)
                };
            }

            function getAds() {
                var btn = document.querySelector('.user-quest__watch-ads-btn');
                if (btn) {
                    var remaining = parseInt(btn.getAttribute('data-count'), 10);
                    if (!isNaN(remaining)) {
                        return {
                            cur: Math.max(0, 3 - remaining),
                            max: 3
                        };
                    }
                }

                var bodyText = document.body ? document.body.innerText : '';
                if (bodyText.indexOf('больше 3 раз') !== -1) {
                    return { cur: 3, max: 3 };
                }

                return { cur: null, max: null };
            }

            var ads = getAds();
            var cards = getCards();

            var comments = getFraction(
                '.wallet-panel_stat[data-tooltip*="комментарии"], ' +
                '.wallet-panel__stat[data-tooltip*="комментарии"]'
            );

            var stats = {
                diamonds:
                    getInt('.wallet-panel_balance') ||
                    getInt('.wallet-panel_amount') ||
                    getInt('.wallet-panel__amount'),

                mineOre:
                    (location.pathname === '/mine' ||
                     document.querySelector('main.main-mine'))
                        ? getInt('.main-mine__header_score-count')
                        : null,

                cards: cards,
                ads: ads,
                comments: comments
            };

            var payload = JSON.stringify({
                diamonds: stats.diamonds,
                mineOre: stats.mineOre,
                cardsCurrent: stats.cards.cur,
                cardsMax: stats.cards.max,
                adsCurrent: stats.ads.cur,
                adsMax: stats.ads.max,
                commentsCurrent: stats.comments.cur,
                commentsMax: stats.comments.max
            });

            if (window.AndroidBot &&
                typeof window.AndroidBot.updateStats === 'function') {
                window.AndroidBot.updateStats(payload, docId);
            }
        })();
    """
}
