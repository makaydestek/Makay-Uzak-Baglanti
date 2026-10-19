package com.makay.remote.ui.screens

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Kimlik alaninda ham deger `X8W1GH5J` tutulur; ekranda `X8W1 GH5J` gosterilir.
 * Boslugu metne YAZMADAN gosterdigi icin imlec geri zıplamaz / harf kaymaz.
 */
object RoomCodeVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val out = if (raw.length <= 4) raw
        else raw.substring(0, 4) + " " + raw.substring(4)

        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                val o = offset.coerceIn(0, raw.length)
                return if (o <= 4) o else o + 1
            }

            override fun transformedToOriginal(offset: Int): Int {
                val t = offset.coerceIn(0, out.length)
                return when {
                    t <= 4 -> t
                    else -> (t - 1).coerceAtMost(raw.length)
                }.coerceIn(0, raw.length)
            }
        }
        return TransformedText(AnnotatedString(out), mapping)
    }
}
