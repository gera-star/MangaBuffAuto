# MangaBuff Auto — Android WebView Experiment

Диагностическая экспериментальная сборка.

Внутри:
- Android WebView + Compose
- AutomationEngine
- TaskManager
- WebViewDiagnosticsTask
- BalanceInspectTask

Не реализованы CAPTCHA/Cloudflare bypass, stealth/fingerprint spoofing,
обход ограничений или подделка запросов.

Diagnostics не выводит значения cookies/localStorage/sessionStorage — только наличие,
количество и имена ключей.

Проверка:
1. Открыть проект в Android Studio.
2. Gradle Sync.
3. Запустить приложение.
4. При необходимости вручную авторизоваться в WebView.
5. Нажать Diagnostics.
6. Затем Balance.
7. Прислать Logcat с тегом MangaBuffAuto.
