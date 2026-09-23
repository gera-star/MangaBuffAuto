package com.example.mangabuffauto.task

/**
 * Автосбор руды на странице шахты.
 * Селекторы взяты из предоставленного MangaBuffAutoDaily 1.5.0:
 * .main-mine__game-tap
 * .main-mine__game-hits-left
 * button.mine-shop__upgrade-btn
 * button.mine-shop__ore-change-btn
 */
object MineTask : AutomationTask {
    override val spec = AutomationTaskSpec(
        id = "mangabuff_mine",
        targetUrl = "https://mangabuff.ru/mine",
        timeoutMs = 30 * 60 * 1000L,
        maxRetries = 1,
        script = """
            (function() {
                'use strict';
                const RUN_ID = window.__mangaBuffRunId;
                const DOC_ID = window.__mangaBuffDocId;
                const TASK_ID = 'mangabuff_mine';
                let stopped = false;
                let timer = null;
                let clicks = 0;

                function log(message) {
                    try { AndroidBot.log('[MINE] ' + String(message)); } catch (_) {}
                }

                function active() {
                    return !stopped &&
                           window.__mangaBuffTaskRunning === true &&
                           window.__mangaBuffRunId === RUN_ID &&
                           window.__mangaBuffDocId === DOC_ID;
                }

                function clearTimer() {
                    if (timer !== null) {
                        clearTimeout(timer);
                        timer = null;
                    }
                }

                function finish(success, message) {
                    if (!active()) return;
                    stopped = true;
                    clearTimer();
                    window.__mangaBuffTaskRunning = false;
                    log(message);
                    try { AndroidBot.finish(TASK_ID, RUN_ID, success); } catch (_) {}
                }

                function visible(el) {
                    if (!el) return false;
                    const r = el.getBoundingClientRect();
                    const s = getComputedStyle(el);
                    return r.width > 0 && r.height > 0 &&
                           s.display !== 'none' && s.visibility !== 'hidden' &&
                           parseFloat(s.opacity || '1') > 0;
                }

                function hitsLeft() {
                    const el = document.querySelector('.main-mine__game-hits-left');
                    if (!el) return null;
                    const n = parseInt((el.innerText || el.textContent || '').replace(/[^0-9]/g, ''), 10);
                    return Number.isFinite(n) ? n : null;
                }

                function mineOnce() {
                    if (!active()) return;

                    try {
                        const left = hitsLeft();
                        if (left !== null && left <= 0) {
                            log('LIMIT_REACHED hits=0 clicks=' + clicks);
                            finish(true, 'Mine limit reached.');
                            return;
                        }

                        const button = document.querySelector('button.main-mine__game-tap');
                        if (!button || !visible(button)) {
                            log('MINE_BUTTON_NOT_READY');
                        } else if (button.disabled) {
                            log('MINE_BUTTON_DISABLED');
                        } else {
                            button.click();
                            clicks++;
                            if (clicks === 1 || clicks % 10 === 0) {
                                log('CLICK=' + clicks + (left !== null ? ' HITS_LEFT=' + left : ''));
                            }
                        }
                    } catch (e) {
                        log('MINE_ERROR=' + (e && (e.message || e.name) || e));
                    }

                    timer = setTimeout(mineOnce, 1000);
                }

                log('START run=' + RUN_ID + ' doc=' + DOC_ID);
                mineOnce();
            })();
        """.trimIndent()
    )
}
