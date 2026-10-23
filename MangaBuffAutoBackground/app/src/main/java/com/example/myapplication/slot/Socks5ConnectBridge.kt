package com.example.myapplication.slot

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Локальный HTTP-прокси на 127.0.0.1, понимающий только метод CONNECT и пробрасывающий
 * туннель через удалённый SOCKS5-сервер (с авторизацией логином и паролем, RFC 1928/1929).
 *
 * Зачем: Chromium/WebView не умеет SOCKS5 с логином и паролем, а умеет HTTP-прокси.
 * WebView слота направляется на этот мост, а мост ходит в настоящий SOCKS5.
 *
 * Безопасность:
 *  - слушает только loopback;
 *  - любой другой процесс на устройстве может подключиться к loopback-порту, поэтому мост
 *    требует Proxy-Authorization с одноразовым случайным токеном, иначе отвечает 407;
 *  - никогда не подключается напрямую: при любой ошибке SOCKS5 клиент получает 502
 *    (fail-closed, без «отката» на прямое соединение);
 *  - имена хостов передаются в SOCKS5 как доменные имена (DNS решается на стороне прокси).
 */
class Socks5ConnectBridge(
    private val socksHost: String,
    private val socksPort: Int,
    private val username: String,
    private val password: String,
    val accessToken: String = newToken(),
    private val connectTimeoutMs: Int = 15_000,
    private val log: (String) -> Unit = {}
) : Closeable {

    private val server = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "socks5-bridge").apply { isDaemon = true }
    }
    private val lifecycleLock = Any()
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val closeCompleted = CountDownLatch(1)

    @Volatile
    private var closeFailure: IOException? = null
    @Volatile
    private var closed = false
    private var started = false

    /** Порт, на котором слушает мост (loopback). */
    val port: Int get() = server.localPort

    fun start(): Socks5ConnectBridge {
        synchronized(lifecycleLock) {
            check(!closed) { "SOCKS5 bridge is already closed" }
            if (started) return this
            started = true
        }
        try {
            pool.execute { acceptLoop() }
        } catch (e: RejectedExecutionException) {
            close()
            throw IOException("SOCKS5 bridge executor is unavailable", e)
        }
        return this
    }

    override fun close() {
        val sockets = synchronized(lifecycleLock) {
            if (closed) null else {
                closed = true
                activeSockets.toList()
            }
        }
        if (sockets == null) {
            val finished = try {
                closeCompleted.await(CLOSE_AWAIT_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted while waiting for SOCKS5 bridge shutdown", error)
            }
            if (!finished) throw IOException("SOCKS5 bridge shutdown is still in progress")
            closeFailure?.let { throw IOException("SOCKS5 bridge did not shut down cleanly", it) }
            return
        }

        var failure: IOException? = null
        try {
            closeQuietly(server)
            // Interrupting a thread alone does not reliably unblock Socket.read() on Android.
            // Closing every owned client/upstream socket unblocks both controlled tunnel directions.
            sockets.forEach(::closeQuietly)
            pool.shutdownNow()
            val terminated = pool.awaitTermination(
                CLOSE_AWAIT_TIMEOUT_MS,
                java.util.concurrent.TimeUnit.MILLISECONDS
            )
            if (!terminated) {
                failure = IOException("SOCKS5 bridge relay tasks did not terminate before timeout")
            } else {
                // Queued handlers may have been removed by shutdownNow() before their finally block.
                // No task can mutate the set after executor termination, so release those closed refs.
                synchronized(lifecycleLock) { activeSockets.clear() }
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            failure = IOException("Interrupted while stopping SOCKS5 bridge", error)
        } finally {
            pool.shutdownNow()
            closeFailure = failure
            closeCompleted.countDown()
        }
        failure?.let { throw it }
    }

    private fun registerSocket(socket: Socket): Boolean = synchronized(lifecycleLock) {
        if (closed) false else activeSockets.add(socket)
    }

    private fun unregisterSocket(socket: Socket?) {
        if (socket != null) activeSockets.remove(socket)
    }

    internal fun activeSocketCountForTest(): Int = activeSockets.size

    internal fun executorTerminatedForTest(): Boolean = pool.isTerminated

    private fun acceptLoop() {
        while (!closed) {
            val client = try {
                server.accept()
            } catch (_: IOException) {
                break
            }
            // Registration and close() use the same lock: a socket accepted during shutdown is
            // either included in close()'s snapshot or rejected and closed immediately.
            if (!registerSocket(client)) {
                closeQuietly(client)
                break
            }
            try {
                pool.execute { handle(client) }
            } catch (_: RejectedExecutionException) {
                unregisterSocket(client)
                closeQuietly(client)
                break
            }
        }
    }

    private fun handle(client: Socket) {
        var upstream: Socket? = null
        var tunneled = false
        try {
            client.tcpNoDelay = true
            client.soTimeout = HEADER_TIMEOUT_MS

            val head = readHead(client.getInputStream())
            if (head == null) {
                respond(client, 400, "Bad Request")
                return
            }
            val lines = head.split("\r\n")
            val requestLine = lines[0].split(' ')
            if (requestLine.size < 3 || !requestLine[0].equals("CONNECT", ignoreCase = true)) {
                // Открытый HTTP не поддерживается: приложение работает только по HTTPS.
                respond(client, 405, "Method Not Allowed")
                return
            }
            val authority = parseAuthority(requestLine[1])
            if (authority == null) {
                respond(client, 400, "Bad Request")
                return
            }

            val authHeader = lines.drop(1)
                .firstOrNull { it.startsWith("Proxy-Authorization:", ignoreCase = true) }
                ?.substringAfter(':')
                ?.trim()
            if (!isAuthorized(authHeader)) {
                respond(client, 407, "Proxy Authentication Required", "Proxy-Authenticate: Basic realm=\"slot\"")
                return
            }

            val (host, targetPort) = authority
            upstream = openSocks(host, targetPort)

            client.soTimeout = 0
            client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            tunneled = true
            tunnel(client, upstream)
        } catch (e: Exception) {
            log("BRIDGE_ERROR ${e.javaClass.simpleName}: ${e.message}")
            if (!tunneled) {
                runCatching { respond(client, 502, "Bad Gateway") }
            } else {
                closeQuietly(client)
                closeQuietly(upstream)
            }
        } finally {
            if (!tunneled) {
                closeQuietly(upstream)
                closeQuietly(client)
            }
            unregisterSocket(upstream)
            unregisterSocket(client)
        }
    }

    private fun isAuthorized(header: String?): Boolean {
        if (header == null || !header.startsWith("Basic ", ignoreCase = true)) return false
        val decoded = try {
            String(Base64.getDecoder().decode(header.substring(6).trim()), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val expected = "$AUTH_USER:$accessToken"
        return MessageDigest.isEqual(decoded.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
    }

    private fun respond(client: Socket, code: Int, reason: String, extraHeader: String? = null) {
        val response = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
            if (extraHeader != null) append(extraHeader).append("\r\n")
            append("Content-Length: 0\r\nConnection: close\r\n\r\n")
        }
        client.getOutputStream().write(response.toByteArray(Charsets.ISO_8859_1))
        client.getOutputStream().flush()
    }

    /** Открывает SOCKS5-соединение до [host]:[port] через удалённый сервер. */
    private fun openSocks(host: String, port: Int): Socket {
        val s = Socket()
        if (!registerSocket(s)) {
            closeQuietly(s)
            throw IOException("SOCKS5 bridge is closing")
        }
        try {
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(socksHost, socksPort), connectTimeoutMs)
            s.soTimeout = connectTimeoutMs
            val out = s.getOutputStream()
            val inp = s.getInputStream()

            val useAuth = username.isNotEmpty()
            out.write(if (useAuth) byteArrayOf(5, 2, 0, 2) else byteArrayOf(5, 1, 0))
            out.flush()
            val selected = readFully(inp, 2)
            if (selected[0].toInt() != 5) throw IOException("SOCKS5: неверная версия ответа")
            when (selected[1].toInt() and 0xFF) {
                0x00 -> Unit
                0x02 -> {
                    if (!useAuth) throw IOException("SOCKS5: сервер требует авторизацию")
                    val u = username.toByteArray(Charsets.UTF_8)
                    val p = password.toByteArray(Charsets.UTF_8)
                    if (u.size !in 1..255 || p.size > 255) throw IOException("SOCKS5: недопустимая длина логина или пароля")
                    out.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p)
                    out.flush()
                    val status = readFully(inp, 2)
                    if (status[1].toInt() != 0) throw IOException("SOCKS5: авторизация отклонена")
                }
                else -> throw IOException("SOCKS5: нет подходящего метода авторизации")
            }

            val request = ByteArrayOutputStream()
            request.write(byteArrayOf(5, 1, 0))
            val v4 = parseIpv4(host)
            when {
                v4 != null -> {
                    request.write(1)
                    request.write(v4)
                }
                host.contains(':') -> {
                    // IPv6-литерал; InetAddress.getByName для строки с ':' DNS не выполняет.
                    request.write(4)
                    request.write(InetAddress.getByName(host).address)
                }
                else -> {
                    val name = host.toByteArray(Charsets.US_ASCII)
                    if (name.isEmpty() || name.size > 255) throw IOException("SOCKS5: недопустимое имя хоста")
                    request.write(3)
                    request.write(name.size)
                    request.write(name)
                }
            }
            request.write(port ushr 8)
            request.write(port and 0xFF)
            out.write(request.toByteArray())
            out.flush()

            val reply = readFully(inp, 4)
            if (reply[0].toInt() != 5) throw IOException("SOCKS5: неверная версия ответа")
            if (reply[1].toInt() != 0) throw IOException("SOCKS5: отказ, код ${reply[1].toInt() and 0xFF}")
            val skip = when (reply[3].toInt()) {
                1 -> 4 + 2
                4 -> 16 + 2
                3 -> (readFully(inp, 1)[0].toInt() and 0xFF) + 2
                else -> throw IOException("SOCKS5: неизвестный тип адреса в ответе")
            }
            readFully(inp, skip)
            s.soTimeout = 0
            return s
        } catch (t: Throwable) {
            unregisterSocket(s)
            closeQuietly(s)
            throw t
        }
    }

    private fun tunnel(a: Socket, b: Socket) {
        val reverseDirectionFinished = CountDownLatch(1)
        // Keep sockets in activeSockets until both pumps have actually ended. Otherwise the
        // synchronous pump could return after a half-close and remove them while the reverse
        // pump is still blocked, making that connection invisible to close().
        pool.execute {
            try {
                pump(a, b)
            } finally {
                reverseDirectionFinished.countDown()
            }
        }
        try {
            pump(b, a)
            reverseDirectionFinished.await()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            closeQuietly(a)
            closeQuietly(b)
        }
    }

    private fun pump(from: Socket, to: Socket) {
        val buffer = ByteArray(16 * 1024)
        try {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                output.write(buffer, 0, n)
            }
            try {
                to.shutdownOutput()
            } catch (_: IOException) {
            }
        } catch (_: IOException) {
            closeQuietly(from)
            closeQuietly(to)
        }
    }

    private fun readHead(input: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < MAX_HEAD_BYTES) {
            val b = input.read()
            if (b < 0) return null
            sb.append(b.toChar())
            val n = sb.length
            if (n >= 4 && sb[n - 4] == '\r' && sb[n - 3] == '\n' && sb[n - 2] == '\r' && sb[n - 1] == '\n') {
                return sb.toString()
            }
        }
        return null
    }

    private fun readFully(input: InputStream, count: Int): ByteArray {
        val data = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val n = input.read(data, offset, count - offset)
            if (n < 0) throw IOException("SOCKS5: соединение закрыто сервером")
            offset += n
        }
        return data
    }

    private fun closeQuietly(c: Closeable?) {
        try {
            c?.close()
        } catch (_: IOException) {
        }
    }

    companion object {
        /** Имя пользователя для Proxy-Authorization между WebView и мостом. */
        const val AUTH_USER = "slot"
        private const val HEADER_TIMEOUT_MS = 15_000
        private const val CLOSE_AWAIT_TIMEOUT_MS = 5_000L
        private const val MAX_HEAD_BYTES = 16 * 1024

        fun newToken(): String {
            val bytes = ByteArray(24)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /** Разбирает authority из строки CONNECT: «host:port» или «[ipv6]:port». */
        internal fun parseAuthority(value: String): Pair<String, Int>? {
            val host: String
            val portText: String
            if (value.startsWith("[")) {
                val end = value.indexOf(']')
                if (end < 0 || end + 1 >= value.length || value[end + 1] != ':') return null
                host = value.substring(1, end)
                portText = value.substring(end + 2)
            } else {
                val i = value.lastIndexOf(':')
                if (i <= 0) return null
                host = value.substring(0, i)
                portText = value.substring(i + 1)
            }
            val port = portText.toIntOrNull() ?: return null
            if (host.isEmpty() || port !in 1..65535) return null
            return host to port
        }

        /** Возвращает 4 байта IPv4-литерала или null, если строка не является IPv4-адресом. */
        internal fun parseIpv4(value: String): ByteArray? {
            val parts = value.split('.')
            if (parts.size != 4) return null
            val out = ByteArray(4)
            for (i in 0..3) {
                val s = parts[i]
                if (s.isEmpty() || s.length > 3 || !s.all { it in '0'..'9' }) return null
                val v = s.toInt()
                if (v > 255) return null
                out[i] = v.toByte()
            }
            return out
        }
    }
}
