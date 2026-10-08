package com.example.myapplication.automation

import androidx.annotation.Keep
import com.example.myapplication.INetworkResetUserService
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Shizuku UserService. The process runs with the Shizuku backend identity
 * (shell UID for the normal wireless-ADB setup, root for a root backend).
 */
@Keep
class NetworkResetUserService : INetworkResetUserService.Stub() {

    @Keep
    constructor()

    override fun execute(command: String): String {
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()

            val output = BufferedReader(
                InputStreamReader(process.inputStream)
            ).use { it.readText() }

            val exitCode = process.waitFor()
            "exit=${exitCode} output=${output.trim().take(500)}"
        } catch (e: Exception) {
            "exit=-1 error=${e.message.orEmpty().take(500)}"
        }
    }

    override fun destroy() {
        System.exit(0)
    }
}
