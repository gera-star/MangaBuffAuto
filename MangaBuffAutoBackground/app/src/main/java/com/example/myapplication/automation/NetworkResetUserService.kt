package com.example.myapplication.automation

import androidx.annotation.Keep
import com.example.myapplication.INetworkResetUserService
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Shizuku UserService. The process runs with the Shizuku backend identity
 * (shell UID for the normal wireless-ADB setup, root for a root backend).
 */
@Keep
class NetworkResetUserService : INetworkResetUserService.Stub() {

    override fun execute(command: String): String {
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()

            val finished = process.waitFor(5, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return "exit=-1 error=command timeout"
            }

            val output = BufferedReader(
                InputStreamReader(process.inputStream)
            ).use { it.readText() }

            val exitCode = process.exitValue()
            "exit=${exitCode} output=${output.trim().take(500)}"
        } catch (e: Exception) {
            "exit=-1 error=${e.message.orEmpty().take(500)}"
        }
    }

    override fun destroy() {
        System.exit(0)
    }
}
