package com.example.myapplication.automation

import android.webkit.WebSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewContainerPrivacyPolicyTest {
    @Test
    fun defaultsKeepRequiredSiteFeaturesAndDisableRiskyWebSettings() {
        val config = WebViewContainerPrivacyConfig()
        assertTrue(config.javaScriptEnabled)
        assertTrue(config.domStorageEnabled)
        assertFalse(config.acceptThirdPartyCookies)
        assertEquals(WebSettings.LOAD_DEFAULT, config.cacheMode)
    }
    @Test
    fun profileNamesMustBeStableAndSafeForAndroidXProfileApi() {
        assertTrue(ProfileWebViewFactory.isValidProfileNameForTest("mb_account_123"))
        assertTrue(ProfileWebViewFactory.isValidProfileNameForTest("tab-slot_2"))
        assertFalse(ProfileWebViewFactory.isValidProfileNameForTest("profile with spaces"))
        assertFalse(ProfileWebViewFactory.isValidProfileNameForTest(""))
        assertFalse(ProfileWebViewFactory.isValidProfileNameForTest("x".repeat(65)))
    }

}
