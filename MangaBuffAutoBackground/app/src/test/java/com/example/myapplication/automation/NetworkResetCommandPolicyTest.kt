package com.example.myapplication.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkResetCommandPolicyTest {
    @Test
    fun allowsOnlyFixedAirplaneModeCommands() {
        assertEquals(
            listOf("cmd", "connectivity", "airplane-mode", "enable"),
            NetworkResetCommandPolicy.argumentsFor("cmd connectivity airplane-mode enable")
        )
        assertEquals(
            listOf("cmd", "connectivity", "airplane-mode", "disable"),
            NetworkResetCommandPolicy.argumentsFor("cmd connectivity airplane-mode disable")
        )
    }

    @Test
    fun rejectsArbitraryCommandsAndShellInjection() {
        assertNull(NetworkResetCommandPolicy.argumentsFor("id"))
        assertNull(NetworkResetCommandPolicy.argumentsFor("settings put global airplane_mode_on 1"))
        assertNull(NetworkResetCommandPolicy.argumentsFor("cmd connectivity airplane-mode enable; id"))
        assertNull(NetworkResetCommandPolicy.argumentsFor("cmd connectivity airplane-mode disable && id"))
        assertNull(NetworkResetCommandPolicy.argumentsFor("cmd connectivity airplane-mode enable\nid"))
    }
}
