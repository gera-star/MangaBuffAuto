package com.example.myapplication.automation

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.example.myapplication.data.NetworkResetMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.coroutines.resume

/**
 * Account-transition network reset.
 *
 * The ordinary Android app process cannot toggle Airplane Mode directly.
 * This provider therefore treats AIRPLANE_MODE as an optional privileged
 * capability and uses a root shell when the device exposes a working "su".
 *
 * A future Shizuku implementation can implement the same contract without
 * changing the sequential runner.
 */
class NetworkResetManager(
    private val context: Context,
    private val onLog: (String) -> Unit = {}
) {

    suspend fun reset(mode: NetworkResetMode): Boolean {
        if (mode == NetworkResetMode.NONE) {
            onLog("NETWORK_RESET_SKIPPED mode=NONE")
            return true
        }

        onLog("NETWORK_RESET_REQUESTED mode=$mode")

        return when (mode) {
            NetworkResetMode.NONE -> true
            NetworkResetMode.AIRPLANE_MODE -> resetByRootAirplaneMode()
        }
    }

    private suspend fun resetByRootAirplaneMode(): Boolean {
        val rootCheck = runRootCommand("id")
        if (!rootCheck.success || !rootCheck.output.contains("uid=0")) {
            onLog("AIRPLANE_MODE_UNAVAILABLE reason=ROOT_SHELL_NOT_AVAILABLE")
            return false
        }

        var enabled = false
        try {
            val enable = runRootCommand("cmd connectivity airplane-mode enable")
            onLog(
                "NETWORK_RESET_AIRPLANE_ENABLE exit=\$enable.exitCode " +
                    "output=\$sanitize(enable.output)"
            )
            if (!enable.success) return false

            enabled = true
            delay(15_000L)

            val disable = runRootCommand("cmd connectivity airplane-mode disable")
            onLog(
                "NETWORK_RESET_AIRPLANE_DISABLE exit=\$disable.exitCode " +
                    "output=\$sanitize(disable.output)"
            )
            enabled = false

            if (!disable.success) return false

            onLog("NETWORK_WAIT_START")
            val ready = awaitValidatedNetwork(30_000L)
            onLog("NETWORK_WAIT_RESULT ready=\$ready")
            return ready
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onLog("NETWORK_RESET_ERROR error=\$e.message")
            return false
        } finally {
            if (enabled) {
                runCatching {
                    val restore = runRootCommand("cmd connectivity airplane-mode disable")
                    onLog(
                        "NETWORK_RESET_AIRPLANE_FORCE_DISABLE exit=\$restore.exitCode"
                    )
                }
            }
        }
    }

    private suspend fun awaitValidatedNetwork(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val connectivityManager =
                    context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

                var registered = false

                fun cleanup() {
                    if (registered) {
                        runCatching {
                            connectivityManager.unregisterNetworkCallback(callback)
                        }
                        registered = false
                    }
                }

                fun isValidated(capabilities: NetworkCapabilities?): Boolean =
                    capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

                lateinit var callback: ConnectivityManager.NetworkCallback
                callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities
                    ) {
                        if (isValidated(networkCapabilities) && continuation.isActive) {
                            cleanup()
                            continuation.resume(true)
                        }
                    }

                    override fun onAvailable(network: Network) {
                        val capabilities =
                            connectivityManager.getNetworkCapabilities(network)
                        if (isValidated(capabilities) && continuation.isActive) {
                            cleanup()
                            continuation.resume(true)
                        }
                    }
                }

                try {
                    val current = connectivityManager.activeNetwork
                    if (current != null &&
                        isValidated(connectivityManager.getNetworkCapabilities(current))
                    ) {
                        continuation.resume(true)
                        return@suspendCancellableCoroutine
                    }

                    connectivityManager.registerDefaultNetworkCallback(callback)
                    registered = true
                } catch (e: Exception) {
                    cleanup()
                    if (continuation.isActive) {
                        continuation.resume(false)
                    }
                }

                continuation.invokeOnCancellation {
                    cleanup()
                }
            }
        } ?: false

    private suspend fun runRootCommand(command: String): RootCommandResult =
        withContext(Dispatchers.IO) {
            try {
                val process = ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start()

                val output = BufferedReader(
                    InputStreamReader(process.inputStream)
                ).use { it.readText() }

                val exitCode = process.waitFor()

                RootCommandResult(
                    success = exitCode == 0,
                    exitCode = exitCode,
                    output = output.trim()
                )
            } catch (e: Exception) {
                RootCommandResult(
                    success = false,
                    exitCode = -1,
                    output = e.message.orEmpty()
                )
            }
        }

    private fun sanitize(value: String): String =
        value.replace("\\s+".toRegex(), " ").take(300)

    private data class RootCommandResult(
        val success: Boolean,
        val exitCode: Int,
        val output: String
    )
}
