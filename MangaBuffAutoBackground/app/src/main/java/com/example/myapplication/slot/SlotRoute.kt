package com.example.myapplication.slot

/**
 * Тип маршрута контейнерной вкладки. Модель не зависит от Android, чтобы её можно было
 * проверять обычными JVM-тестами.
 */
enum class SlotProxyType { NONE, HTTP, SOCKS5 }

/**
 * Маршрут одного слота (одного процесса WebView).
 *
 * Прокси в WebView задаётся на весь процесс, поэтому маршрут жёстко привязан к слоту
 * на всё время жизни процесса и не меняется «на лету».
 */
data class SlotRoute(
    val type: SlotProxyType = SlotProxyType.NONE,
    val host: String = "",
    val port: Int = 0,
    val username: String = "",
    val password: String = ""
) {
    /** Возвращает текст ошибки или null, если маршрут корректен. */
    fun validate(): String? {
        if (type == SlotProxyType.NONE) return null
        if (host.isBlank() || host.contains("://") || host.contains('/') || host.any { it.isWhitespace() }) {
            return "Некорректный адрес прокси: укажите только хост без http:// и пути."
        }
        if (port !in 1..65535) return "Порт прокси должен быть от 1 до 65535."
        if (type == SlotProxyType.SOCKS5 &&
            (username.toByteArray(Charsets.UTF_8).size > 255 || password.toByteArray(Charsets.UTF_8).size > 255)
        ) {
            return "Логин и пароль SOCKS5 не могут быть длиннее 255 байт."
        }
        return null
    }

    /** Ключ сравнения маршрутов (включает пароль, существует только в памяти). */
    fun bindingKey(): String =
        listOf(type.name, host.trim().lowercase(), port.toString(), username, password).joinToString("\u0001")

    /** Описание для интерфейса: без логина и пароля. */
    fun describe(): String = when (type) {
        SlotProxyType.NONE -> "без прокси"
        SlotProxyType.HTTP -> "HTTP ${hostForDisplay()}:$port"
        SlotProxyType.SOCKS5 -> "SOCKS5 ${hostForDisplay()}:$port"
    }

    /** Хост в формате URI-authority: IPv6-литералы берутся в квадратные скобки. */
    fun authorityHost(): String {
        val h = host.trim()
        return if (h.contains(':') && !h.startsWith("[")) "[$h]" else h
    }

    private fun hostForDisplay(): String = host.trim()

    // Пароль не должен попадать в логи через автоматический toString().
    override fun toString(): String = "SlotRoute(${describe()}, user=${if (username.isEmpty()) "-" else "***"})"
}

/**
 * Приводит введённый адрес к https-URL. Допускаются только http/https;
 * любые другие схемы (file:, intent:, javascript:, content:) отклоняются.
 */
fun normalizeSlotUrl(raw: String): String? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val withScheme = if (text.contains("://")) text else "https://$text"
    val scheme = withScheme.substringBefore("://").lowercase()
    if (scheme != "https" && scheme != "http") return null
    val rest = withScheme.substringAfter("://")
    if (rest.isBlank() || rest.any { it.isWhitespace() }) return null
    return withScheme
}
