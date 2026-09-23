package com.example.mangabuffauto.task

/**
 * Просмотр доступной рекламы.
 * Подтверждено исходником MangaBuffAutoDaily 1.5.0:
 *   URL: https://mangabuff.ru/balance
 *   button.button--primary.user-quest__watch-ads-btn
 *   div[data-fullscreen-element-name='close-btn']
 *
 * Важный принцип: закрываем только найденный DOM-элемент рекламного overlay.
 * Нет координатного "угадывания" крестика.
 */
object WatchAdsTask : AutomationTask {
    override val spec = AutomationTaskSpec(
        id = "mangabuff_watch_ads",
        targetUrl = "https://mangabuff.ru/balance",
        timeoutMs = 10 * 60 * 1000L,
        maxRetries = 1,
        script = """
            (function() {
                'use strict';
                const RUN_ID = window.__mangaBuffRunId;
                const DOC_ID = window.__mangaBuffDocId;
                const TASK_ID = 'mangabuff_watch_ads';
                const MAX_ADS = 3;
                let timer = null;
                let stopped = false;

                function log(message) {
                    try { AndroidBot.log('[ADS] ' + String(message)); } catch (_) {}
                }
                function active() {
                    return !stopped &&
                           window.__mangaBuffTaskRunning === true &&
                           window.__mangaBuffRunId === RUN_ID &&
                           window.__mangaBuffDocId === DOC_ID;
                }
                function later(fn, ms) {
                    timer = setTimeout(function() { timer = null; fn(); }, ms);
                }
                function finish(ok, message) {
                    if (!active()) return;
                    stopped = true;
                    if (timer !== null) { clearTimeout(timer); timer = null; }
                    window.__mangaBuffTaskRunning = false;
                    log(message);
                    try { AndroidBot.finish(TASK_ID, RUN_ID, ok); } catch (_) {}
                }
                function visible(el) {
                    if (!el) return false;
                    const r = el.getBoundingClientRect();
                    const s = getComputedStyle(el);
                    return r.width > 0 && r.height > 0 &&
                           s.display !== 'none' && s.visibility !== 'hidden' &&
                           parseFloat(s.opacity || '1') > 0;
                }
                function adButton() {
                    const buttons = Array.from(document.querySelectorAll(
                        'button.button--primary.user-quest__watch-ads-btn, button.user-quest__watch-ads-btn'
                    ));
                    return buttons.find(function(b) {
                        return visible(b) && !b.disabled;
                    }) || null;
                }
                function closeButton() {
                    const el = document.querySelector("div[data-fullscreen-element-name='close-btn']");
                    if (el && visible(el)) return el;

                    const candidates = Array.from(document.querySelectorAll(
                        'button[aria-label], [role="button"][aria-label], button[title], [role="button"][title]'
                    ));
                    for (const c of candidates) {
                        if (!visible(c)) continue;
                        const text = ((c.getAttribute('aria-label') || '') + ' ' +
                                      (c.getAttribute('title') || '')).toLowerCase();
                        if (text.indexOf('close') !== -1 || text.indexOf('закры') !== -1 ||
                            text.indexOf('dismiss') !== -1) return c;
                    }
                    return null;
                }
                function pageSaysLimit() {
                    const text = (document.body ? document.body.innerText : '').toLowerCase();
                    return text.indexOf('лимит') !== -1 ||
                           text.indexOf('нельзя смотреть рекламу больше 3 раз') !== -1;
                }
                function waitForAdButton(start, callback) {
                    if (!active()) return;
                    const btn = adButton();
                    if (btn) { callback(btn); return; }
                    if (pageSaysLimit()) {
                        log('LIMIT_REACHED');
                        try { AndroidBot.reportLimit(TASK_ID, RUN_ID); } catch (_) {}
                        return;
                    }
                    if (Date.now() - start >= 15_000) {
                        finish(false, 'Ad button not found.');
                        return;
                    }
                    later(function() { waitForAdButton(start, callback); }, 500);
                }
                function waitForClose(start, callback) {
                    if (!active()) return;
                    const close = closeButton();
                    if (close) { callback(close); return; }
                    if (Date.now() - start >= 90_000) {
                        finish(false, 'Ad close button not found.');
                        return;
                    }
                    later(function() { waitForClose(start, callback); }, 500);
                }
                function closeAd(attempt, callback) {
                    if (!active()) return;
                    const close = closeButton();
                    if (!close) {
                        if (attempt >= 4) { finish(false, 'Ad close button disappeared.'); return; }
                        later(function() { closeAd(attempt + 1, callback); }, 1000);
                        return;
                    }
                    try {
                        close.click();
                        log('CLOSE_CLICK attempt=' + attempt);
                    } catch (e) {
                        log('CLOSE_CLICK_ERROR=' + (e && (e.message || e.name) || e));
                    }
                    later(function() {
                        if (!active()) return;
                        const stillOpen = closeButton();
                        if (!stillOpen) {
                            callback(true);
                        } else if (attempt < 4) {
                            closeAd(attempt + 1, callback);
                        } else {
                            callback(false);
                        }
                    }, 1500);
                }
                function watchOne(index) {
                    if (!active()) return;
                    if (index > MAX_ADS) {
                        finish(true, 'ADS_FINISHED count=' + MAX_ADS);
                        return;
                    }
                    log('WAIT_AD_BUTTON ' + index + '/' + MAX_ADS);
                    waitForAdButton(Date.now(), function(btn) {
                        if (!active()) return;
                        btn.scrollIntoView({block: 'center', inline: 'nearest'});
                        later(function() {
                            if (!active()) return;
                            const fresh = adButton();
                            if (!fresh) { finish(false, 'Ad button became unavailable.'); return; }
                            log('AD_CLICK ' + index + '/' + MAX_ADS);
                            try { fresh.click(); } catch (e) {
                                finish(false, 'Ad button click failed.');
                                return;
                            }
                            // The ad itself is normally ~20–21 seconds. Start the timer
                            // immediately after the click, not after the close button appears.
                            // Otherwise the old logic could wait another 35s and miss the close
                            // element after the ad had already completed.
                            const openedAt = Date.now();
                            const MIN_WATCH_MS = 22_000;
                            const MAX_CLOSE_WAIT_MS = 45_000;
                            const waitForAdCompletion = function() {
                                if (!active()) return;
                                const elapsed = Date.now() - openedAt;
                                if (elapsed < MIN_WATCH_MS) {
                                    later(waitForAdCompletion, Math.min(MIN_WATCH_MS - elapsed, 500));
                                    return;
                                }

                                const close = closeButton();
                                if (close) {
                                    closeAd(1, function(closed) {
                                        if (!active()) return;
                                        if (!closed) {
                                            finish(false, 'Ad overlay could not be closed.');
                                            return;
                                        }
                                        log('AD_COMPLETED ' + index + '/' + MAX_ADS + ' elapsed=' + (Date.now() - openedAt));
                                        later(function() { watchOne(index + 1); }, 2000);
                                    });
                                    return;
                                }

                                // Some ad variants expose the close control a little later.
                                // Do not wait forever: the task has its own native timeout.
                                if (elapsed >= MAX_CLOSE_WAIT_MS) {
                                    finish(false, 'Ad finished but close button was not found.');
                                    return;
                                }
                                later(waitForAdCompletion, 500);
                            };
                            waitForAdCompletion();
                        }, 500);
                    });
                }
                log('START run=' + RUN_ID + ' doc=' + DOC_ID);
                // We do not start with a fixed 65s timer. The close control is the source of truth.
                watchOne(1);
            })();
        """.trimIndent()
    )
}
