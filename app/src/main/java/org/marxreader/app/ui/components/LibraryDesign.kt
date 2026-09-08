package org.marxreader.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun PageHeading(title: String, modifier: Modifier = Modifier, subtitle: String = "") {
    Column(modifier.padding(top = 8.dp, bottom = 12.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
internal fun LibraryOverview(authorCount: Int, bookCount: Int) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("书库", style = MaterialTheme.typography.headlineLarge)
        Text("$authorCount 位作者 · $bookCount 部作品", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun LibraryMetric(value: String, label: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
            fontSize = 23.sp, lineHeight = 30.sp, color = MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
internal fun BookCoverMark(label: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Box(contentAlignment = Alignment.Center) {
            Text(label.firstOrNull { it.isLetterOrDigit() }?.toString().orEmpty(),
                fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun ShelfSummary(reading: Int, bookmarks: Int, notes: Int) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
        Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LibraryMetric(reading.toString(), "阅读记录", Modifier.weight(1f))
            LibraryMetric(bookmarks.toString(), "书签", Modifier.weight(1f))
            LibraryMetric(notes.toString(), "高亮与批注", Modifier.weight(1f))
        }
    }
}
