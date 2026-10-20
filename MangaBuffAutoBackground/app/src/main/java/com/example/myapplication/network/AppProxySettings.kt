package com.example.myapplication.network

import android.content.Context
import android.webkit.HttpAuthHandler
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

data class AppProxyConfig(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = 8080,
    val username: String = "",
    val password: String = ""
) {
    fun isValid(): Boolean =
        host.isNotBlank() &&
            !host.contains("://") &&
            !host.contains("/") &&
            !host.any { it.isWhitespace() } &&
            port in 1..65535

    fun isEnabledAndValid(): Boolean = enabled && isValid()
}

/**
 * Temporary app-level proxy settings.
 *
 * WebView uses AndroidX ProxyController; OkHttp requests use a dynamic ProxySelector
 * so toggling the setting affects new connections without recreating the client.
 */
object AppProxySettings {
    private const val PREFS = "mangabuff_temporary_proxy"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"
    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD = "password"

    fun read(context: Context): AppProxyConfig {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return AppProxyConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            host = prefs.getString(KEY_HOST, "").orEmpty(),
            port = prefs.getInt(KEY_PORT, 8080),
            username = prefs.getString(KEY_USERNAME, "").orEmpty(),
            password = prefs.getString(KEY_PASSWORD, "").orEmpty()
        )
    }

    fun save(context: Context, config: AppProxyConfig) {
        require(!config.enabled || config.isValid()) {
            "Укажи адрес прокси без http:// и порт от 1 до 65535."
        }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putString(KEY_HOST, config.host.trim())
            .putInt(KEY_PORT, config.port)
            .putString(KEY_USERNAME, config.username)
            .putString(KEY_PASSWORD, config.password)
            .apply()
    }

    /**
     * Applies the override to every WebView in this app process. When disabled,
     * the system proxy configuration is restored.
     */
    fun applyWebViewProxy(
        context: Context,
        onComplete: (success: Boolean, message: String) -> Unit = { _, _ -> }
    ) {
        val appContext = context.applicationContext
        val config = read(appContext)
        val executor = ContextCompat.getMainExecutor(appContext)
        try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                if (config.enabled) {
                    onComplete(false, "Эта версия Android System WebView не поддерживает прокси приложения.")
                } else {
                    onComplete(true, "Прокси выключен.")
                }
                return
            }

            val controller = ProxyController.getInstance()
            if (config.enabled) {
                if (!config.isValid()) {
                    onComplete(false, "Проверь адрес прокси и порт.")
                    return
                }
                val proxyConfig = ProxyConfig.Builder()
                    .addProxyRule("${config.host}:${config.port}")
                    .build()
                controller.setProxyOverride(proxyConfig, executor, Runnable {
                    onComplete(true, "Прокси применён для WebView. Перезапусти текущую задачу.")
                })
            } else {
                controller.clearProxyOverride(executor, Runnable {
                    onComplete(true, "Прокси выключен; возвращено системное подключение.")
                })
            }
        } catch (e: Exception) {
            onComplete(false, "Не удалось применить прокси: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Routes HTTP-client traffic through the current proxy settings. */
    fun configureOkHttp(builder: OkHttpClient.Builder, context: Context): OkHttpClient.Builder {
        val appContext = context.applicationContext
        val systemSelector = ProxySelector.getDefault()

        builder.proxySelector(object : ProxySelector() {
            override fun select(uri: URI?): List<Proxy> {
                if (uri == null) return listOf(Proxy.NO_PROXY)
                val current = read(appContext)
                if (!current.isEnabledAndValid()) {
                    return systemSelector?.select(uri) ?: listOf(Proxy.NO_PROXY)
                }
                return try {
                    listOf(
                        Proxy(
                            Proxy.Type.HTTP,
                            InetSocketAddress(current.host, current.port)
                        )
                    )
                } catch (_: Exception) {
                    listOf(Proxy.NO_PROXY)
                }
            }

            override fun connectFailed(uri: URI?, socketAddress: SocketAddress?, exception: IOException?) {
                if (uri != null && socketAddress != null) {
                    runCatching { systemSelector?.connectFailed(uri, socketAddress, exception) }
                }
            }
        })

        builder.proxyAuthenticator(Authenticator { route, response ->
            val current = read(appContext)
            val proxyRoute = route?.proxy?.type() == Proxy.Type.HTTP
            if (
                !current.isEnabledAndValid() ||
                current.username.isBlank() ||
                !proxyRoute ||
                response.request.header("Proxy-Authorization") != null ||
                responseCount(response) >= 2
            ) {
                null
            } else {
                response.request.newBuilder()
                    .header("Proxy-Authorization", Credentials.basic(current.username, current.password))
                    .build()
            }
        })
        return builder
    }

    /**
     * Handles an HTTP proxy authentication challenge for automation WebViews.
     * Returns true when proxy credentials were supplied.
     */
    fun handleWebViewProxyAuth(
        context: Context,
        host: String?,
        handler: HttpAuthHandler
    ): Boolean {
        val current = read(context)
        if (
            !current.isEnabledAndValid() ||
            current.username.isBlank() ||
            !(host.equals(current.host, ignoreCase = true) ||
                host.equals("${current.host}:${current.port}", ignoreCase = true))
        ) {
            return false
        }
        handler.proceed(current.username, current.password)
        return true
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var previous = response.priorResponse
        while (previous != null) {
            count++
            previous = previous.priorResponse
        }
        return count
    }
}
