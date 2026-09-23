package com.example.mangabuffauto.task

object WebViewDiagnosticsTask : AutomationTask {
    override val spec = AutomationTaskSpec(
        id = "mangabuff_webview_diagnostics",
        targetUrl = "https://mangabuff.ru/",
        timeoutMs = 120000L,
        maxRetries = 0,
        script = """
            (function() {
                var currentRunId = window.__mangaBuffRunId;
                function log(x) { AndroidBot.log("[Diag] " + String(x)); }
                function finish(ok, msg) {
                    if (window.__mangaBuffRunId !== currentRunId) return;
                    window.__mangaBuffTaskRunning = false;
                    AndroidBot.finish("mangabuff_webview_diagnostics", currentRunId, ok);
                }
                function run() {
                    if (!window.__mangaBuffTaskRunning || window.__mangaBuffRunId !== currentRunId) return;
                    log("UA=" + navigator.userAgent);
                    log("SCREEN=" + screen.width + "x" + screen.height + " DPR=" + window.devicePixelRatio);
                    log("COOKIE=" + (document.cookie ? "YES" : "NO"));
                    setTimeout(function(){ finish(true, "Diag done"); }, 2000);
                }
                run();
            })();
        """.trimIndent()
    )
}
