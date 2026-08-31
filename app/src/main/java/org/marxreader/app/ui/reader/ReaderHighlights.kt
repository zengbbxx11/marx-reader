package org.marxreader.app.ui

import androidx.compose.ui.graphics.Color
import org.marxreader.app.data.HighlightColor

internal fun HighlightColor.composeColor(): Color = when (this) {
    HighlightColor.YELLOW -> Color(0xFFFFD54F)
    HighlightColor.RED -> Color(0xFFEF5350)
    HighlightColor.BLUE -> Color(0xFF42A5F5)
    HighlightColor.GREEN -> Color(0xFF66BB6A)
}

internal val HighlightColor.displayName: String get() = when (this) {
    HighlightColor.YELLOW -> "黄色"
    HighlightColor.RED -> "红色"
    HighlightColor.BLUE -> "蓝色"
    HighlightColor.GREEN -> "绿色"
}
