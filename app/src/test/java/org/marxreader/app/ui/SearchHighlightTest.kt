package org.marxreader.app.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchHighlightTest {
    @Test
    fun removesMarkupAndKeepsHighlightSpan() {
        val value = highlightedExcerpt("劳动创造<b>剩余价值</b>。", Color.Red)

        assertEquals("劳动创造剩余价值。", value.text)
        assertTrue(value.spanStyles.any { it.start == 4 && it.end == 8 })
    }
}
