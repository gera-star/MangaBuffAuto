package com.example.mangabuffauto.task

/**
 * Одноразовая попытка забрать доступную награду чата.
 * Селектор подтверждён в MangaBuffAutoDaily 1.5.0:
 * button.chat-arena__get-coins-btn
 * Никаких сетевых запросов к Yandex Metrica здесь нет.
 */
object ChatDiamondTask : AutomationTask {
    override val spec = AutomationTaskSpec(
        id = "mangabuff_chat_reward",
        targetUrl = "https://mangabuff.ru/chat",
        timeoutMs = 90_000L,
        maxRetries = 0,
        script = """
            (function() {
                'use strict';
                const RUN_ID = window.__mangaBuffRunId;
                const DOC_ID = window.__mangaBuffDocId;
                const TASK_ID = 'mangabuff_chat_reward';

                function log(message) {
                    try { AndroidBot.log('[CHAT] ' + String(message)); } catch (_) {}
                }
                function active() {
                    return window.__mangaBuffTaskRunning === true &&
                           window.__mangaBuffRunId === RUN_ID &&
                           window.__mangaBuffDocId === DOC_ID;
                }
                function finish(ok, message) {
                    if (!active()) return;
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
                function run() {
                    if (!active()) return;
                    const btn = document.querySelector('button.chat-arena__get-coins-btn');
                    if (!btn || !visible(btn) || btn.disabled) {
                        finish(true, 'CHAT_REWARD_NOT_AVAILABLE');
                        return;
                    }
                    try {
                        btn.scrollIntoView({block: 'center', inline: 'nearest'});
                        btn.click();
                        log('CLICK_1');
                        setTimeout(function() {
                            if (!active()) return;
                            const again = document.querySelector('button.chat-arena__get-coins-btn');
                            if (again && visible(again) && !again.disabled) {
                                again.click();
                                log('CLICK_2');
                            }
                            setTimeout(function() {
                                if (active()) finish(true, 'CHAT_REWARD_CHECK_FINISHED');
                            }, 1500);
                        }, 1000);
                    } catch (e) {
                        log('CHAT_ERROR=' + (e && (e.message || e.name) || e));
                        finish(false, 'Chat reward click failed.');
                    }
                }
                setTimeout(run, 1200);
            })();
        """.trimIndent()
    )
}
