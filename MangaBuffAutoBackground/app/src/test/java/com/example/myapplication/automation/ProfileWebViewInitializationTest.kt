package com.example.myapplication.automation

import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileWebViewInitializationTest {
    private data class FakeWebView(val id: String)

    @Test
    fun viewRemainsTrackedWhenProfileBindingThrows() {
        val active = linkedMapOf<String, FakeWebView>()
        val view = FakeWebView("profile-error")
        val failure = runCatching {
            initializeTrackedWebView(active, "account-1", view) {
                throw IllegalStateException("setProfile failed")
            }
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertSame(view, active["account-1"])
    }

    @Test
    fun viewRemainsTrackedWhenCookieManagerIsMissing() {
        val active = linkedMapOf<String, FakeWebView>()
        val view = FakeWebView("cookie-manager-missing")
        val failure = runCatching {
            initializeTrackedWebView(active, "account-2", view) {
                throw IllegalStateException("cookie manager missing")
            }
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertSame(view, active["account-2"])
    }
}
