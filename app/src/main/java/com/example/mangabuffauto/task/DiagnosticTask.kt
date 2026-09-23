package com.example.mangabuffauto.task

object DiagnosticTask : AutomationTask {
    override val spec = AutomationTaskSpec(
        id = "diagnostic_profile_menu",
        targetUrl = "https://mangabuff.ru/",
        timeoutMs = 30000L,
        maxRetries = 1,
        script = """
            (function() {
                var report = {};
                function log(k, v) { report[k] = v; }
                function run() {
                    const trigger = document.querySelector('.header-profile');
                    const tippyInstance = trigger ? trigger._tippy : null;
                    log("PROFILE_TRIGGER_FOUND", !!trigger);
                    log("PROFILE_TIPPY_INSTANCE_FOUND", !!tippyInstance);
                    if (tippyInstance) {
                        log("PROFILE_TIPPY_PLACEMENT", tippyInstance.props.placement);
                        tippyInstance.show();
                        setTimeout(() => {
                            var popper = tippyInstance.popper;
                            if (popper) {
                                log("PROFILE_MENU_FOUND", !!popper.querySelector('.menu--profile'));
                            }
                            AndroidBot.log("[DIAG_REPORT] " + JSON.stringify(report));
                            AndroidBot.finish("diagnostic_profile_menu", window.__mangaBuffRunId, true);
                        }, 1500);
                    } else {
                        AndroidBot.finish("diagnostic_profile_menu", window.__mangaBuffRunId, false);
                    }
                }
                run();
            })();
        """.trimIndent()
    )
    override fun isUrlExpected(url: String): Boolean = url.contains("mangabuff.ru")
}
