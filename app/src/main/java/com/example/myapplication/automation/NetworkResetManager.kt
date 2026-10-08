package com.example.myapplication.automation

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.example.myapplication.INetworkResetUserService
import com.example.myapplication.data.NetworkResetMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.coroutines.resume

/**
 * Account-transition network reset.
 *
 * The ordinary Android app process cannot toggle Airplane Mode directly.
 * We first try an authorized Shizuku UserService (shell UID with Wireless
 * Debugging, or root when Shizuku is running with root). A legacy root-shell
 * fallback is kept for rooted devices without Shizuku.
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
            NetworkResetMode.AIRPLANE_MODE -> resetAirplaneMode()
        }
    }

    private suspend fun resetAirplaneMode(): Boolean {
        val provider = when {
            isShizukuReady() -> {
                onLog(
                    "AIRPLANE_MODE_PROVIDER=SHIZUKU uid=" +
                        runCatching { Shizuku.getUid() }.getOrDefault(-1)
                )
                "SHIZUKU"
            }
            rootShellAvailable() -> {
                onLog("AIRPLANE_MODE_PROVIDER=ROOT")
                "ROOT"
            }
            else -> {
                onLog("AIRPLANE_MODE_UNAVAILABLE reason=NO_PRIVILEGED_PROVIDER")
                return false
            }
        }

        var enabled = false
        try {
            val enable = runPrivilegedCommand(
                provider,
                "cmd connectivity airplane-mode enable"
            )
            onLog(
                "NETWORK_RESET_AIRPLANE_ENABLE provider=$$provider " +
                    "exit=$${enable.exitCode} output=$${sanitize(enable.output)}"
            )
            if (!enable.success) return false

            enabled = true
            delay(15_000L)

            val disable = runPrivilegedCommand(
                provider,
                "cmd connectivity airplane-mode disable"
            )
            onLog(
                "NETWORK_RESET_AIRPLANE_DISABLE provider=$$provider " +
                    "exit=$${disable.exitCode} output=$${sanitize(disable.output)}"
            )
            enabled = false

            if (!disable.success) return false

            onLog("NETWORK_WAIT_START")
            val ready = awaitValidatedNetwork(30_000L)
            onLog("NETWORK_WAIT_RESULT ready=$$ready")
            return ready
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onLog("NETWORK_RESET_ERROR error=$${e.message}")
            return false
        } finally {
            if (enabled) {
                runCatching {
                    val restore = runPrivilegedCommand(
                        provider,
                        "cmd connectivity airplane-mode disable"
                    )
                    onLog(
                        "NETWORK_RESET_AIRPLANE_FORCE_DISABLE " +
                            "provider=$$provider exit=$${restore.exitCode}"
                    )
                }
            }
        }
    }

    private fun isShizukuReady(): Boolean =
        try {
            Shizuku.pingBinder() &&
                !Shizuku.isPreV11() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }

    private fun rootShellAvailable(): Boolean =
        runCatching {
            val result = runRootCommandBlocking("id")
            result.success && result.output.contains("uid=0")
        }.getOrDefault(false)

    private suspend fun runPrivilegedCommand(
        provider: String,
        command: String
    ): RootCommandResult {
        return if (provider == "SHIZUKU") {
            runShizukuCommand(command)
        } else {
            runRootCommand(command)
        }
    }

    private suspend fun runShizukuCommand(command: String): RootCommandResult =
        withTimeoutOrNull(10_000L) {
            suspendCancellableCoroutine { continuation ->
                val args = Shizuku.UserServiceArgs(
                    ComponentName(context, NetworkResetUserService::class.java)
                )
                    .daemon(false)
                    .tag("mangabuff-network-reset")
                    .version(2)
                    .processNameSuffix("network-reset")

                lateinit var connection: ServiceConnection

                fun cleanup() {
                    runCatching {
                        Shizuku.unbindUserService(args, connection, true)
                    }
                }

                connection = object : ServiceConnection {
                    override fun onServiceConnected(
                        name: ComponentName?,
                        service: android.os.IBinder?
                    ) {
                        if (!continuation.isActive || service == null) {
                            cleanup()
                            return
                        }

                        try {
                            val remote =
                                INetworkResetUserService.Stub.asInterface(service)
                            val raw = remote.execute(command)
                            val parsed = parseUserServiceResult(raw)

                            if (continuation.isActive) {
                                continuation.resume(parsed)
                            }
                        } catch (e: Throwable) {
                            if (continuation.isActive) {
                                continuation.resume(
                                    RootCommandResult(
                                        success = false,
                                        exitCode = -1,
                                        output = e.message.orEmpty()
                                    )
                                )
                            }
                        } finally {
                            cleanup()
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        if (continuation.isActive) {
                            continuation.resume(
                                RootCommandResult(
                                    success = false,
                                    exitCode = -1,
                                    output = "Shizuku user service disconnected"
                                )
                            )
                        }
                    }
                }

                try {
                    Shizuku.bindUserService(args, connection)
                } catch (e: Throwable) {
                    if (continuation.isActive) {
                        continuation.resume(
                            RootCommandResult(
                                success = false,
                                exitCode = -1,
                                output = e.message.orEmpty()
                            )
                        )
                    }
                }

                continuation.invokeOnCancellation {
                    runCatching {
                        Shizuku.unbindUserService(args, connection, true)
                    }
                }
            }
        } ?: RootCommandResult(
            success = false,
            exitCode = -1,
            output = "Shizuku command timeout"
        )

    private fun parseUserServiceResult(raw: String): RootCommandResult {
        val match = Regex(
            "^exit=(-?$d+)$s+output=(.*)$",
            RegexOption.DOT_MATCHES_ALL
        ).find(raw.trim())

        if (match == null) {
            return RootCommandResult(
                success = false,
                exitCode = -1,
                output = raw.take(500)
            )
        }

        val exitCode = match.groupValues[1].toIntOrNull() ?: -1
        return RootCommandResult(
            success = exitCode == 0,
            exitCode = exitCode,
            output = match.groupValues[2]
        )
    }

    private suspend fun awaitValidatedNetwork(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val connectivityManager =
                    context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

                var registered = false

                lateinit var callback: ConnectivityManager.NetworkCallback

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
                    if (
                        current != null &&
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
            runRootCommandBlocking(command)
        }

    private fun runRootCommandBlocking(command: String): RootCommandResult {
        return try {
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
        value.replace("\$s+".toRegex(), " ").take(300)

    private data class RootCommandResult(
        val success: Boolean,
        val exitCode: Int,
        val output: String
    )
}
