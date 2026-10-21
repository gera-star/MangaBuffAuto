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

/**
 * Temporary diagnostic: asks a public echo service which address this account's HTTP client
 * currently exits from, using the same per-account proxy settings as automation requests.
 * Only a masked value is returned. This does not check the WebView's own route.
 */
object ExitIpChecker {
    data class Result(val maskedIp: String?, val viaProxy: Boolean, val error: String?)

    private const val ECHO_URL = "https://api.ipify.org"

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
        try {
            client.newCall(Request.Builder().url(ECHO_URL).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result(null, viaProxy, "HTTP " + response.code)
                }
                val masked = ExitIpMask.mask(response.body?.string().orEmpty())
                if (masked == null) Result(null, viaProxy, "нет ответа")
                else Result(masked, viaProxy, null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result(null, viaProxy, e.javaClass.simpleName)
        }
    }
}
