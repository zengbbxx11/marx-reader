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
}
