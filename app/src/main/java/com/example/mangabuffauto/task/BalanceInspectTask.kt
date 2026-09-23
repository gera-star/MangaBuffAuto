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
                function log(x) { AndroidBot.log("[Inspect] " + String(x)); }
                function finish(ok, msg) {
                    if (window.__mangaBuffRunId !== currentRunId) return;
                    window.__mangaBuffTaskRunning = false;
                    AndroidBot.finish("mangabuff_balance_inspect", currentRunId, ok);
                }
                function run() {
                    if (!window.__mangaBuffTaskRunning || window.__mangaBuffRunId !== currentRunId) return;
                    log("URL=" + location.href);
                    log("TITLE=" + document.title);
                    
                    var bodyText = document.body ? document.body.innerText : "";
                    log("BODY_LEN=" + bodyText.length);
                    
                    // Simple collection of visible text patterns
                    var m = bodyText.match(/(\d+)\s*алмаз/i);
                    if (m) log("ALMAZ=" + m[1]);
                    
                    setTimeout(function(){ finish(true, "Inspection complete"); }, 2000);
                }
                run();
            })();
        """.trimIndent()
    )
}
