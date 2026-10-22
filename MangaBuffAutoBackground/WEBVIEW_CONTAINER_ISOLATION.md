# Изоляция WebView-контейнеров (Android/Kotlin)

Этот документ описывает фактическую реализацию ветки `background-rebuild` и границы Android WebView API.

## Что уже применено в приложении

`automation/ProfileWebViewStore.kt` создаёт отдельный AndroidX WebView Profile на каждый аккаунт с именем `mb_<accountId>` и назначает его через `WebViewCompat.setProfile(webView, profileName)` **до настройки или использования WebView**. Cookies берутся из `ProfileStore.getInstance().getProfile(profileName)?.cookieManager`, а не из default-profile singleton.

В `automation/WebViewContainerPrivacy.kt` добавлены настройки каждого WebView: отключены third-party cookies, file/content access, смешанный HTTP-контент, всплывающие окна из JavaScript и геолокация; включён Safe Browsing; JavaScript и DOM Storage оставлены включёнными, потому что они необходимы сайту для входа и работы автоматизации. Cache остаётся `LOAD_DEFAULT`, но используется отдельный WebView Profile. User-Agent по умолчанию одинаковый и платформенный для всех профилей.

Важно: текущая архитектура приложения разделяет данные **по аккаунтам**, а не создаёт UI «произвольная вкладка-контейнер». Для браузера с вкладками выдавайте каждому контейнеру стабильный уникальный `profileName` (например, `tab_<UUID>`) и храните этот ID отдельно от URL. Не используйте один profileName для двух контейнеров, если они должны быть изолированы.

## 1. Cookies, LocalStorage, cache и setDataDirectorySuffix

Предпочтительный способ разделить данные нескольких WebView **в одном процессе** — AndroidX Multi-Profile API. Profile хранит собственный набор данных, включая профильный CookieManager и WebStorage. Это уже применяется в `ProfileWebViewStore`.

Минимальный пример:

```kotlin
val profileName = "tab_${containerId}" // устойчивый ID, только [a-zA-Z0-9_-]
val webView = WebView(context)
WebViewCompat.setProfile(webView, profileName) // до прочих операций с WebView

val profile = ProfileStore.getInstance().getProfile(profileName)
    ?: error("Profile не создан")
val cookieManager = profile.cookieManager
cookieManager.setAcceptCookie(true)
cookieManager.setAcceptThirdPartyCookies(webView, false)

// Теперь применяйте настройки и загружайте URL.
webView.loadUrl("https://example.com")
```

Проверяйте `WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)`; если функция не поддерживается установленным Android System WebView, не имитируйте изоляцию общими cookies — сообщите о несовместимости или используйте архитектуру отдельных процессов.

### Когда нужен setDataDirectorySuffix

`WebView.setDataDirectorySuffix()` (API 28+) и AndroidX `ProcessGlobalConfig.setDataDirectorySuffix()` назначают suffix **всему процессу**, а не конкретному экземпляру WebView. Вызов обязан произойти до создания первого WebView и до любых вызовов, которые инициализируют пакет `android.webkit`. Несколько WebView в одном процессе не могут получить разные suffix. Поэтому не вызывайте этот API при открытии каждой вкладки и не пытайтесь менять suffix на лету.

Для архитектуры с отдельным процессом на контейнер пример ранней инициализации (в каждом таком процессе только один раз):

```kotlin
// В Application.onCreate() процесса-контейнера, до любых WebView-вызовов.
if (WebViewFeature.isStartupFeatureSupported(
        WebViewFeature.STARTUP_FEATURE_SET_DATA_DIRECTORY_SUFFIX,
        this
    )
) {
    ProcessGlobalConfig.apply(
        ProcessGlobalConfig().setDataDirectorySuffix(
            this,
            "container_process_01"
        )
    )
}
```

Каждому реально отдельному процессу нужен собственный suffix. Данные между такими процессами не разделяются автоматически, включая cookies; если копирование сессии требуется, передавайте только специально выбранные данные через IPC. Один и тот же процесс нельзя переинициализировать с другим suffix.

## 2. Индивидуальный SOCKS5/HTTP-прокси

В AndroidX WebKit `ProxyController` устанавливает переопределение прокси для **процесса WebView**, а не для отдельного WebView или профиля. Следовательно, несколько профилей в одном процессе не могут одновременно использовать разные proxy routes. Текущий `network/AppProxySettings.kt` именно поэтому применяет proxy override на уровне процесса, а настройки маршрута блокируются во время выполняющихся задач.

Пример HTTP или SOCKS-прокси для одного отдельного WebView-процесса:

```kotlin
fun applyProcessProxy(
    host: String,
    port: Int,
    scheme: String, // "http", "https" или "socks"
    executor: Executor,
    onApplied: () -> Unit
) {
    require(host.isNotBlank() && port in 1..65535)
    require(scheme in setOf("http", "https", "socks"))

    check(WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
        "Эта версия Android System WebView не поддерживает proxy override"
    }

    val proxyConfig = ProxyConfig.Builder()
        .addProxyRule("$scheme://$host:$port")
        .build()

    ProxyController.getInstance()
        .setProxyOverride(proxyConfig, executor, Runnable { onApplied() })
}
```

Для SOCKS5 указывайте `scheme = "socks"`. Возможности proxy authentication зависят от стека и типа прокси; не считайте, что обычный WebView `HttpAuthHandler` гарантированно обработает аутентификацию SOCKS5.

Чтобы **разные одновременно открытые контейнеры** имели разные прокси, нужны разные процессы WebView (в каждом процессе свои suffix и ProxyController) либо отдельный корректно настроенный сетевой туннель/VPN. Multi-Profile сам по себе независимую маршрутизацию не предоставляет. При отдельном процессе нельзя передавать объект `WebView` в другой процесс: UI WebView должен находиться в том процессе, а управление — идти через IPC. Это более крупная архитектурная переработка; текущие account profiles и proxy override не следует выдавать за независимую маршрутизацию по вкладкам.

Прокси — не гарантия анонимности: он не убирает cookies, WebRTC/IP leaks во всех конфигурациях, TLS/browser fingerprints или идентификацию входом в один и тот же аккаунт. Тестируйте и DNS, и HTTPS, и UDP/WebRTC отдельно.

## 3. User-Agent и WebSettings

- Используйте одинаковый проверенный платформенный User-Agent по умолчанию для всех профилей. Случайный UA или добавление ID аккаунта обычно повышает уникальность отпечатка и может ломать совместимость.
- Настройки применяются к экземпляру `WebView`: JavaScript и DOM Storage должны оставаться включёнными только когда они нужны сайту; third-party cookies отключены; file/content access, геолокация и mixed content выключены; Safe Browsing включён; всплывающие окна из JavaScript и autoplay выключены.
- Кэш не надо считать отдельным только потому, что создан новый объект WebView: он связан с браузерным профилем. Для временной сессии добавьте явную операцию удаления профиля/очистки его данных, но не очищайте persistent login cookies при каждом открытии, если нужно сохранять вход.
- Не используйте режимы, которые ломают сайт, без тестов. Для строгой privacy-модели отключайте JavaScript/DOM Storage только там, где это действительно совместимо с нужными страницами.

В приложении эти значения централизованы в `WebViewContainerPrivacy.kt`, чтобы новые контейнеры не получали ослабленную конфигурацию случайно.

## Официальная документация

- [AndroidX Profile](https://developer.android.com/reference/androidx/webkit/Profile)
- [WebViewCompat.setProfile](https://developer.android.com/reference/androidx/webkit/WebViewCompat#setProfile(android.webkit.WebView,java.lang.String))
- [ProcessGlobalConfig.setDataDirectorySuffix](https://developer.android.com/reference/androidx/webkit/ProcessGlobalConfig#setDataDirectorySuffix(android.content.Context,java.lang.String))
- [ProxyController](https://developer.android.com/reference/androidx/webkit/ProxyController)
- [ProxyConfig.Builder.addProxyRule](https://developer.android.com/reference/androidx/webkit/ProxyConfig.Builder#addProxyRule(java.lang.String))
