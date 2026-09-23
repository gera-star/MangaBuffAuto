package com.example.mangabuffauto.task

object ReadTask : AutomationTask {

    private const val HOME_URL = "https://mangabuff.ru/"
    private const val CATALOG_URL = "https://mangabuff.ru/manga"

    private const val MAX_DAILY_COMMENTS = 10
    private const val MIN_COMMENT_INTERVAL = 8
    private const val MAX_COMMENT_INTERVAL = 20

    private val COMMENTS = listOf(
        "Интересная глава.",
        "Хорошая глава.",
        "Сюжет становится интереснее.",
        "Интересно развивается история.",
        "Вот это поворот.",
        "Неожиданно получилось.",
        "Очень атмосферно.",
        "Хорошая подача.",
        "Интересный момент.",
        "Сильная сцена.",
        "Неплохой эпизод.",
        "Понравился этот момент.",
        "Интересная задумка.",
        "Хорошо сделано.",
        "Динамично получилось.",
        "Есть над чем подумать.",
        "Любопытный поворот.",
        "Хорошая атмосфера.",
        "Понравилась эта сцена.",
        "Сюжет держит внимание.",
        "Необычно получилось.",
        "Хорошая динамика.",
        "Интересный ход автора.",
        "Глава читается легко.",
        "События развиваются бодро.",
        "Неплохое развитие событий.",
        "Персонажи становятся интереснее.",
        "Очень любопытно.",
        "Здесь было интересно.",
        "Хороший темп.",
        "Атмосферная сцена.",
        "Интересно наблюдать за героями.",
        "Сильный эпизод.",
        "Хорошо раскрыта история.",
        "Интересная сцена.",
        "Получилось живо.",
        "Хорошая работа с персонажами.",
        "Сюжетная интрига работает.",
        "Понравилось развитие событий.",
        "Неожиданный момент.",
        "Хороший эпизод с героями.",
        "Непредсказуемо вышло.",
        "Глава получилась динамичной.",
        "Интересная подача событий.",
        "Хорошо передана атмосфера.",
        "Здесь много интересных деталей.",
        "Сюжет развивается бодро.",
        "Понравился общий настрой.",
        "Хороший поворот сюжета.",
        "Сильный момент.",
        "Хорошо продвинули сюжет.",
        "Интересная часть истории.",
        "Понравилась динамика.",
        "Неожиданно, но интересно.",
        "Сюжет приятно удивил.",
        "Хорошая детализация.",
        "Персонажи ведут себя интересно.",
        "Интересно наблюдать за развитием истории.",
        "Хорошо передано настроение.",
        "Действительно интересный эпизод.",
        "Сюжет набирает обороты.",
        "Этот момент запомнился.",
        "Интересное развитие.",
        "Сцена отлично вписалась в сюжет.",
        "Здесь есть интрига.",
        "Хороший баланс событий.",
        "Интересно придумано.",
        "Здесь действительно интересно.",
        "События не стоят на месте.",
        "Поворот получился неожиданным.",
        "Хорошо развивается линия героя.",
        "Интересно, как всё складывается.",
        "Глава получилась насыщенной.",
        "Неплохо закрутили сюжет.",
        "Здесь чувствуется напряжение.",
        "Интересный конфликт.",
        "Очень неплохо.",
        "Хорошая сцена.",
        "Здесь стало интереснее.",
        "Сюжетная линия радует.",
        "Понравился этот эпизод.",
        "Необычный ход.",
        "Хорошая история.",
        "Интересно получилось с героями.",
        "Хорошо продумано.",
        "Интрига становится интереснее.",
        "Понравилось, как раскрыли героя.",
        "Хороший момент для сюжета.",
        "Интересная деталь.",
        "Герой раскрылся с новой стороны.",
        "Хорошо развивается история.",
        "Сцена получилась атмосферной.",
        "Интересный поворот событий.",
        "Глава оставила впечатление.",
        "Хорошо показаны персонажи.",
        "Сюжет получился насыщенным.",
        "Интересное развитие событий.",
        "Здесь есть настроение.",
        "Хорошая сцена с героями.",
        "Интересно наблюдать за сюжетом.",
        "Хороший эпизод.",
        "События развиваются интересно.",
        "Понравилась эта часть истории.",
        "Сильная подача.",
        "Хорошая интрига.",
        "Интересный конфликт персонажей.",
        "Очень атмосферная глава.",
        "Хорошо получилось.",
        "Сюжет движется бодро.",
        "Интересный фрагмент.",
        "Поворот сюжета удивил.",
        "Понравилось развитие героя.",
        "Хорошая работа над сценой.",
        "Интересная ситуация.",
        "Неплохой ход.",
        "Здесь всё довольно интересно.",
        "Хорошо держится напряжение.",
        "Понравилась атмосфера.",
        "Интересный момент для сюжета.",
        "События получились насыщенными.",
        "Хорошо раскрываются персонажи.",
        "Любопытное развитие истории.",
        "Глава получилась интересной.",
        "Неплохая динамика.",
        "Хороший сюжетный ход.",
        "Интересно развивается линия героя.",
        "Сцена получилась сильной.",
        "Хорошая детализация персонажей.",
        "Здесь много интересного.",
        "Понравилась подача.",
        "Интрига держится отлично.",
        "Интересная задумка автора.",
        "Хорошо раскрыта сцена.",
        "Сюжет выглядит интересно.",
        "Неожиданное развитие событий.",
        "Понравился этот поворот.",
        "Атмосфера отлично передана.",
        "Хорошая сцена для сюжета.",
        "Интересная линия персонажа.",
        "Насыщенный эпизод.",
        "Здесь всё развивается довольно бодро.",
        "Хорошо получилось с атмосферой.",
        "Интересно наблюдать за событиями.",
        "Понравился этот фрагмент.",
        "Хороший сюжетный момент.",
        "Необычное развитие.",
        "Интересная сцена с героями.",
        "Хорошо показано настроение.",
        "Сюжет продолжает удивлять.",
        "Понравилась эта история.",
        "Интересный эпизод.",
        "Хорошо развивается конфликт.",
        "Сильная сюжетная сцена.",
        "Интересно закрутили события.",
        "Хорошая подача персонажей.",
        "Здесь есть интересные детали.",
        "Поворот вышел неожиданным.",
        "Понравился общий стиль.",
        "Очень интересная сцена.",
        "Хорошая динамика событий.",
        "Сюжет развивается плавно.",
        "Интересно раскрывается история.",
        "Понравился этот сюжетный ход.",
        "Хорошо показаны отношения героев.",
        "Интересный момент с персонажем.",
        "Атмосфера получилась отличной.",
        "Неплохое развитие сюжета.",
        "Хорошо держит внимание.",
        "Любопытная сцена.",
        "Интересно получилось.",
        "Понравилась динамика сцены.",
        "Сюжетная линия развивается интересно.",
        "Хорошая работа с атмосферой.",
        "Неожиданный сюжетный ход.",
        "Интересно раскрыли персонажа.",
        "Сильная атмосфера.",
        "Хороший темп событий.",
        "Понравилась эта сюжетная деталь.",
        "Интересная часть главы.",
        "Хорошо продуман эпизод.",
        "Сюжет получился живым.",
        "Интересная сцена получилась.",
        "Хорошая история и атмосфера.",
        "Любопытный момент.",
        "Хорошо раскрывается сюжет.",
        "Интересный персонаж.",
        "События получились неожиданными.",
        "Атмосфера здесь на высоте.",
        "Хороший сюжетный эпизод.",
        "Интересно развивается конфликт.",
        "Хорошо получилось с персонажами.",
        "Интересное сочетание событий.",
        "Сюжет уверенно развивается.",
        "Хорошая эмоциональная сцена.",
        "Интересный момент главы.",
        "Неплохая история.",
        "Хорошо переданы эмоции.",
        "Интересная динамика между героями.",
        "Понравился сюжетный баланс.",
        "Сильный поворот.",
        "Хорошо раскрыли ситуацию.",
        "Интересно развивается действие.",
        "Понравилась эта часть.",
        "Хорошая сцена с интригой.",
        "Сюжет стал ещё интереснее.",
        "Интересный вариант развития событий.",
        "Хорошая работа автора.",
        "Атмосфера хорошо чувствуется.",
        "Интересно получилось с сюжетом.",
        "Хороший момент истории.",
        "Неожиданно и интересно.",
        "Интересная подача истории.",
        "Хорошо раскрыта интрига.",
        "Понравилось настроение этой сцены.",
        "Интересная динамика.",
        "Сюжет держит интерес.",
        "Хорошо показан герой.",
        "Интересное развитие персонажа.",
        "Насыщенная сцена.",
        "Хороший сюжетный поворот.",
        "Интересно развивается история героев.",
        "Понравилась атмосфера эпизода.",
        "Хорошо выстроена сцена.",
        "Любопытное развитие событий.",
        "Интересный сюжетный момент.",
        "Хорошо получилось с динамикой.",
        "Понравилась работа с персонажами.",
        "Интересная деталь сюжета.",
        "Хороший эпизод получился.",
        "Сюжетная сцена удалась.",
        "Хорошая эмоциональная подача.",
        "Необычная сцена.",
        "Понравился этот сюжет.",
        "Хорошо развивается действие.",
        "Интересная атмосфера.",
        "События раскрываются постепенно.",
        "Хороший поворот истории.",
        "Понравилось, как развивается сюжет.",
        "Хорошая сцена с напряжением.",
        "Интересный ход.",
        "Сюжетная линия стала интереснее.",
        "Хорошо показана ситуация.",
        "Понравился этот момент истории.",
        "Интересное развитие персонажей.",
        "Хорошая глава получилась.",
        "Атмосфера отлично работает.",
        "Неплохой сюжетный момент.",
        "Интересно раскрывается конфликт.",
        "Хорошая динамика главы.",
        "Понравилась сцена.",
        "Неожиданная деталь.",
        "Интересная история.",
        "Хорошо передана динамика.",
        "Сюжетная интрига держит внимание.",
        "Понравилось, как показали героя.",
        "Хороший эмоциональный момент.",
        "Интересная задумка сцены.",
        "Сюжет развивается интересно.",
        "Хорошо получилось с героями.",
        "Любопытный сюжетный ход.",
        "Интересная глава получилась.",
        "Хорошая атмосфера истории.",
        "Сильный эпизод получился.",
        "Необычный поворот событий.",
        "Хорошо раскрыта история героя.",
        "Интересно наблюдать за развитием.",
        "Понравилась эта деталь.",
        "Хорошая сцена и атмосфера.",
        "Интересно закручено.",
        "Сюжетная часть получилась сильной.",
        "Хорошо держится интрига.",
        "Понравилось развитие персонажей.",
        "Интересная сцена для сюжета.",
        "Хорошая подача событий.",
        "Неожиданное решение.",
        "Атмосфера этой части понравилась.",
        "Хорошо раскрываются события.",
        "Понравилась динамика истории.",
        "Интересно развивается линия сюжета.",
        "Хорошая сцена с эмоциями.",
        "Любопытный эпизод.",
        "Сюжет получился увлекательным.",
        "Понравился этот ход.",
        "Хорошая работа над персонажами.",
        "Интересная деталь в сюжете.",
        "Неожиданно раскрылась ситуация.",
        "Интересный конфликт получился.",
        "Хороший момент для развития сюжета.",
        "Сюжетная линия выглядит интересно.",
        "Понравилась сцена с героями.",
        "Хорошо раскрыли персонажа.",
        "Интересная подача сцены.",
        "Насыщенная и интересная глава.",
        "Хороший сюжетный темп.",
        "Хорошо показана атмосфера.",
        "Необычно раскрыли ситуацию.",
        "Хорошая динамика персонажей.",
        "Хорошая сцена получилась.",
        "Любопытный момент сюжета.",
        "Понравилась эта глава.",
        "Сюжетная сцена интересная.",
        "Хорошо раскрыта идея.",
        "Интересный эпизод с героями.",
        "Хорошая подача сюжета.",
        "Интересное развитие истории.",
        "Неожиданная сцена.",
        "Хорошо развивается линия персонажа.",
        "Понравилось, как всё показано.",
        "Интересная ситуация для сюжета.",
        "Хорошая работа с эмоциями.",
        "Сюжетная интрига получилась интересной.",
        "Хороший эпизод с атмосферой.",
        "Необычное решение автора.",
        "Понравилось развитие истории.",
        "Интересный момент с героями.",
        "Сюжет развивается довольно бодро.",
        "Понравилась эта сюжетная линия.",
        "Интересная подача персонажей.",
        "Хорошо раскрыта атмосфера.",
        "Любопытный поворот сюжета.",
    )

    override val spec = AutomationTaskSpec(
        id = "mangabuff_read",
        targetUrl = HOME_URL,
        script = generateScript()
    )

    private fun generateScript(): String {

        val commentsJs = COMMENTS.joinToString(
            prefix = "[",
            postfix = "]"
        ) {
            "\"" + it
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r") + "\""
        }

        return """
            (function() {
                'use strict';

                const TASK_ID = 'mangabuff_read';
                const CATALOG_URL = '$CATALOG_URL';

                const MIN_COMMENT_INTERVAL = $MIN_COMMENT_INTERVAL;
                const MAX_COMMENT_INTERVAL = $MAX_COMMENT_INTERVAL;
                const MAX_DAILY_COMMENTS = $MAX_DAILY_COMMENTS;

                const COMMENTS = $commentsJs;

                window.__mangaBuffTaskRunning = true;

                function log(message) {
                    try {
                        if (
                            window.AndroidBot &&
                            typeof window.AndroidBot.log === 'function'
                        ) {
                            window.AndroidBot.log('[READ] ' + message);
                        } else {
                            console.log('[READ] ' + message);
                        }
                    } catch (e) {
                        console.log('[READ] ' + message);
                    }
                }

                function finish(success) {
                    try {
                        window.__mangaBuffTaskRunning = false;

                        if (window.__mangaBuffFallbackScrollTimer) {
                            cancelAnimationFrame(window.__mangaBuffFallbackScrollTimer);
                            window.__mangaBuffFallbackScrollTimer = null;
                        }

                        if (window.__mangaBuffReaderTimer) {
                            clearInterval(
                                window.__mangaBuffReaderTimer
                            );
                            window.__mangaBuffReaderTimer = null;
                        }

                        if (window.__mangaBuffSpeedTimer) {
                            clearInterval(
                                window.__mangaBuffSpeedTimer
                            );
                            window.__mangaBuffSpeedTimer = null;
                        }

                        window.__mangaBuffNativeReadTick = null;
                        window.__mangaBuffNativeMaintenanceAt = 0;
                        window.__mangaBuffBackgroundMode = false;
                        stopReaderMaintenanceTimer();
                        if (window.__mbTippyFixTimer1) { clearTimeout(window.__mbTippyFixTimer1); window.__mbTippyFixTimer1 = null; }
                        if (window.__mbTippyFixTimer2) { clearTimeout(window.__mbTippyFixTimer2); window.__mbTippyFixTimer2 = null; }
                        window.__mbTippyFixTimersScheduled = false;

                        if (
                            window.AndroidBot &&
                            typeof window.AndroidBot.finish === 'function'
                        ) {
                            window.AndroidBot.finish(
                                TASK_ID,
                                window.__mangaBuffRunId || '',
                                !!success
                            );
                        }

                    } catch (e) {
                        console.error('[READ] finish error', e);
                    }
                }

                function fail(message) {
                    log('ERROR: ' + message);
                    finish(false);
                }

                function absoluteUrl(href) {
                    try {
                        const raw = String(href || '').trim();
                        if (!raw) return null;

                        const parsed = new URL(raw, location.href);
                        let path = parsed.pathname || '/';

                        // MangaBuff иногда отдаёт у кнопки "Продолжить"
                        // относительный href без /manga/:
                        //   vozvrashchenie.../1/178
                        // На странице /manga/<slug> браузер в таком случае
                        // получает /manga/<slug>/..., но если скрипт/DOM уже
                        // находится в другом контексте, сайт может получить
                        // /<slug>/1/178 и вернуть 404.
                        // Для URL главы всегда нормализуем путь к реальному
                        // формату MangaBuff: /manga/<slug>/<volume>/<chapter>.
                        if (!path.startsWith('/manga/')) {
                            const parts = path
                                .split('/')
                                .filter(function(part) { return part.length > 0; });

                            if (parts.length >= 3 && /^\d+$/.test(parts[parts.length - 1])) {
                                path = '/manga/' + parts.join('/');
                            }
                        }

                        parsed.pathname = path;
                        return parsed.href;
                    } catch (e) {
                        log(
                            'URL NORMALIZE ERROR: ' +
                            (e && e.message || e)
                        );
                        return null;
                    }
                }

                function getPath() {
                    return window.location.pathname || '/';
                }

                function isHomePage() {
                    const path = getPath();

                    return (
                        path === '/' ||
                        path === ''
                    );
                }

                function isCatalogPage() {
                    const path = getPath();

                    return (
                        path === '/manga' ||
                        path === '/manga/'
                    );
                }

                function isMangaPage() {
                    return /^\/manga\/[^/]+\/?$/.test(
                        getPath()
                    );
                }

                function isReaderPage() {
                    return /^\/manga\/[^/]+\/[^/]+\/[^/]+\/?$/.test(
                        getPath()
                    );
                }

                /*
                 * ============================================================
                 * SAFETY
                 * ============================================================
                 */

                function detectCaptcha() {

                    const body = (
                        document.body?.innerText ||
                        ''
                    ).toLowerCase();

                    const title = (
                        document.title ||
                        ''
                    ).toLowerCase();

                    const text =
                        body + ' ' + title;

                    return (
                        text.includes('captcha') ||
                        text.includes('cloudflare') ||
                        text.includes('проверка безопасности') ||
                        text.includes('я не робот')
                    );
                }

                function detectBlocking() {

                    const text = (
                        document.body?.innerText ||
                        ''
                    ).toLowerCase();

                    return (
                        text.includes('403 forbidden') ||
                        text.includes('access denied')
                    );
                }

                function notifyCaptcha() {

                    try {
                        if (
                            window.AndroidBot &&
                            typeof window.AndroidBot.alertCaptcha ===
                            'function'
                        ) {
                            window.AndroidBot.alertCaptcha();
                        }
                    } catch (e) {
                        console.error(
                            '[READ] captcha notify error',
                            e
                        );
                    }
                }

                function checkSafety() {

                    if (
                        detectCaptcha() ||
                        detectBlocking()
                    ) {
                        log(
                            'SAFETY: CAPTCHA/Cloudflare/block detected'
                        );

                        notifyCaptcha();
                        finish(false);

                        return false;
                    }

                    return true;
                }

                /*
                 * ============================================================
                 * HOME
                 * ============================================================
                 */

                function findLastRead() {

                    const items =
                        Array.from(
                            document.querySelectorAll(
                                'a.last-read__item[href]'
                            )
                        );

                    if (!items.length) {
                        return null;
                    }

                    for (const item of items) {

                        const href =
                            item.getAttribute('href') || '';

                        if (
                            /^\/manga\/[^/]+\/[^/]+\/[^/]+(?:\/)?(?:[?#].*)?$/
                            .test(href)
                        ) {
                            return item;
                        }
                    }

                    return null;
                }

                function continueFromHome() {

                    const item = findLastRead();

                    if (!item) {
                        log(
                            'HOME: .last-read__item not found'
                        );
                        return false;
                    }

                    const href =
                        item.getAttribute('href');

                    if (!href) {
                        log(
                            'HOME: last-read item has no href'
                        );
                        return false;
                    }

                    const absolute =
                        absoluteUrl(href);

                    if (!absolute) {
                        log(
                            'HOME: invalid last-read href'
                        );
                        return false;
                    }

                    const name =
                        item.querySelector(
                            '.last-read__name'
                        )?.innerText?.trim() || '';

                    const chapterText =
                        item.querySelector(
                            '.last-read__chapters'
                        )?.innerText?.trim() || '';

                    log(
                        'HOME: last read -> ' +
                        (name || 'unknown') +
                        ' | ' +
                        (chapterText || 'unknown')
                    );

                    log(
                        'HOME: opening -> ' +
                        absolute
                    );

                    if (
                        window.location.href !==
                        absolute
                    ) {
                        window.location.href =
                            absolute;
                    }

                    return true;
                }

                /*
                 * ============================================================
                 * MANGA PAGE
                 * ============================================================
                 */

                function findReaderStartLink() {

                    // На странице манги есть конкретная кнопка .read-btn.
                    // Не ищем кнопку по всему DOM: это могло выбирать другой
                    // элемент со словом "Продолжить".
                    const readButtons =
                        Array.from(
                            document.querySelectorAll(
                                'a.read-btn[href], a[href].read-btn'
                            )
                        );

                    for (const element of readButtons) {

                        const text = (
                            element.innerText ||
                            element.textContent ||
                            ''
                        )
                            .replace(/\s+/g, ' ')
                            .trim()
                            .toLowerCase();

                        if (
                            !text.includes('читать') &&
                            !text.includes('продолжить')
                        ) {
                            continue;
                        }

                        const href =
                            element.getAttribute('href') ||
                            element.href ||
                            '';

                        if (!href) {
                            continue;
                        }

                        const absolute = absoluteUrl(href);

                        if (absolute) {
                            return {
                                element: element,
                                href: absolute,
                                text: text
                            };
                        }
                    }

                    // Fallback для старой/изменённой разметки сайта.
                    const candidates =
                        Array.from(
                            document.querySelectorAll(
                                'a[href], button'
                            )
                        );

                    for (const element of candidates) {

                        const text = (
                            element.innerText ||
                            element.textContent ||
                            ''
                        )
                            .replace(/\s+/g, ' ')
                            .trim()
                            .toLowerCase();

                        if (
                            !text.includes('читать') &&
                            !text.includes('продолжить')
                        ) {
                            continue;
                        }

                        const href =
                            element.getAttribute('href') ||
                            element.href ||
                            '';

                        if (!href) {
                            continue;
                        }

                        const absolute = absoluteUrl(href);

                        if (absolute) {
                            return {
                                element: element,
                                href: absolute,
                                text: text
                            };
                        }
                    }

                    return null;
                }

                function openReaderFromMangaPage() {

                    const target =
                        findReaderStartLink();

                    if (!target) {

                        log(
                            'MANGA: "Читать"/"Продолжить" not found'
                        );

                        return false;
                    }

                    log(
                        'MANGA: opening "' +
                        target.text +
                        '" -> ' +
                        target.href
                    );

                    // Для "Продолжить" используем URL самой кнопки напрямую.
                    // Это надёжнее, чем element.click(), если обработчик сайта
                    // перехватывает клик или кнопка создана динамически.
                    try {
                        window.location.href = target.href;
                        return true;
                    } catch (e) {
                        log(
                            'MANGA: location navigation failed, trying click'
                        );
                    }

                    try {
                        target.element.click();
                        return true;
                    } catch (e) {
                        log(
                            'MANGA: click failed -> ' +
                            (e && e.message || e)
                        );
                    }

                    return false;
                }

                /*
                 * ============================================================
                 * CATALOG
                 * ============================================================
                 */

                function findCatalogFiltersTrigger() {

                    const elements =
                        Array.from(
                            document.querySelectorAll(
                                'button, a, [role="button"]'
                            )
                        );

                    for (const element of elements) {

                        const cls =
                            typeof element.className ===
                            'string'
                                ? element.className.toLowerCase()
                                : '';

                        if (
                            cls.includes('filter') ||
                            cls.includes('filters')
                        ) {
                            return element;
                        }
                    }

                    for (const element of elements) {

                        const text = (
                            element.innerText ||
                            element.textContent ||
                            ''
                        )
                            .replace(/\s+/g, ' ')
                            .trim()
                            .toLowerCase();

                        if (
                            text === 'фильтры' ||
                            text === 'фильтр' ||
                            text.includes('фильтры')
                        ) {
                            return element;
                        }
                    }

                    return null;
                }

                function findHideReadCheckbox() {

                    return (
                        document.querySelector(
                            'input[type="checkbox"][name="hide_read"]'
                        ) ||
                        document.querySelector(
                            'input[name="hide_read"]'
                        )
                    );
                }

                function findApplyButton() {

                    const elements =
                        Array.from(
                            document.querySelectorAll(
                                'button, input[type="submit"], a'
                            )
                        );

                    for (const element of elements) {

                        const text = (
                            element.innerText ||
                            element.value ||
                            element.textContent ||
                            ''
                        )
                            .replace(/\s+/g, ' ')
                            .trim()
                            .toLowerCase();

                        if (text === 'применить') {
                            return element;
                        }
                    }

                    for (const element of elements) {

                        const cls =
                            typeof element.className ===
                            'string'
                                ? element.className.toLowerCase()
                                : '';

                        if (
                            cls.includes('apply') ||
                            cls.includes('filter-submit')
                        ) {
                            return element;
                        }
                    }

                    return null;
                }

                function setCheckboxChecked(
                    checkbox,
                    checked
                ) {

                    if (!checkbox) {
                        return false;
                    }

                    if (
                        checkbox.checked ===
                        checked
                    ) {
                        return true;
                    }

                    checkbox.click();

                    if (
                        checkbox.checked !==
                        checked
                    ) {

                        const setter =
                            Object.getOwnPropertyDescriptor(
                                HTMLInputElement.prototype,
                                'checked'
                            )?.set;

                        if (setter) {
                            setter.call(
                                checkbox,
                                checked
                            );
                        } else {
                            checkbox.checked =
                                checked;
                        }

                        checkbox.dispatchEvent(
                            new Event(
                                'input',
                                {
                                    bubbles: true
                                }
                            )
                        );

                        checkbox.dispatchEvent(
                            new Event(
                                'change',
                                {
                                    bubbles: true
                                }
                            )
                        );
                    }

                    return (
                        checkbox.checked ===
                        checked
                    );
                }

                /*
                 * ============================================================
                 * ПОИСК ПОСЛЕДНЕЙ КАРТОЧКИ
                 * ============================================================
                 *
                 * ВАЖНО:
                 *
                 * После hide_read=1 больше не кликаем "Применить".
                 *
                 * Выбираем последнюю доступную карточку каталога.
                 *
                 * Реальный DOM рейтинга:
                 *
                 * <span class="cards__rating cards__rating--green">
                 *
                 * Поэтому сначала используем реальные .cards__item,
                 * затем проверяем наличие .cards__rating--green.
                 * ============================================================
                 */

                function findLastMangaCard() {

                    const cards =
                        Array.from(
                            document.querySelectorAll(
                                '.cards__item, .cards__item-link, article[class*="card"], [class*="cards__item"]'
                            )
                        );

                    log(
                        'CATALOG: cards found=' +
                        cards.length
                    );

                    if (!cards.length) {
                        return null;
                    }

                    const normalized = [];
                    const seen = new Set();

                    for (const card of cards) {
                        let root = card;
                        let link = card.matches('a[href*="/manga/"]')
                            ? card
                            : card.querySelector('a[href*="/manga/"]');

                        if (!link) {
                            const hrefHolder = card.querySelector('[href*="/manga/"]');
                            if (hrefHolder) {
                                link = hrefHolder;
                            }
                        }

                        if (!link) {
                            continue;
                        }

                        const href =
                            link.getAttribute('href') ||
                            link.href ||
                            '';

                        if (!href) {
                            continue;
                        }

                        const absolute = absoluteUrl(href);
                        if (!absolute) {
                            continue;
                        }

                        let parsed;
                        try {
                            parsed = new URL(absolute, location.origin);
                        } catch (e) {
                            continue;
                        }

                        if (!parsed.pathname.startsWith('/manga/')) {
                            continue;
                        }

                        root = link.closest('.cards__item') || card;

                        if (seen.has(absolute)) {
                            continue;
                        }

                        seen.add(absolute);
                        normalized.push({
                            card: root,
                            link: link,
                            href: absolute
                        });
                    }

                    log(
                        'CATALOG: valid manga cards=' +
                        normalized.length
                    );

                    if (!normalized.length) {
                        return null;
                    }

                    const greenCards =
                        normalized.filter(function(item) {
                            return !!item.card.querySelector(
                                '.cards__rating.cards__rating--green'
                            );
                        });

                    log(
                        'CATALOG: green cards=' +
                        greenCards.length
                    );

                    const source =
                        greenCards.length
                            ? greenCards
                            : normalized;

                    return source[source.length - 1];
                }

                function openLastMangaFromCatalog() {

                    const result = findLastMangaCard();

                    if (!result) {
                        log(
                            'CATALOG: no readable manga card yet'
                        );
                        return false;
                    }

                    if (window.__mangaBuffCatalogMangaOpened) {
                        return true;
                    }

                    const name =
                        result.card.querySelector(
                            '.cards__title, .cards__name, .card__title'
                        )?.innerText?.trim() ||
                        result.card.querySelector('img[alt]')?.getAttribute('alt') ||
                        'unknown';

                    const rating =
                        result.card.querySelector(
                            '.cards__rating'
                        )?.innerText?.trim() ||
                        '';

                    log(
                        'CATALOG: selected manga -> ' +
                        name +
                        (rating ? ' | rating=' + rating : '')
                    );

                    log(
                        'CATALOG: opening -> ' +
                        result.href
                    );

                    window.__mangaBuffCatalogMangaOpened = true;

                    try {
                        result.link.click();
                    } catch (e) {
                        log(
                            'CATALOG: click failed, using location.href'
                        );
                        window.location.href = result.href;
                    }

                    setTimeout(function() {
                        if (
                            isCatalogPage() &&
                            window.__mangaBuffTaskRunning
                        ) {
                            window.__mangaBuffCatalogMangaOpened = false;
                            log('CATALOG: click did not navigate, retrying');
                        }
                    }, 1500);

                    return true;
                }

                function catalogTick() {

                    if (!checkSafety()) {
                        return;
                    }

                    if (!isCatalogPage()) {
                        return;
                    }

                    /*
                     * Если hide_read=1 уже установлен,
                     * НИКОГДА больше не нажимаем "Применить".
                     */
                    const params =
                        new URLSearchParams(
                            window.location.search
                        );

                    const hideRead =
                        params.get('hide_read');

                    if (hideRead === '1') {

                        log(
                            'CATALOG: hide_read=1 active'
                        );

                        /*
                         * Даём каталогу дорисоваться.
                         */
                        if (
                            openLastMangaFromCatalog()
                        ) {
                            return;
                        }

                        setTimeout(
                            function() {

                                if (
                                    isCatalogPage() &&
                                    window.__mangaBuffTaskRunning
                                ) {
                                    openLastMangaFromCatalog();
                                }

                            },
                            1000
                        );

                        return;
                    }

                    /*
                     * Обычный /manga.
                     */

                    const checkbox =
                        findHideReadCheckbox();

                    if (!checkbox) {

                        const filters =
                            findCatalogFiltersTrigger();

                        if (filters) {

                            if (
                                !window.__mangaBuffFiltersOpened
                            ) {

                                window.__mangaBuffFiltersOpened =
                                    true;

                                log(
                                    'CATALOG: opening filters'
                                );

                                filters.click();
                            }

                            setTimeout(
                                function() {
                                    catalogTick();
                                },
                                700
                            );

                            return;
                        }

                        log(
                            'CATALOG: hide_read checkbox not found yet'
                        );

                        setTimeout(
                            function() {
                                catalogTick();
                            },
                            700
                        );

                        return;
                    }

                    log(
                        'CATALOG: hide_read checkbox found'
                    );

                    if (
                        !setCheckboxChecked(
                            checkbox,
                            true
                        )
                    ) {

                        log(
                            'CATALOG: failed to enable hide_read'
                        );

                        finish(false);
                        return;
                    }

                    log(
                        'CATALOG: "Скрыть прочитанные" checked'
                    );

                    setTimeout(
                        function() {

                            /*
                             * Повторная проверка.
                             * Если сайт уже поменял URL —
                             * ничего больше не делаем.
                             */
                            if (
                                !isCatalogPage()
                            ) {
                                return;
                            }

                            const currentParams =
                                new URLSearchParams(
                                    window.location.search
                                );

                            if (
                                currentParams.get(
                                    'hide_read'
                                ) === '1'
                            ) {
                                catalogTick();
                                return;
                            }

                            const apply =
                                findApplyButton();

                            if (!apply) {

                                log(
                                    'CATALOG: "Применить" not found'
                                );

                                setTimeout(
                                    function() {
                                        catalogTick();
                                    },
                                    700
                                );

                                return;
                            }

                            log(
                                'CATALOG: clicking "Применить"'
                            );

                            apply.click();

                        },
                        500
                    );
                }

                /*
                 * ============================================================
                 * READER URL
                 * ============================================================
                 */

                function parseReaderUrl(url) {

                    try {

                        const parsed =
                            new URL(
                                url,
                                location.origin
                            );

                        const parts =
                            parsed.pathname
                                .split('/')
                                .filter(Boolean);

                        if (
                            parts.length < 4 ||
                            parts[0] !== 'manga'
                        ) {
                            return null;
                        }

                        const slug =
                            parts[1];

                        const volume =
                            Number(parts[2]);

                        const chapter =
                            Number(parts[3]);

                        if (
                            !slug ||
                            !Number.isFinite(volume) ||
                            !Number.isFinite(chapter)
                        ) {
                            return null;
                        }

                        return {
                            slug: slug,
                            volume: volume,
                            chapter: chapter
                        };

                    } catch (e) {
                        return null;
                    }
                }

                function getCurrentReader() {

                    return parseReaderUrl(
                        window.location.href
                    );
                }

                /*
                 * ============================================================
                 * NEXT CHAPTER
                 * ============================================================
                 */

                function findNextChapterLink() {

                    const current =
                        getCurrentReader();

                    if (!current) {

                        log(
                            'NEXT: current URL is not reader URL'
                        );

                        return null;
                    }

                    // MangaBuff Helper uses this real reader control. Prefer it
                    // when present, then fall back to the broader chapter-link search.
                    const directNext = document.querySelector('a.button[rel="next"]');
                    if (directNext) {
                        const directHref = directNext.getAttribute('href');
                        const directAbsolute = absoluteUrl(directHref);
                        const directParsed = directAbsolute
                            ? parseReaderUrl(directAbsolute)
                            : null;

                        if (directParsed &&
                            directParsed.slug === current.slug &&
                            directParsed.chapter > current.chapter) {
                            log(
                                'NEXT: direct -> ' +
                                directParsed.chapter +
                                ' | ' +
                                directAbsolute
                            );
                            return directAbsolute;
                        }
                    }

                    const candidates = [];

                    const links =
                        Array.from(
                            document.querySelectorAll(
                                'a[href]'
                            )
                        );

                    for (const link of links) {

                        const href =
                            link.getAttribute('href');

                        if (!href) {
                            continue;
                        }

                        const absolute =
                            absoluteUrl(href);

                        if (!absolute) {
                            continue;
                        }

                        const parsed =
                            parseReaderUrl(
                                absolute
                            );

                        if (!parsed) {
                            continue;
                        }

                        if (
                            parsed.slug !==
                            current.slug
                        ) {
                            continue;
                        }

                        if (
                            parsed.chapter <=
                            current.chapter
                        ) {
                            continue;
                        }

                        const text = (
                            link.innerText ||
                            link.textContent ||
                            ''
                        )
                            .replace(/\s+/g, ' ')
                            .trim()
                            .toLowerCase();

                        candidates.push({
                            href: absolute,
                            chapter: parsed.chapter,
                            text: text
                        });
                    }

                    if (!candidates.length) {

                        log(
                            'NEXT: no next chapter found for chapter=' +
                            current.chapter
                        );

                        return null;
                    }

                    candidates.sort(
                        function(a, b) {
                            return (
                                a.chapter -
                                b.chapter
                            );
                        }
                    );

                    const preferred =
                        candidates.find(
                            function(item) {

                                return (
                                    item.text.includes(
                                        'след. глава'
                                    ) ||
                                    item.text.includes(
                                        'следующая глава'
                                    )
                                );
                            }
                        );

                    const result =
                        preferred ||
                        candidates[0];

                    log(
                        'NEXT: ' +
                        current.chapter +
                        ' -> ' +
                        result.chapter +
                        ' | ' +
                        result.href
                    );

                    return result.href;
                }

                function openNextChapter() {

                    const href =
                        findNextChapterLink();

                    if (!href) {

                        log(
                            'NEXT: no next chapter'
                        );

                        finish(false);

                        return false;
                    }

                    log(
                        'NEXT: opening -> ' +
                        href
                    );

                    window.location.href =
                        href;

                    return true;
                }

                /*
                 * ============================================================
                 * END OF MANGA
                 * ============================================================
                 */

                function findNotifyReleaseElement() {

                    const elements =
                        Array.from(
                            document.querySelectorAll(
                                'button, a, [role="button"]'
                            )
                        );

                    for (const element of elements) {

                        const text = (
                            element.innerText ||
                            element.textContent ||
                            ''
                        )
                            .replace(/\s+/g, ' ')
                            .trim()
                            .toLowerCase();

                        if (
                            text.includes(
                                'уведомить о выходе'
                            )
                        ) {
                            return element;
                        }
                    }

                    return null;
                }

                function isMangaFinished() {
                    return !!findNotifyReleaseElement();
                }

                /*
                 * ============================================================
                 * BOOKMARK
                 * ============================================================
                 */

                function findBookmarkButton() {

                    return (
                        document.querySelector(
                            'button.reader-menu__item--bookmark'
                        ) ||
                        document.querySelector(
                            '.reader-menu__item--bookmark'
                        )
                    );
                }

                function findBookmarkMenu() {

                    return document.querySelector(
                        '.menu.menu--bookmark'
                    );
                }

                function findReadButton() {

                    const menu =
                        findBookmarkMenu();

                    if (menu) {

                        const exact =
                            menu.querySelector(
                                'button[data-folder-id="3"]'
                            );

                        if (exact) {
                            return exact;
                        }
                    }

                    return document.querySelector(
                        '.menu--bookmark button[data-folder-id="3"]'
                    );
                }

                function markMangaAsRead() {

                    return new Promise(
                        function(resolve) {

                            log(
                                'FINISH: opening bookmark menu'
                            );

                            const bookmark =
                                findBookmarkButton();

                            if (!bookmark) {

                                log(
                                    'FINISH: bookmark button not found'
                                );

                                resolve(false);
                                return;
                            }

                            if (!findBookmarkMenu()) {
                                bookmark.click();
                            }

                            setTimeout(
                                function() {

                                    const readButton =
                                        findReadButton();

                                    if (!readButton) {

                                        log(
                                            'FINISH: "Прочитано" not found'
                                        );

                                        resolve(false);
                                        return;
                                    }

                                    log(
                                        'FINISH: clicking "Прочитано"'
                                    );

                                    readButton.click();

                                    setTimeout(
                                        function() {

                                            const check =
                                                findReadButton();

                                            if (
                                                check &&
                                                check.classList.contains(
                                                    'menu__item--active'
                                                )
                                            ) {

                                                log(
                                                    'FINISH: "Прочитано" confirmed'
                                                );

                                                resolve(true);

                                            } else {

                                                log(
                                                    'FINISH: reopening bookmark menu'
                                                );

                                                const again =
                                                    findBookmarkButton();

                                                if (again) {
                                                    again.click();
                                                }

                                                setTimeout(
                                                    function() {

                                                        const second =
                                                            findReadButton();

                                                        if (
                                                            second &&
                                                            second.classList.contains(
                                                                'menu__item--active'
                                                            )
                                                        ) {

                                                            log(
                                                                'FINISH: "Прочитано" confirmed after reopen'
                                                            );

                                                            resolve(true);

                                                        } else {

                                                            log(
                                                                'FINISH: could not confirm "Прочитано"'
                                                            );

                                                            resolve(false);
                                                        }

                                                    },
                                                    700
                                                );
                                            }

                                        },
                                        900
                                    );

                                },
                                500
                            );
                        }
                    );
                }

                function handleFinishedManga() {

                    if (
                        window.__mangaBuffFinishedMangaFlowStarted
                    ) {
                        return true;
                    }

                    if (!atBottom()) {
                        return false;
                    }

                    const next =
                        findNextChapterLink();

                    if (next) {
                        return false;
                    }

                    if (!isMangaFinished()) {
                        return false;
                    }

                    window.__mangaBuffFinishedMangaFlowStarted =
                        true;

                    log(
                        'FINISH: end of manga confirmed'
                    );

                    markMangaAsRead()
                        .then(
                            function(success) {

                                if (!success) {
                                    finish(false);
                                    return;
                                }

                                log(
                                    'FINISH: manga marked as read'
                                );

                                setTimeout(
                                    function() {

                                        log(
                                            'FINISH: opening catalog'
                                        );

                                        window.location.href =
                                            CATALOG_URL + '?hide_read=1';

                                    },
                                    1200
                                );
                            }
                        )
                        .catch(
                            function(error) {

                                log(
                                    'FINISH: error -> ' +
                                    (
                                        error?.message ||
                                        String(error)
                                    )
                                );

                                finish(false);
                            }
                        );

                    return true;
                }

                /*
                 * ============================================================
                 * AUTOSCROLL
                 * ============================================================
                 */

                function findAutoScrollButton() {

                    return (
                        document.querySelector('button.reader-autoscroll-btn') ||
                        document.querySelector('[class*="reader-autoscroll-btn"]')
                    );
                }

                function isSiteAutoScrollActive(button) {

                    if (!button) {
                        return false;
                    }

                    const ariaPressed =
                        button.getAttribute('aria-pressed');

                    if (ariaPressed === 'true') {
                        return true;
                    }

                    if (ariaPressed === 'false') {
                        return false;
                    }

                    const className =
                        typeof button.className === 'string'
                            ? button.className.toLowerCase()
                            : '';

                    if (
                        className.includes('active') ||
                        className.includes('running') ||
                        className.includes('playing') ||
                        className.includes('enabled')
                    ) {
                        return true;
                    }

                    const stopIcon =
                        button.querySelector(
                            '.reader-autoscroll-icon-stop, [class*="autoscroll-icon-stop"]'
                        );

                    const playIcon =
                        button.querySelector(
                            '.reader-autoscroll-icon-play, [class*="autoscroll-icon-play"]'
                        );

                    if (stopIcon && !playIcon) {
                        const style = window.getComputedStyle(stopIcon);
                        if (style.display !== 'none' && style.visibility !== 'hidden') {
                            return true;
                        }
                    }

                    if (playIcon && !stopIcon) {
                        const style = window.getComputedStyle(playIcon);
                        if (style.display !== 'none' && style.visibility !== 'hidden') {
                            return false;
                        }
                    }

                    const text =
                        (button.innerText || button.textContent || '')
                            .trim()
                            .toLowerCase();

                    if (text.includes('останов') || text.includes('stop')) {
                        return true;
                    }

                    return false;
                }

                function stopFallbackScroll() {

                    if (window.__mangaBuffFallbackScrollTimer) {
                        cancelAnimationFrame(window.__mangaBuffFallbackScrollTimer);
                        window.__mangaBuffFallbackScrollTimer = null;
                    }

                    window.__mangaBuffFallbackScrollStarted = false;
                    window.__mangaBuffFallbackScrollLastFrame = 0;
                }

                function startFallbackScroll(forceStrong) {

                    if (window.__mangaBuffFallbackScrollStarted) {
                        return;
                    }

                    window.__mangaBuffFallbackScrollStarted = true;

                    log(
                        forceStrong
                            ? 'SCROLL: bot smooth scroll started'
                            : 'SCROLL: bot smooth assist started'
                    );

                    /*
                     * Плавный непрерывный скролл через requestAnimationFrame.
                     * Раньше использовался scrollBy(..., behavior:"smooth")
                     * каждые несколько сотен миллисекунд. Такие анимации
                     * накладывались друг на друга и движение было рваным.
                     *
                     * Скорость увеличена ровно примерно в 3 раза относительно
                     * предыдущего fallback-режима.
                     *
                     * 1x / 2x из панели дополнительно умножают скорость.
                     */
                    const basePixelsPerSecond = forceStrong ? 280 : 280;

                    function frame(now) {

                        if (!window.__mangaBuffTaskRunning) {
                            stopFallbackScroll();
                            return;
                        }

                        if (!isReaderPage()) {
                            window.__mangaBuffFallbackScrollTimer =
                                requestAnimationFrame(frame);
                            return;
                        }

                        if (atBottom()) {
                            window.__mangaBuffFallbackScrollTimer =
                                requestAnimationFrame(frame);
                            return;
                        }

                        const previous =
                            Number(window.__mangaBuffFallbackScrollLastFrame || 0);

                        window.__mangaBuffFallbackScrollLastFrame = now;

                        const deltaMs = previous > 0
                            ? Math.min(50, Math.max(0, now - previous))
                            : 16.67;

                        const speedMultiplier =
                            Number(window.__mangaBuffSpeedMultiplier || 1);

                        const pixelsPerSecond =
                            basePixelsPerSecond *
                            (speedMultiplier >= 1.5 ? 2 : 1);

                        const pixels =
                            pixelsPerSecond * deltaMs / 1000;

                        if (pixels > 0) {
                            window.scrollBy(0, pixels);
                        }

                        window.__mangaBuffFallbackScrollTimer =
                            requestAnimationFrame(frame);
                    }

                    window.__mangaBuffFallbackScrollLastFrame = 0;
                    window.__mangaBuffFallbackScrollTimer =
                        requestAnimationFrame(frame);
                }

                function ensureSiteAutoScroll() {

                    if (!isReaderPage()) {
                        return false;
                    }

                    const button = findAutoScrollButton();

                    if (!button) {
                        return false;
                    }

                    /*
                     * Важно: не кликаем кнопку повторно на каждом тике.
                     * Если у сайта нет aria-pressed/active-класса, повторный
                     * click может выключить уже работающий автоскролл.
                     * Поэтому на документ делаем один стартовый click.
                     */
                    if (!window.__mangaBuffSiteAutoScrollClicked) {
                        try {
                            button.click();
                            window.__mangaBuffSiteAutoScrollClicked = true;
                            log('SCROLL: site autoscroll click -> ON');
                        } catch (e) {
                            log('SCROLL: site autoscroll click error');
                            return false;
                        }
                    }

                    const active = isSiteAutoScrollActive(button);
                    window.__mangaBuffSiteAutoScrollActive = active;
                    window.__mangaBuffAutoScrollStarted = true;

                    return true;
                }

                function ensureAutoScroll() {

                    if (!isReaderPage()) {
                        return;
                    }

                    const siteActive = ensureSiteAutoScroll();

                    /*
                     * Штатный автоскролл сайта включаем всегда, когда кнопка
                     * доступна. Параллельно оставляем небольшой bot-assist,
                     * чтобы движение страницы не зависело только от внутреннего
                     * таймера сайта. Если кнопки сайта нет — включаем сильный
                     * fallback, который полностью ведёт страницу.
                     */
                    if (siteActive) {
                        if (!window.__mangaBuffFallbackScrollStarted) {
                            startFallbackScroll(false);
                        }
                        return;
                    }

                    if (!window.__mangaBuffFallbackScrollDelayStarted) {
                        window.__mangaBuffFallbackScrollDelayStarted = true;

                        setTimeout(function() {

                            if (
                                !isReaderPage() ||
                                !window.__mangaBuffTaskRunning
                            ) {
                                return;
                            }

                            const realButton = findAutoScrollButton();

                            if (realButton) {
                                ensureSiteAutoScroll();
                                if (isSiteAutoScrollActive(realButton)) {
                                    if (!window.__mangaBuffFallbackScrollStarted) {
                                        startFallbackScroll(false);
                                    }
                                    return;
                                }
                            }

                            startFallbackScroll(true);

                        }, 900);
                    }
                }

                /*
                 * ============================================================
                 * SPEED
                 * ============================================================
                 */

                function normalizeSpeedText(text) {

                    return (
                        text || ''
                    )
                        .trim()
                        .toLowerCase()
                        .replace(/х/g, 'x')
                        .replace(/\s+/g, '');
                }

                function findSpeedButton(speed) {

                    const wanted = speed === 1 ? '1x' : '2x';

                    const selectors = [
                        '.reader-autoscroll-speed button',
                        '.reader-autoscroll__speed button',
                        '.reader-autoscroll-speed *',
                        '.reader-autoscroll__speed *',
                        'button'
                    ];

                    const elements = [];
                    const seen = new Set();

                    for (const selector of selectors) {
                        for (const element of document.querySelectorAll(selector)) {
                            if (seen.has(element)) continue;
                            seen.add(element);

                            const text = normalizeSpeedText(
                                element.innerText ||
                                element.textContent ||
                                ''
                            );

                            if (text === wanted) {
                                elements.push(element);
                            }
                        }
                    }

                    return elements.length ? elements[0] : null;
                }

                function selectScrollSpeed(speed) {

                    const button = findSpeedButton(speed);

                    if (!button) {
                        log('SCROLL: speed button not found -> ' + speed + 'x');
                        return false;
                    }

                    try {
                        button.click();
                        window.__mangaBuffSelectedSpeed = speed;
                        window.__mangaBuffSpeedSelectedForDocument = true;
                        log('SCROLL: speed selected ' + speed + 'x');
                        return true;
                    } catch (e) {
                        log('SCROLL: speed click error -> ' + speed + 'x');
                        return false;
                    }
                }

                window.__mangaBuffSetSpeed = function(speed) {
                    const normalized = Number(speed) >= 1.5 ? 2 : 1;
                    window.__mangaBuffSpeedMultiplier = normalized;
                    window.__mangaBuffSpeedSelectedForDocument = false;

                    if (window.__mangaBuffFallbackScrollTimer) {
                        stopFallbackScroll();
                        if (isReaderPage()) {
                            const button = findAutoScrollButton();
                            if (button) {
                                startFallbackScroll(false);
                            } else {
                                startFallbackScroll(true);
                            }
                        }
                    }

                    return selectScrollSpeed(normalized);
                };

                function ensureScrollSpeed() {

                    if (!isReaderPage()) {
                        return;
                    }

                    const wanted =
                        Number(window.__mangaBuffSpeedMultiplier || 1) >= 1.5
                            ? 2
                            : 1;

                    if (
                        window.__mangaBuffSpeedSelectedForDocument &&
                        window.__mangaBuffSelectedSpeed === wanted
                    ) {
                        return;
                    }

                    selectScrollSpeed(wanted);
                }

                /*
                 * ============================================================
                 * BOTTOM
                 * ============================================================
                 */

                function atBottom() {

                    const scrollTop =
                        window.scrollY ||
                        document.documentElement.scrollTop ||
                        document.body.scrollTop ||
                        0;

                    const viewport =
                        window.innerHeight ||
                        document.documentElement.clientHeight ||
                        0;

                    const height =
                        Math.max(
                            document.body.scrollHeight || 0,
                            document.documentElement.scrollHeight || 0
                        );

                    const distanceToBottom = height - (scrollTop + viewport);

                    // MangaBuffAutoDaily 1.5.0 additionally checked the real
                    // reader footer. Prefer that signal when it is present,
                    // while keeping the old scroll-height fallback for pages
                    // whose footer is rendered differently.
                    const footer = document.querySelector('div.reader__footer');
                    if (footer) {
                        const rect = footer.getBoundingClientRect();
                        const style = window.getComputedStyle(footer);
                        const footerVisible = rect.width > 0 &&
                            rect.height > 0 &&
                            style.display !== 'none' &&
                            style.visibility !== 'hidden';

                        if (footerVisible && distanceToBottom <= 100) {
                            return true;
                        }
                    }

                    return distanceToBottom <= 80;
                }

                /*
                 * ============================================================
                 * COMMENTS
                 * ============================================================
                 */

                function randomCommentInterval() {

                    return (
                        MIN_COMMENT_INTERVAL +
                        Math.floor(
                            Math.random() *
                            (
                                MAX_COMMENT_INTERVAL -
                                MIN_COMMENT_INTERVAL +
                                1
                            )
                        )
                    );
                }

                function getChapterKey() {

                    const data =
                        getCurrentReader();

                    if (!data) {
                        return '';
                    }

                    return (
                        data.slug +
                        ':' +
                        data.volume +
                        ':' +
                        data.chapter
                    );
                }

                function maybePostComment() {

                    if (
                        window.__mangaBuffCommentInProgress ||
                        window.__mangaBuffCommentHandled
                    ) {
                        return;
                    }

                    const today =
                        new Date()
                            .toISOString()
                            .slice(0, 10);

                    let state = {
                        date: today,
                        dailyComments: 0,
                        chaptersSinceComment: 0,
                        nextCommentAfter:
                            randomCommentInterval()
                    };

                    try {

                        const saved =
                            JSON.parse(
                                window.localStorage.getItem(
                                    'mangabuff_read_comment_state'
                                ) ||
                                'null'
                            );

                        if (
                            saved &&
                            saved.date === today
                        ) {

                            state = saved;

                        } else {

                            state.nextCommentAfter =
                                randomCommentInterval();
                        }

                    } catch (e) {

                        state.nextCommentAfter =
                            randomCommentInterval();
                    }

                    const chapter =
                        getChapterKey();

                    if (!chapter) {
                        return;
                    }

                    if (
                        window.__mangaBuffCountedChapter ===
                        chapter
                    ) {
                        return;
                    }

                    window.__mangaBuffCountedChapter =
                        chapter;

                    state.chaptersSinceComment =
                        (
                            state.chaptersSinceComment ||
                            0
                        ) + 1;

                    window.localStorage.setItem(
                        'mangabuff_read_comment_state',
                        JSON.stringify(state)
                    );

                    if (
                        (state.dailyComments || 0) >=
                        MAX_DAILY_COMMENTS
                    ) {

                        log(
                            'COMMENT: daily limit reached'
                        );

                        return;
                    }

                    const required =
                        Math.max(
                            MIN_COMMENT_INTERVAL,
                            state.nextCommentAfter ||
                            MIN_COMMENT_INTERVAL
                        );

                    if (
                        state.chaptersSinceComment <
                        required
                    ) {

                        log(
                            'COMMENT: waiting ' +
                            state.chaptersSinceComment +
                            '/' +
                            required
                        );

                        return;
                    }

                    const icon =
                        document.querySelector(
                            'i.icon-comment'
                        );

                    if (!icon) {

                        log(
                            'COMMENT: comment icon not found'
                        );

                        return;
                    }

                    window.__mangaBuffCommentInProgress =
                        true;

                    const opener =
                        icon.closest(
                            'button,a,[role="button"],.reader-menu__item'
                        );

                    if (opener) {
                        opener.click();
                    } else {
                        icon.click();
                    }

                    setTimeout(
                        function() {

                            const textarea =
                                document.querySelector(
                                    '.comments__send-form textarea'
                                );

                            const send =
                                document.querySelector(
                                    '.comments__send-btn'
                                );

                            if (
                                !textarea ||
                                !send
                            ) {

                                window.__mangaBuffCommentInProgress =
                                    false;

                                log(
                                    'COMMENT: form not found'
                                );

                                return;
                            }

                            const message =
                                COMMENTS[
                                    Math.floor(
                                        Math.random() *
                                        COMMENTS.length
                                    )
                                ];

                            textarea.focus();

                            const setter =
                                Object.getOwnPropertyDescriptor(
                                    HTMLTextAreaElement.prototype,
                                    'value'
                                )?.set;

                            if (setter) {
                                setter.call(
                                    textarea,
                                    message
                                );
                            } else {
                                textarea.value =
                                    message;
                            }

                            textarea.dispatchEvent(
                                new Event(
                                    'input',
                                    {
                                        bubbles: true
                                    }
                                )
                            );

                            textarea.dispatchEvent(
                                new Event(
                                    'change',
                                    {
                                        bubbles: true
                                    }
                                )
                            );

                            setTimeout(
                                function() {

                                    send.click();

                                    state.dailyComments =
                                        (
                                            state.dailyComments ||
                                            0
                                        ) + 1;

                                    state.chaptersSinceComment =
                                        0;

                                    state.nextCommentAfter =
                                        randomCommentInterval();

                                    window.localStorage.setItem(
                                        'mangabuff_read_comment_state',
                                        JSON.stringify(state)
                                    );

                                    window.__mangaBuffCommentHandled =
                                        true;

                                    window.__mangaBuffCommentInProgress =
                                        false;

                                    log(
                                        'COMMENT: sent, daily=' +
                                        state.dailyComments
                                    );

                                    setTimeout(
                                        function() {

                                            const close =
                                                document.querySelector(
                                                    '.reader-comments__close'
                                                );

                                            if (close) {
                                                close.click();
                                            }

                                        },
                                        1000
                                    );

                                },
                                700
                            );

                        },
                        800
                    );
                }

                /*
                 * ============================================================
                 * READER
                 * ============================================================
                 */

                function registerReaderDocument() {

                    const current =
                        getChapterKey();

                    if (
                        window.__mangaBuffActiveChapter !==
                        current
                    ) {

                        window.__mangaBuffActiveChapter =
                            current;

                        window.__mangaBuffAutoScrollStarted =
                            false;

                        window.__mangaBuffFallbackScrollStarted =
                            false;

                        window.__mangaBuffFallbackScrollDelayStarted =
                            false;

                        window.__mangaBuffSpeedSelectedForDocument =
                            false;

                        window.__mangaBuffCommentInProgress =
                            false;

                        window.__mangaBuffCommentHandled =
                            false;

                        window.__mangaBuffCountedChapter =
                            '';

                        window.__mangaBuffFinishedMangaFlowStarted =
                            false;

                        window.__mangaBuffMovingNextChapter =
                            false;

                        window.__mangaBuffNativeMaintenanceAt = 0;

                        stopFallbackScroll();

                        log(
                            'READER: new chapter -> ' +
                            current
                        );
                    }
                }

                function nativeBackgroundScrollTick() {

                    if (!window.__mangaBuffTaskRunning || !isReaderPage()) {
                        return;
                    }

                    if (atBottom()) {
                        return;
                    }

                    // Native scrolling is enabled only while Android reports that
                    // the app is in background / the screen is off. Foreground
                    // scrolling remains owned by the existing reader logic, so
                    // native scrolling cannot double the visible 1x/2x speed.
                    if (!window.__mangaBuffBackgroundMode) {
                        return;
                    }

                    const speedMultiplier =
                        Number(window.__mangaBuffSpeedMultiplier || 1);

                    // Same base as the foreground fallback: 1x = 280 px/s,
                    // 2x = exactly double.
                    const basePixelsPerSecond = 280;
                    const pixelsPerSecond =
                        basePixelsPerSecond *
                        (speedMultiplier >= 1.5 ? 2 : 1);

                    const pixels = pixelsPerSecond * 0.5;

                    if (pixels > 0) {
                        window.scrollBy(0, pixels);
                    }
                }

                function stopReaderMaintenanceTimer() {
                    if (window.__mangaBuffReaderTimer) {
                        clearInterval(window.__mangaBuffReaderTimer);
                        window.__mangaBuffReaderTimer = null;
                    }
                }

                function startReaderMaintenanceTimer() {
                    if (window.__mangaBuffReaderTimer) {
                        return;
                    }

                    window.__mangaBuffReaderTimer = setInterval(function() {
                        if (
                            window.__mangaBuffTaskRunning &&
                            !window.__mangaBuffBackgroundMode &&
                            isReaderPage()
                        ) {
                            readerTick();
                        }
                    }, 1000);
                }

                window.__mangaBuffSetBackgroundMode = function(enabled) {
                    window.__mangaBuffBackgroundMode = !!enabled;

                    if (window.__mangaBuffBackgroundMode) {
                        // In background the native Android heartbeat is the only
                        // reader maintenance loop. This prevents two independent
                        // loops from driving the same WebView.
                        stopFallbackScroll();
                        stopReaderMaintenanceTimer();
                    } else if (window.__mangaBuffTaskRunning && isReaderPage()) {
                        // Foreground returns ownership to the existing JS reader.
                        startReaderMaintenanceTimer();
                        ensureAutoScroll();
                    }
                };

                window.__mangaBuffNativeReadTick = function() {
                    try {
                        if (!window.__mangaBuffTaskRunning || !window.__mangaBuffBackgroundMode) {
                            return;
                        }

                        if (!checkSafety()) {
                            return;
                        }

                        if (isReaderPage()) {
                            nativeBackgroundScrollTick();

                            const now = Date.now();
                            const lastMaintenance = Number(window.__mangaBuffNativeMaintenanceAt || 0);
                            if (!lastMaintenance || now - lastMaintenance >= 1000) {
                                window.__mangaBuffNativeMaintenanceAt = now;
                                readerTick();
                            }
                        }
                    } catch (e) {
                        log(
                            'NATIVE TICK ERROR: ' +
                            (e && (e.message || e) || e)
                        );
                    }
                };

                function readerTick() {

                    if (!checkSafety()) {
                        return;
                    }

                    registerReaderDocument();

                    ensureAutoScroll();

                    ensureScrollSpeed();

                    maybePostComment();

                    if (!atBottom()) {
                        return;
                    }

                    log(
                        'READER: bottom reached'
                    );

                    if (
                        handleFinishedManga()
                    ) {
                        return;
                    }

                    if (
                        window.__mangaBuffMovingNextChapter
                    ) {
                        return;
                    }

                    window.__mangaBuffMovingNextChapter =
                        true;

                    stopFallbackScroll();

                    const button =
                        findAutoScrollButton();

                    if (
                        button &&
                        window.__mangaBuffAutoScrollStarted
                    ) {

                        try {
                            button.click();
                        } catch (e) {}

                        window.__mangaBuffAutoScrollStarted =
                            false;
                    }

                    window.__mangaBuffNextChapterTimer =
                        setTimeout(
                            function() {

                                window.__mangaBuffNextChapterTimer = null;
                                window.__mangaBuffMovingNextChapter =
                                    false;

                            if (!isReaderPage()) {
                                return;
                            }

                            if (
                                handleFinishedManga()
                            ) {
                                return;
                            }

                            openNextChapter();

                            },
                            1200
                        );
                }

                /*
                 * ============================================================
                 * START HOME
                 * ============================================================
                 */

                function startHome() {

                    if (!checkSafety()) {
                        return;
                    }

                    log(
                        'HOME: looking for last-read item'
                    );

                    if (continueFromHome()) {
                        return;
                    }

                    setTimeout(
                        function() {

                            if (
                                isHomePage() &&
                                window.__mangaBuffTaskRunning
                            ) {
                                continueFromHome();
                            }

                        },
                        1000
                    );

                    setTimeout(
                        function() {

                            if (
                                isHomePage() &&
                                window.__mangaBuffTaskRunning
                            ) {
                                continueFromHome();
                            }

                        },
                        2500
                    );

                    setTimeout(
                        function() {

                            if (
                                isHomePage() &&
                                window.__mangaBuffTaskRunning
                            ) {
                                continueFromHome();
                            }

                        },
                        5000
                    );
                }

                /*
                 * ============================================================
                 * START CATALOG
                 * ============================================================
                 */

                function startCatalog() {

                    if (!checkSafety()) {
                        return;
                    }

                    log(
                        'CATALOG: page loaded'
                    );

                    setTimeout(
                        function() {
                            catalogTick();
                        },
                        400
                    );

                    setTimeout(
                        function() {
                            catalogTick();
                        },
                        1200
                    );

                    setTimeout(
                        function() {
                            catalogTick();
                        },
                        2500
                    );

                    setTimeout(
                        function() {
                            catalogTick();
                        },
                        4500
                    );
                }

                /*
                 * ============================================================
                 * START MANGA PAGE
                 * ============================================================
                 */

                function startMangaPage() {

                    if (!checkSafety()) {
                        return;
                    }

                    log(
                        'MANGA: page loaded'
                    );

                    setTimeout(
                        function() {
                            openReaderFromMangaPage();
                        },
                        700
                    );

                    setTimeout(
                        function() {

                            if (
                                isMangaPage() &&
                                window.__mangaBuffTaskRunning
                            ) {
                                openReaderFromMangaPage();
                            }

                        },
                        2000
                    );
                }

                /*
                 * ============================================================
                 * START READER
                 * ============================================================
                 */

                function startReader() {

                    if (!checkSafety()) {
                        return;
                    }

                    readerTick();

                    if (!window.__mangaBuffBackgroundMode) {
                        startReaderMaintenanceTimer();
                    }
                }

                /*
                 * ============================================================
                 * RUN
                 * ============================================================
                 */

                function run() {

                    log(
                        'START: ' +
                        window.location.href
                    );

                    if (!checkSafety()) {
                        return;
                    }

                    if (isHomePage()) {
                        startHome();
                        return;
                    }

                    if (isCatalogPage()) {
                        startCatalog();
                        return;
                    }

                    if (isMangaPage()) {
                        startMangaPage();
                        return;
                    }

                    if (isReaderPage()) {
                        startReader();
                        return;
                    }

                    fail(
                        'Unsupported page: ' +
                        window.location.href
                    );
                }

                /*
                 * ============================================================
                 * START
                 * ============================================================
                 */

                try {

                    run();

                } catch (e) {

                    console.error(
                        '[READ] fatal error',
                        e
                    );

                    fail(
                        e?.message ||
                        String(e)
                    );
                }

            })();
        """.trimIndent()
    }

    override fun toString(): String {
        return "ReadTask(id=${spec.id}, targetUrl=${spec.targetUrl})"
    }
}