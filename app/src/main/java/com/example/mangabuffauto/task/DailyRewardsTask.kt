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
                function log(x) { AndroidBot.log("[Daily] " + String(x)); }
                function finish(ok, msg) {
                    if (window.__mangaBuffRunId !== currentRunId) return;
                    window.__mangaBuffTaskRunning = false;
                    AndroidBot.finish("mangabuff_daily_rewards", currentRunId, ok);
                }
                function getElementCenter(el) {
                    var rect = el.getBoundingClientRect();
                    return {
                        x: (rect.left + rect.width / 2) * window.devicePixelRatio,
                        y: (rect.top + rect.height / 2) * window.devicePixelRatio
                    };
                }

                function run() {
                    if (!window.__mangaBuffTaskRunning || window.__mangaBuffRunId !== currentRunId) return;
                    var found = 0;
                    var buttons = document.querySelectorAll("button, a.btn");
                    buttons.forEach(function(b) {
                        var t = (b.innerText || "").toLowerCase();
                        if (t.indexOf("забрать") !== -1 || t.indexOf("бонус") !== -1) {
                            if (b.offsetParent !== null) {
                                var coords = getElementCenter(b);
                                AndroidBot.tap(coords.x, coords.y, currentRunId, docId);
                                found++;
                            }
                        }
                    });
                    log("Found: " + found);
                    setTimeout(function(){ finish(true, "Done"); }, 3000);
                }
                run();
            })();
        """.trimIndent()
    )
}
