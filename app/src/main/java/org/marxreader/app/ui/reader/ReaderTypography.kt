package org.marxreader.app.ui

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.marxreader.app.data.ReaderFont
import org.marxreader.app.data.ReaderFontWeight

internal val ReaderFont.composeFontFamily: FontFamily
    get() = when (this) {
        ReaderFont.SERIF -> FontFamily.Serif
        ReaderFont.SANS_SERIF -> FontFamily.SansSerif
    }

internal val ReaderFontWeight.composeFontWeight: FontWeight
    get() = when (this) {
        ReaderFontWeight.REGULAR -> FontWeight.Normal
        ReaderFontWeight.MEDIUM -> FontWeight.Medium
    }
