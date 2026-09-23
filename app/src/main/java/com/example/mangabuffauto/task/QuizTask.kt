package com.example.mangabuffauto.task

object QuizTask : AutomationTask {
    override val spec = AutomationTaskSpec(
        id = "mangabuff_quiz",
        targetUrl = "https://mangabuff.ru/quiz",
        timeoutMs = 60_000L,
        maxRetries = 0,
        script = """
            (function() {
                const RUN_ID = window.__mangaBuffRunId;
                const DOC_ID = window.__mangaBuffDocId;
                const TASK_ID = 'mangabuff_quiz';
                function log(m) { try { AndroidBot.log('[QUIZ] ' + String(m)); } catch (_) {} }
                function finish() {
                    if (window.__mangaBuffRunId !== RUN_ID || window.__mangaBuffDocId !== DOC_ID) return;
                    window.__mangaBuffTaskRunning = false;
                    log('TODO: correct answer source is not implemented.');
                    try { AndroidBot.finish(TASK_ID, RUN_ID, false); } catch (_) {}
                }
                log('TODO: quiz answer source is not implemented for WebView.');
                setTimeout(finish, 500);
            })();
        """.trimIndent()
    )
}
