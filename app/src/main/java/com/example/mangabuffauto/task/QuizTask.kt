package com.example.mangabuffauto.task

/**
 * Quiz пока не включён в UI/расписание.
 * AutoDaily получает correct_text из сетевого ответа /quiz/ через Selenium
 * DevTools. WebView-реализация такого перехвата в текущем проекте ещё не
 * подтверждена, поэтому намеренно не выдаём угадывание ответа за готовую функцию.
 */
object QuizTask : AutomationTask {
    override val spec = AutomationTaskSpec(
        id = "mangabuff_quiz",
        targetUrl = "https://mangabuff.ru/quiz",
        timeoutMs = 60_000L,
        maxRetries = 0,
        script = """
            (function() {
                'use strict';
                const RUN_ID = window.__mangaBuffRunId;
                const DOC_ID = window.__mangaBuffDocId;
                const TASK_ID = 'mangabuff_quiz';
                function log(m) { try { AndroidBot.log('[QUIZ] ' + String(m)); } catch (_) {} }
                function finish() {
                    if (window.__mangaBuffRunId !== RUN_ID || window.__mangaBuffDocId !== DOC_ID) return;
                    window.__mangaBuffTaskRunning = false;
                    log('TODO: answer source is not implemented; quiz task is not executed.');
                    try { AndroidBot.finish(TASK_ID, RUN_ID, false); } catch (_) {}
                }
                log('TODO: .quiz__answer-item.button is confirmed, but correct_text acquisition is not implemented for WebView.');
                setTimeout(finish, 500);
            })();
        """.trimIndent()
    )
}
