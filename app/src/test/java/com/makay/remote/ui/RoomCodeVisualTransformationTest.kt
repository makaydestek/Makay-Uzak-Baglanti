package com.makay.remote.ui

import androidx.compose.ui.text.AnnotatedString
import com.makay.remote.ui.screens.RoomCodeVisualTransformation
import org.junit.Assert.assertEquals
import org.junit.Test

class RoomCodeVisualTransformationTest {

    @Test
    fun insertsSpaceAfterFourCharsWithoutChangingOriginal() {
        val raw = AnnotatedString("X8W1GH5J")
        val t = RoomCodeVisualTransformation.filter(raw)
        assertEquals("X8W1 GH5J", t.text.text)
        // Imlec ham 5. karakterde iken gosterimde bosluktan sonra olmali
        assertEquals(6, t.offsetMapping.originalToTransformed(5))
        assertEquals(5, t.offsetMapping.transformedToOriginal(6))
    }

    @Test
    fun shortCodeHasNoSpace() {
        val t = RoomCodeVisualTransformation.filter(AnnotatedString("X8W1"))
        assertEquals("X8W1", t.text.text)
        assertEquals(4, t.offsetMapping.originalToTransformed(4))
    }
}
