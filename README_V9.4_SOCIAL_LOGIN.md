# MangaBuffAuto V9.4 — Social Login routing fix

Основа: V9.3.

Исправлен `AutomationRuntime.kt`.

## Что исправлено

1. VK ID:
   - добавлены `id.vk.ru`, `vk.com` и `*.vk.ru` в OAuth hosts;
   - URL вида `https://id.vk.ru/authorize?...` больше не уходит в обычную ветку внешних ссылок.
2. Discord / Yandex / Google:
   - сохранена OAuth-ветка для `discord.com`, `*.discord.com`, `yandex.ru`, `*.yandex.ru`, `accounts.google.com`, `*.google.com`.
3. Обычные внешние ссылки:
   - `ACTION_VIEW` теперь запускается через `applicationContext` с `FLAG_ACTIVITY_NEW_TASK`, поэтому больше не должно быть ошибки:
     `Calling startActivity() from outside of an Activity context...`
4. Остальная архитектура V9.3 не изменялась.

## Важно

Это исправляет маршрутизацию OAuth внутри WebView. Я не могу выполнить реальную авторизацию VK/Discord/Yandex/Google без устройства и пользовательской сессии, поэтому после сборки нужно проверить каждый провайдер вручную.

Google может дополнительно ограничивать OAuth внутри embedded WebView; если после этой версии только Google не пройдет авторизацию, нужен отдельный разбор фактического URL/ошибки Google, а не изменение остальных провайдеров.
