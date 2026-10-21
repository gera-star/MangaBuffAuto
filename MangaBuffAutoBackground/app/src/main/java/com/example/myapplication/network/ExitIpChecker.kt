package com.example.myapplication.network

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Hides the tail of an address so the screen shows only enough to tell connections apart. */
object ExitIpMask {
    private val ipv4 = Regex("""^(\d{1,3})\.(\d{1,3})\.\d{1,3}\.\d{1,3}$""")

    fun mask(raw: String): String? {
        val ip = raw.trim()
        ipv4.matchEntire(ip)?.let { return it.groupValues[1] + "." + it.groupValues[2] + ".*.*" }
        if (ip.contains(':') && ip.all { it.isLetterOrDigit() || it == ':' }) {
            val groups = ip.split(':').filter { it.isNotEmpty() }
            if (groups.size >= 2) return groups[0] + ":" + groups[1] + ":*"
        }
        return null
    }
}

/** Pulls the visitor address out of the Yandex "internet" page text, preferring the IPv4 label. */
object YandexInternetPageParser {
    private val ipv4 = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
    private val labelled = Regex("""IPv4[^0-9]{0,200}((?:\d{1,3}\.){3}\d{1,3})""")

    fun extract(html: String): String? {
        val candidate = labelled.find(html)?.groupValues?.get(1)
            ?: ipv4.find(html)?.value
        return candidate?.takeIf { part ->
            part.split('.').all { (it.toIntOrNull() ?: 256) in 0..255 }
        }
    }
}

/**
 * Temporary diagnostic: asks a public echo service which address this account's HTTP client
 * currently exits from, using the same per-account proxy settings as automation requests.
 * Only a masked value is returned. This does not check the WebView's own route.
 */
object ExitIpChecker {
    data class Result(val maskedIp: String?, val viaProxy: Boolean, val error: String?)

    private const val YANDEX_URL = "https://yandex.com/internet/"
    private const val ECHO_URL = "https://api.ipify.org"
    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    suspend fun check(context: Context, accountId: String): Result = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val viaProxy = AppProxySettings.read(appContext, accountId).isEnabledAndValid()
        val client = AppProxySettings.configureOkHttp(
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .callTimeout(12, TimeUnit.SECONDS),
            appContext,
            accountId
        ).build()
        var lastError = "нет ответа"
        // The Yandex page is tried first (reachable from Russia); the plain echo service is the fallback.
        for (source in listOf(YANDEX_URL, ECHO_URL)) {
            try {
                val request = Request.Builder().url(source).header("User-Agent", BROWSER_UA).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        lastError = "HTTP " + response.code
                        return@use
                    }
                    val body = response.body?.source()?.let { src ->
                        src.request(200_000L)
                        src.buffer.clone().readUtf8()
                    }.orEmpty()
                    val raw = if (source == YANDEX_URL) YandexInternetPageParser.extract(body) else body
                    val masked = raw?.let { ExitIpMask.mask(it) }
                    if (masked != null) return@withContext Result(masked, viaProxy, null)
                    lastError = "нет ответа"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e.javaClass.simpleName
            }
        }
        Result(null, viaProxy, lastError)
    }
}
