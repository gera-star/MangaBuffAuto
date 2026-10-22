package com.example.myapplication.slot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotRouteTest {
    @Test
    fun noneIsAlwaysValid() {
        assertNull(SlotRoute().validate())
    }

    @Test
    fun rejectsBadHostsAndPorts() {
        assertNotNull(SlotRoute(SlotProxyType.HTTP, "", 8080).validate())
        assertNotNull(SlotRoute(SlotProxyType.HTTP, "http://h", 8080).validate())
        assertNotNull(SlotRoute(SlotProxyType.HTTP, "h/x", 8080).validate())
        assertNotNull(SlotRoute(SlotProxyType.SOCKS5, "h h", 1080).validate())
        assertNotNull(SlotRoute(SlotProxyType.SOCKS5, "h", 0).validate())
        assertNotNull(SlotRoute(SlotProxyType.SOCKS5, "h", 70000).validate())
        assertNull(SlotRoute(SlotProxyType.SOCKS5, "proxy.example.org", 1080, "u", "p").validate())
    }

    @Test
    fun toStringNeverContainsSecrets() {
        val text = SlotRoute(SlotProxyType.SOCKS5, "h.example", 1080, "alice", "s3cret").toString()
        assertFalse(text.contains("s3cret"))
        assertFalse(text.contains("alice"))
    }

    @Test
    fun differentRoutesHaveDifferentKeys() {
        val a = SlotRoute(SlotProxyType.HTTP, "h", 1)
        val b = SlotRoute(SlotProxyType.HTTP, "h", 2)
        assertTrue(a.bindingKey() != b.bindingKey())
        assertEquals(a.bindingKey(), SlotRoute(SlotProxyType.HTTP, " H ", 1).bindingKey())
    }

    @Test
    fun urlNormalizationAcceptsOnlyHttpAndHttps() {
        assertEquals("https://example.org", normalizeSlotUrl("example.org"))
        assertEquals("http://example.org/a", normalizeSlotUrl("http://example.org/a"))
        assertNull(normalizeSlotUrl("javascript:alert(1)"))
        assertNull(normalizeSlotUrl("data:text/html,<script>alert(1)</script>"))
        assertNull(normalizeSlotUrl("file:///etc/passwd"))
        assertNull(normalizeSlotUrl("intent://x#Intent;end"))
        assertNull(normalizeSlotUrl("ftp://example.org"))
        assertNull(normalizeSlotUrl("   "))
        assertNull(normalizeSlotUrl("https://"))
        assertEquals("https://example.org:8080", normalizeSlotUrl("example.org:8080"))
    }

    @Test
    fun ipv6AuthorityIsBracketed() {
        assertEquals("[::1]", SlotRoute(SlotProxyType.HTTP, "::1", 1).authorityHost())
        assertEquals("h", SlotRoute(SlotProxyType.HTTP, "h", 1).authorityHost())
    }
}
