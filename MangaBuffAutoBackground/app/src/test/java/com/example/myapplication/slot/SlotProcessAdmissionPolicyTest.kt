package com.example.myapplication.slot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotProcessAdmissionPolicyTest {
    private val base = "com.example.myapplication.background"

    @Test
    fun requiresTheExactSuffixForTheCurrentSlotProcess() {
        assertTrue(SlotProcessAdmissionPolicy.isReady("$base:slot0", true, "slot0"))
        assertTrue(SlotProcessAdmissionPolicy.isReady("$base:slot2", true, "slot2"))
        assertFalse(SlotProcessAdmissionPolicy.isReady("$base:slot0", true, "slot1"))
        assertFalse(SlotProcessAdmissionPolicy.isReady("$base:slot2", true, null))
    }

    @Test
    fun rejectsUnsupportedUnconfiguredAndNonSlotProcesses() {
        assertFalse(SlotProcessAdmissionPolicy.isReady("$base:slot1", false, "slot1"))
        assertFalse(SlotProcessAdmissionPolicy.isReady(base, true, "slot0"))
        assertFalse(SlotProcessAdmissionPolicy.isReady(null, true, "slot0"))
    }
}
