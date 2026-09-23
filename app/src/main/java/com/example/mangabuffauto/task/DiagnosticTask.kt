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
                function log(k, v) {
                    report[k] = v;
                }

                function run() {
                    const trigger = document.querySelector('.header-profile');
                    const tippyInstance = trigger ? trigger._tippy : null;
                    
                    log("PROFILE_TRIGGER_FOUND", !!trigger);
                    log("PROFILE_TIPPY_INSTANCE_FOUND", !!tippyInstance);
                    
                    if (tippyInstance) {
                        log("PROFILE_TIPPY_PLACEMENT", tippyInstance.props.placement);
                        log("PROFILE_TIPPY_INTERACTIVE", tippyInstance.props.interactive);
                        log("PROFILE_TIPPY_APPEND_TO", String(tippyInstance.props.appendTo));
                        
                        tippyInstance.show();
                        
                        setTimeout(() => {
                            const popper = tippyInstance.popper;
                            if (popper) {
                                log("PROFILE_POPPER_PARENT", popper.parentElement ? (popper.parentElement.tagName + (popper.parentElement.className ? "." + popper.parentElement.className.split(' ').join('.') : "")) : "null");
                                const rect = popper.getBoundingClientRect();
                                const style = window.getComputedStyle(popper);
                                log("PROFILE_POPPER_RECT", rect);
                                log("PROFILE_POPPER_HEIGHT_CSS", style.height);
                                log("PROFILE_POPPER_OVERFLOW", style.overflow);
                                
                                const content = popper.querySelector('.menu--profile');
                                log("PROFILE_MENU_FOUND", !!content);
                                if (content) {
                                    log("PROFILE_MENU_RECT", content.getBoundingClientRect());
                                    
                                    let hierarchy = [];
                                    let curr = content;
                                    while (curr && curr !== document.body) {
                                        const s = window.getComputedStyle(curr);
                                        hierarchy.push({
                                            tag: curr.tagName,
                                            cls: curr.className,
                                            overflow: s.overflow,
                                            height: s.height,
                                            maxH: s.maxHeight,
                                            pos: s.position,
                                            rect: curr.getBoundingClientRect()
                                        });
                                        curr = curr.parentElement;
                                    }
                                    log("HIERARCHY", hierarchy);
                                }
                            }
                            
                            log("WINDOW_INNER", window.innerWidth + "x" + window.innerHeight);
                            
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
