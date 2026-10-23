package com.example.myapplication.slot

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class Socks5ConnectBridgeTest {

    /** Минимальный SOCKS5-сервер: запоминает, что запросил клиент, и отвечает эхом. */
    private class FakeSocks(private val requireAuth: Boolean, private val reject: Boolean = false) : AutoCloseable {
        val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        val requestedHost = AtomicReference<String>()
        val requestedPort = AtomicReference<Int>()
        val credentials = AtomicReference<String>()
        val done = CountDownLatch(1)

        init {
            Thread {
                try {
                    server.accept().use { s ->
                        val i = s.getInputStream()
                        val o = s.getOutputStream()
                        val ver = i.read()
                        val n = i.read()
                        val methods = ByteArray(n).also { readFully(i, it) }
                        assertEquals(5, ver)
                        if (requireAuth) {
                            assertTrue(methods.contains(2))
                            o.write(byteArrayOf(5, 2))
                            readFully(i, ByteArray(1)) // версия 1
                            val ul = i.read()
                            val u = ByteArray(ul).also { readFully(i, it) }
                            val pl = i.read()
                            val p = ByteArray(pl).also { readFully(i, it) }
                            credentials.set(String(u) + ":" + String(p))
                            o.write(byteArrayOf(1, 0))
                        } else {
                            o.write(byteArrayOf(5, 0))
                        }
                        val head = ByteArray(4).also { readFully(i, it) }
                        val host = when (head[3].toInt()) {
                            3 -> {
                                val l = i.read()
                                String(ByteArray(l).also { readFully(i, it) })
                            }
                            1 -> ByteArray(4).also { readFully(i, it) }.joinToString(".") { (it.toInt() and 0xFF).toString() }
                            else -> "?"
                        }
                        val pb = ByteArray(2).also { readFully(i, it) }
                        requestedHost.set(host)
                        requestedPort.set(((pb[0].toInt() and 0xFF) shl 8) or (pb[1].toInt() and 0xFF))
                        if (reject) {
                            o.write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0))
                            return@use
                        }
                        o.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                        o.flush()
                        // эхо
                        val buf = ByteArray(1024)
                        while (true) {
                            val r = i.read(buf)
                            if (r < 0) break
                            o.write(buf, 0, r)
                            o.flush()
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    done.countDown()
                }
            }.apply { isDaemon = true }.start()
        }

        private fun readFully(i: InputStream, b: ByteArray) {
            var off = 0
            while (off < b.size) {
                val r = i.read(b, off, b.size - off)
                if (r < 0) throw java.io.IOException("eof")
                off += r
            }
        }

        override fun close() = server.close()
    }

    private fun basic(token: String) =
        "Basic " + Base64.getEncoder().encodeToString("${Socks5ConnectBridge.AUTH_USER}:$token".toByteArray())

    private fun readStatusLine(s: Socket): String {
        val sb = StringBuilder()
        val i = s.getInputStream()
        while (!sb.endsWith("\r\n\r\n")) {
            val b = i.read()
            if (b < 0) break
            sb.append(b.toChar())
        }
        return sb.lineSequence().first()
    }

    @Test
    fun tunnelsThroughSocksWithCredentialsAndSendsHostnameNotIp() {
        FakeSocks(requireAuth = true).use { socks ->
            Socks5ConnectBridge("127.0.0.1", socks.server.localPort, "user", "pass").start().use { bridge ->
                Socket("127.0.0.1", bridge.port).use { c ->
                    c.soTimeout = 5000
                    c.getOutputStream().write(
                        ("CONNECT example.org:443 HTTP/1.1\r\nHost: example.org:443\r\n" +
                            "Proxy-Authorization: ${basic(bridge.accessToken)}\r\n\r\n").toByteArray()
                    )
                    assertTrue(readStatusLine(c).contains("200"))
                    c.getOutputStream().write("ping".toByteArray())
                    val reply = ByteArray(4)
                    var off = 0
                    while (off < 4) off += c.getInputStream().read(reply, off, 4 - off)
                    assertArrayEquals("ping".toByteArray(), reply)
                }
                assertEquals("example.org", socks.requestedHost.get())
                assertEquals(443, socks.requestedPort.get())
                assertEquals("user:pass", socks.credentials.get())
            }
        }
    }

    @Test
    fun rejectsClientsWithoutToken() {
        FakeSocks(requireAuth = false).use { socks ->
            Socks5ConnectBridge("127.0.0.1", socks.server.localPort, "", "").start().use { bridge ->
                Socket("127.0.0.1", bridge.port).use { c ->
                    c.soTimeout = 5000
                    c.getOutputStream().write("CONNECT example.org:443 HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                    assertTrue(readStatusLine(c).contains("407"))
                }
                Socket("127.0.0.1", bridge.port).use { c ->
                    c.soTimeout = 5000
                    c.getOutputStream().write(
                        ("CONNECT example.org:443 HTTP/1.1\r\nProxy-Authorization: ${basic("wrong")}\r\n\r\n").toByteArray()
                    )
                    assertTrue(readStatusLine(c).contains("407"))
                }
                // Ни одного обращения к SOCKS-серверу не было.
                assertNull(socks.requestedHost.get())
            }
        }
    }

    @Test
    fun failsClosedWithBadGatewayWhenSocksRejects() {
        FakeSocks(requireAuth = false, reject = true).use { socks ->
            Socks5ConnectBridge("127.0.0.1", socks.server.localPort, "", "").start().use { bridge ->
                Socket("127.0.0.1", bridge.port).use { c ->
                    c.soTimeout = 5000
                    c.getOutputStream().write(
                        ("CONNECT example.org:443 HTTP/1.1\r\nProxy-Authorization: ${basic(bridge.accessToken)}\r\n\r\n").toByteArray()
                    )
                    assertTrue(readStatusLine(c).contains("502"))
                }
            }
        }
    }

    @Test
    fun failsClosedWhenSocksServerUnreachable() {
        // Порт, на котором никто не слушает.
        val dead = ServerSocket(0).use { it.localPort }
        Socks5ConnectBridge("127.0.0.1", dead, "", "", connectTimeoutMs = 2000).start().use { bridge ->
            Socket("127.0.0.1", bridge.port).use { c ->
                c.soTimeout = 8000
                c.getOutputStream().write(
                    ("CONNECT example.org:443 HTTP/1.1\r\nProxy-Authorization: ${basic(bridge.accessToken)}\r\n\r\n").toByteArray()
                )
                assertTrue(readStatusLine(c).contains("502"))
            }
        }
    }

    @Test
    fun plainHttpMethodsAreNotForwarded() {
        FakeSocks(requireAuth = false).use { socks ->
            Socks5ConnectBridge("127.0.0.1", socks.server.localPort, "", "").start().use { bridge ->
                Socket("127.0.0.1", bridge.port).use { c ->
                    c.soTimeout = 5000
                    c.getOutputStream().write("GET http://example.org/ HTTP/1.1\r\nHost: example.org\r\n\r\n".toByteArray())
                    assertTrue(readStatusLine(c).contains("405"))
                }
                assertNull(socks.requestedHost.get())
            }
        }
    }

    @Test
    fun closeShutsDownActiveBidirectionalTunnelAndReleasesSockets() {
        FakeSocks(requireAuth = false).use { socks ->
            val bridge = Socks5ConnectBridge(
                "127.0.0.1", socks.server.localPort, "", "", connectTimeoutMs = 2_000
            ).start()
            try {
                Socket("127.0.0.1", bridge.port).use { client ->
                    client.soTimeout = 3_000
                    client.getOutputStream().write(
                        ("CONNECT example.org:443 HTTP/1.1\r\n" +
                            "Proxy-Authorization: ${basic(bridge.accessToken)}\r\n\r\n").toByteArray()
                    )
                    assertTrue(readStatusLine(client).contains("200"))
                    client.getOutputStream().write("ping".toByteArray())
                    val echoed = ByteArray(4)
                    var offset = 0
                    while (offset < echoed.size) {
                        val n = client.getInputStream().read(echoed, offset, echoed.size - offset)
                        if (n < 0) throw AssertionError("Tunnel closed before echo completed")
                        offset += n
                    }
                    assertEquals("ping", String(echoed))
                    bridge.close()
                    val eof = runCatching { client.getInputStream().read() }.getOrNull()
                    assertTrue("client side should be closed or at EOF", eof == null || eof == -1)
                }
                assertTrue("upstream fake server should observe tunnel close", socks.done.await(3, TimeUnit.SECONDS))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (bridge.activeSocketCountForTest() != 0 && System.nanoTime() < deadline) {
                    Thread.sleep(10)
                }
                assertEquals(0, bridge.activeSocketCountForTest())
            } finally {
                bridge.close()
            }
        }
    }

    @Test
    fun closeIsIdempotentBeforeAndAfterStart() {
        val neverStarted = Socks5ConnectBridge("127.0.0.1", 1080, "", "")
        neverStarted.close()
        assertTrue(runCatching { neverStarted.start() }.isFailure)
        neverStarted.close()

        val started = Socks5ConnectBridge("127.0.0.1", 1080, "", "").start()
        started.close()
        started.close()
    }

    @Test
    fun parsesAuthorityAndIpv4() {
        assertEquals("a.b" to 443, Socks5ConnectBridge.parseAuthority("a.b:443"))
        assertEquals("::1" to 8443, Socks5ConnectBridge.parseAuthority("[::1]:8443"))
        assertNull(Socks5ConnectBridge.parseAuthority("a.b"))
        assertNull(Socks5ConnectBridge.parseAuthority("a.b:0"))
        assertNull(Socks5ConnectBridge.parseAuthority("a.b:99999"))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), Socks5ConnectBridge.parseIpv4("1.2.3.4"))
        assertNull(Socks5ConnectBridge.parseIpv4("1.2.3.256"))
        assertNull(Socks5ConnectBridge.parseIpv4("example.org"))
        assertNotNull(Socks5ConnectBridge.newToken())
    }
}
