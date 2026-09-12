package org.marxreader.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderSettingsTest {
    @Test
    fun presetsAreDetectedAndCanBeAppliedWithoutChangingThemeOrMode() {
        val original = ReaderSettings(theme = ReaderTheme.DARK, mode = ReadingMode.SCROLL)
        val relaxed = original.applyPreset(ReaderLayoutPreset.RELAXED)

        assertEquals(ReaderLayoutPreset.RELAXED, relaxed.layoutPreset)
        assertEquals(ReaderTheme.DARK, relaxed.theme)
        assertEquals(ReadingMode.SCROLL, relaxed.mode)
    }

    @Test
    fun manualLayoutChangeBecomesCustom() {
        assertEquals(
            ReaderLayoutPreset.CUSTOM,
            ReaderSettings().copy(paragraphSpacing = .83f).layoutPreset
        )
    }

    @Test fun corruptNumericSettingsFallBackWithoutAffectingValidFields() {
        val actual = ReaderSettings(fontSize = Float.NaN, lineHeight = Float.POSITIVE_INFINITY,
            paragraphSpacing = -1f, horizontalPadding = Int.MAX_VALUE, verticalPadding = -1,
            brightness = Float.NaN, theme = ReaderTheme.DARK).validated()
        org.junit.Assert.assertEquals(ReaderSettings(theme = ReaderTheme.DARK), actual)
    }

    @Test fun allPresetsAndSupportedBoundariesSurviveValidation() {
        ReaderLayoutPreset.values().filter { it != ReaderLayoutPreset.CUSTOM }.forEach { preset ->
            val value = ReaderSettings().applyPreset(preset)
            org.junit.Assert.assertEquals(value, value.validated())
        }
        val low = ReaderSettings(fontSize = 15f, lineHeight = 1.3f, paragraphSpacing = .35f,
            horizontalPadding = 12, verticalPadding = 12, brightness = .05f)
        val high = ReaderSettings(fontSize = 32f, lineHeight = 2.2f, paragraphSpacing = 1.25f,
            horizontalPadding = 42, verticalPadding = 48, brightness = 1f)
        org.junit.Assert.assertEquals(low, low.validated())
        org.junit.Assert.assertEquals(high, high.validated())
    }
}
