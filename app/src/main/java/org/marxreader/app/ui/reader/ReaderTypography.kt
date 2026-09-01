package org.marxreader.app.ui

import android.graphics.Typeface
import android.os.Build
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

internal fun readerTypeface(
    fontFamily: ReaderFont?,
    fontWeight: ReaderFontWeight?,
    bold: Boolean = false
): Typeface {
    val familyName = when (fontFamily) {
        ReaderFont.SANS_SERIF -> "sans-serif"
        else -> "serif"
    }
    val weight = if (bold) 700 else when (fontWeight) {
        ReaderFontWeight.MEDIUM -> 500
        else -> 400
    }
    val legacyStyle = if (weight >= 600) Typeface.BOLD else Typeface.NORMAL
    val requested = runCatching {
        val base = Typeface.create(familyName, legacyStyle)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(base, weight, false)
        } else {
            base
        }
    }.getOrNull()
    return requested ?: runCatching {
        Typeface.create(Typeface.DEFAULT, legacyStyle)
    }.getOrNull() ?: Typeface.DEFAULT
}
