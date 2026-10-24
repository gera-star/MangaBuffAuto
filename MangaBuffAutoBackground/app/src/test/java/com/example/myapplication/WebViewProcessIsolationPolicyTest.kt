package com.example.myapplication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebViewProcessIsolationPolicyTest {
    private val base = "com.example.myapplication.background"

    @Test
    fun mapsOnlyDeclaredSlotWorkerAndViewerProcesses() {
        assertEquals("slot0", WebViewProcessIsolationPolicy.suffixFor("$base:slot0"))
        assertEquals("slot2", WebViewProcessIsolationPolicy.suffixFor("$base:slot2"))
        assertEquals("w0", WebViewProcessIsolationPolicy.suffixFor("$base:w0"))
        assertEquals("w3", WebViewProcessIsolationPolicy.suffixFor("$base:w3"))
        assertEquals("webviewer", WebViewProcessIsolationPolicy.suffixFor("$base:webviewer"))
    }

    @Test
    fun rejectsMainUnknownMalformedAndOutOfRangeProcessNames() {
        assertNull(WebViewProcessIsolationPolicy.suffixFor(base))
        assertNull(WebViewProcessIsolationPolicy.suffixFor(null))
        assertNull(WebViewProcessIsolationPolicy.suffixFor(""))
        assertNull(WebViewProcessIsolationPolicy.suffixFor("$base:slot3"))
        assertNull(WebViewProcessIsolationPolicy.suffixFor("$base:slot00"))
        assertNull(WebViewProcessIsolationPolicy.suffixFor("$base:w4"))
        assertNull(WebViewProcessIsolationPolicy.suffixFor("$base:worker0"))
        assertNull(WebViewProcessIsolationPolicy.suffixFor("$base:other:webviewer"))
    }
}
