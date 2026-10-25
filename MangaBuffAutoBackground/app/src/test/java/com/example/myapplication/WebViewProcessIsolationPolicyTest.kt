package com.example.myapplication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebViewProcessIsolationPolicyTest {
    private val applicationId = "com.example.myapplication.background"

    @Test
    fun mapsOnlyDeclaredSlotWorkerAndViewerProcesses() {
        assertEquals("slot0", WebViewProcessIsolationPolicy.suffixFor("$applicationId:slot0", applicationId))
        assertEquals("slot1", WebViewProcessIsolationPolicy.suffixFor("$applicationId:slot1", applicationId))
        assertEquals("slot2", WebViewProcessIsolationPolicy.suffixFor("$applicationId:slot2", applicationId))
        assertEquals("w0", WebViewProcessIsolationPolicy.suffixFor("$applicationId:w0", applicationId))
        assertEquals("w1", WebViewProcessIsolationPolicy.suffixFor("$applicationId:w1", applicationId))
        assertEquals("w2", WebViewProcessIsolationPolicy.suffixFor("$applicationId:w2", applicationId))
        assertEquals("w3", WebViewProcessIsolationPolicy.suffixFor("$applicationId:w3", applicationId))
        assertEquals("webviewer", WebViewProcessIsolationPolicy.suffixFor("$applicationId:webviewer", applicationId))
    }

    @Test
    fun rejectsMainUnknownMalformedAndOutOfRangeProcessNames() {
        assertNull(WebViewProcessIsolationPolicy.suffixFor(applicationId, applicationId))
        assertNull(WebViewProcessIsolationPolicy.suffixFor(null, applicationId))
        assertNull(WebViewProcessIsolationPolicy.suffixFor("", applicationId))
        assertNull(WebViewProcessIsolationPolicy.suffixFor("$applicationId:slot3", applicationId))
        assertNull(WebViewProcessIsolationPolicy.suffixFor("$applicationId:slot00", applicationId))
        assertNull(WebViewProcessIsolationPolicy.suffixFor("$applicationId:w4", applicationId))
        assertNull(WebViewProcessIsolationPolicy.suffixFor("$applicationId:worker0", applicationId))
        assertNull(WebViewProcessIsolationPolicy.suffixFor("$applicationId:other:webviewer", applicationId))
        assertNull(WebViewProcessIsolationPolicy.suffixFor("com.example.myapplication.background.evil:slot0", applicationId))
        assertNull(WebViewProcessIsolationPolicy.suffixFor("com.other.app:slot0", applicationId))
        assertNull(WebViewProcessIsolationPolicy.suffixFor("$applicationId:", applicationId))
        assertNull(WebViewProcessIsolationPolicy.suffixFor("$applicationId:slot0", ""))
    }
}
