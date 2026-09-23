package com.example.mangabuffauto.task

object BalanceInspectTask : AutomationTask {
    override val spec = AutomationTaskSpec(
        id = "mangabuff_balance_inspect",
        targetUrl = "https://mangabuff.ru/balance",
        timeoutMs = 60000L,
        maxRetries = 0,
        script = """
            (function() {
                var currentRunId = window.__mangaBuffRunId;
                function finish(ok) {
                    if (window.__mangaBuffRunId !== currentRunId) return;
                    window.__mangaBuffTaskRunning = false;
                    AndroidBot.finish("mangabuff_balance_inspect", currentRunId, ok);
                }
                AndroidBot.log("[Inspect] URL=" + location.href);
                AndroidBot.log("[Inspect] TITLE=" + document.title);
                var bodyText = document.body ? document.body.innerText : "";
                AndroidBot.log("[Inspect] BODY_LEN=" + bodyText.length);
                var m = bodyText.match(/(\d+)\s*алмаз/i);
                if (m) AndroidBot.log("[Inspect] ALMAZ=" + m[1]);
                setTimeout(function(){ finish(true); }, 2000);
            })();
        """.trimIndent()
    )
}
