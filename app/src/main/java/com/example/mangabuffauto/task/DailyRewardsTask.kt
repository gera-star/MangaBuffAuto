package com.example.mangabuffauto.task

object DailyRewardsTask : AutomationTask {
    override val spec = AutomationTaskSpec(
        id = "mangabuff_daily_rewards",
        targetUrl = "https://mangabuff.ru/",
        timeoutMs = 60000L,
        maxRetries = 1,
        script = """
            (function() {
                var currentRunId = window.__mangaBuffRunId;
                var docId = window.__mangaBuffDocId;
                function finish(ok) {
                    if (window.__mangaBuffRunId !== currentRunId) return;
                    window.__mangaBuffTaskRunning = false;
                    AndroidBot.finish("mangabuff_daily_rewards", currentRunId, ok);
                }
                var buttons = document.querySelectorAll("button, a.btn");
                var found = 0;
                buttons.forEach(function(b) {
                    var t = (b.innerText || "").toLowerCase();
                    if ((t.indexOf("забрать") !== -1 || t.indexOf("бонус") !== -1) && b.offsetParent !== null) {
                        var r = b.getBoundingClientRect();
                        AndroidBot.tap((r.left+r.width/2)*devicePixelRatio, (r.top+r.height/2)*devicePixelRatio, currentRunId, docId);
                        found++;
                    }
                });
                AndroidBot.log("[Daily] Found: " + found);
                setTimeout(function(){ finish(true); }, 3000);
            })();
        """.trimIndent()
    )
}
