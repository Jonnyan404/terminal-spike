package com.yanjiyu.terminalspike.ui.settings

import com.yanjiyu.terminalspike.core.model.KeyboardAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class KeyboardActionReplacementTest {
    @Test
    fun replacementKeepsTheExactPositionAndCount() {
        val original = KeyboardPresets.general.actions

        val replaced = replaceKeyboardActionAt(
            actions = original,
            index = 0,
            replacement = KeyboardAction.F1,
        )

        assertEquals(original.size, replaced.size)
        assertEquals(KeyboardAction.F1, replaced[0])
        assertEquals(original.drop(1), replaced.drop(1))
    }

    @Test
    fun sameActionIsNoOpAndAnExistingActionKeepsOneCopy() {
        val original = KeyboardPresets.general.actions

        assertSame(original, replaceKeyboardActionAt(original, 0, original[0]))
        // Replacing a key with one that already exists must not move the old key to another slot.
        val replaced = replaceKeyboardActionAt(original, 0, original[1])
        assertEquals(original[1], replaced[0])
        assertEquals(original.size - 1, replaced.size)
        assertEquals(original.drop(2), replaced.drop(1))
    }

    @Test
    fun outOfRangePositionIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            replaceKeyboardActionAt(KeyboardPresets.general.actions, -1, KeyboardAction.F1)
        }
    }
}
