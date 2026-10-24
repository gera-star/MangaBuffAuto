package com.example.myapplication.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountWebUrlPolicyTest {
    @Test
    fun acceptsHttpsAndNormalizesWhitespace() {
        assertEquals("https://mangabuff.ru/", AccountWebUrlPolicy.normalize("  https://mangabuff.ru/  "))
        assertEquals("https://example.org/path", AccountWebUrlPolicy.normalize("https://example.org/path"))
    }

    @Test
    fun rejectsHttpAndOtherSchemes() {
        assertNull(AccountWebUrlPolicy.normalize("http://example.org"))
        assertNull(AccountWebUrlPolicy.normalize("javascript:alert(1)"))
        assertNull(AccountWebUrlPolicy.normalize("file:///etc/passwd"))
        assertNull(AccountWebUrlPolicy.normalize("//example.org/path"))
    }

    @Test
    fun rejectsMalformedAuthorityAndUserInfo() {
        assertNull(AccountWebUrlPolicy.normalize("https://"))
        assertNull(AccountWebUrlPolicy.normalize("https://:443"))
        assertNull(AccountWebUrlPolicy.normalize("https://host:abc"))
        assertNull(AccountWebUrlPolicy.normalize("https://host:"))
        assertNull(AccountWebUrlPolicy.normalize("https://exa_mple.org"))
        assertNull(AccountWebUrlPolicy.normalize("https://user:pass@example.org"))
    }

    @Test
    fun rejectsInvalidPorts() {
        assertNull(AccountWebUrlPolicy.normalize("https://example.org:0"))
        assertNull(AccountWebUrlPolicy.normalize("https://example.org:65536"))
    }
}
