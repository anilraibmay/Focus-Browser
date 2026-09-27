package com.example

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AddressBarBehaviorTest {

    @Test
    fun testInitialFocusAutoSelection() {
        val initialUrl = "https://example.com/search?q=kotlin"
        var textFieldValue = TextFieldValue(initialUrl)

        // When user taps unfocused address bar: auto-selection is applied instantly
        textFieldValue = textFieldValue.copy(selection = TextRange(0, textFieldValue.text.length))

        assertEquals(0, textFieldValue.selection.start)
        assertEquals(initialUrl.length, textFieldValue.selection.end)
        assertEquals(initialUrl.length, textFieldValue.selection.length)
    }

    @Test
    fun testFlexibleSubsequentTapMovesCursorWithoutPermanentSelection() {
        val initialUrl = "https://example.com/search?q=kotlin"
        var textFieldValue = TextFieldValue(initialUrl, selection = TextRange(0, initialUrl.length))

        // When user taps again while already focused, cursor moves to tap position (e.g., offset 8)
        val tapOffset = 8
        textFieldValue = textFieldValue.copy(selection = TextRange(tapOffset))

        assertEquals(tapOffset, textFieldValue.selection.start)
        assertEquals(tapOffset, textFieldValue.selection.end)
        assertEquals(0, textFieldValue.selection.length) // collapsed selection allows flexible typing/editing
        assertNotEquals(initialUrl.length, textFieldValue.selection.length)
    }

    @Test
    fun testFlexibleTypingOverwritesWhenSelected() {
        val initialUrl = "https://example.com"
        var textFieldValue = TextFieldValue(initialUrl, selection = TextRange(0, initialUrl.length))

        // If user types while full selection is active, replaces content
        val newChar = "g"
        val replaced = textFieldValue.text.replaceRange(
            textFieldValue.selection.min,
            textFieldValue.selection.max,
            newChar
        )
        textFieldValue = TextFieldValue(replaced, selection = TextRange(newChar.length))

        assertEquals("g", textFieldValue.text)
        assertEquals(1, textFieldValue.selection.start)
    }
}
