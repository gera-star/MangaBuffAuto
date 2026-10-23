package com.example.myapplication.slot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotLauncherTest {
    @Test fun slotIndexParsedFromProcessName() {
        assertEquals(2, SlotLauncher.slotIndexOf("com.example.myapplication.background:slot2"))
        assertNull(SlotLauncher.slotIndexOf("com.example.myapplication.background"))
        assertNull(SlotLauncher.slotIndexOf("com.example.myapplication.background:slot9"))
        assertNull(SlotLauncher.slotIndexOf(null))
    }

    @Test fun onlyThreeFixedSlotsAreValid() {
        assertEquals(3, SlotLauncher.SLOT_COUNT)
        assertTrue(SlotLauncher.isValidSlot(0))
        assertTrue(SlotLauncher.isValidSlot(1))
        assertTrue(SlotLauncher.isValidSlot(2))
        assertFalse(SlotLauncher.isValidSlot(-1))
        assertFalse(SlotLauncher.isValidSlot(3))
    }

    @Test fun tabIdAndProfileName() {
        assertTrue(SlotLauncher.isValidTabId("3f2a-9B_x"))
        assertFalse(SlotLauncher.isValidTabId("../evil"))
        assertFalse(SlotLauncher.isValidTabId(""))
        assertEquals("tab_abc", SlotLauncher.profileName("abc"))
    }
}
