package com.example.myapplication.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExitIpMaskTest {
    @Test
    fun ipv4KeepsFirstTwoParts() {
        assertEquals("85.12.*.*", ExitIpMask.mask("85.12.34.56\n"))
    }

    @Test
    fun ipv6KeepsFirstTwoGroups() {
        assertEquals("2001:db8:*", ExitIpMask.mask("2001:db8:85a3::8a2e:370:7334"))
    }

    @Test
    fun garbageIsRejected() {
        assertNull(ExitIpMask.mask("<html>blocked</html>"))
        assertNull(ExitIpMask.mask(""))
    }

    @Test
    fun yandexPageParserPrefersLabelledIpv4() {
        val page = "Версия 10.0.0.1 ... IPv4: 85.12.34.56 ... IPv6: нет"
        assertEquals("85.12.34.56", YandexInternetPageParser.extract(page))
        assertNull(YandexInternetPageParser.extract("нет адреса"))
        assertNull(YandexInternetPageParser.extract("IPv4 999.1.1.1"))
    }
}
