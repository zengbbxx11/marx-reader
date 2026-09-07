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
internal fun PageHeading(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = 8.dp, bottom = 8.dp)) {
        Box(Modifier.width(28.dp).height(3.dp).clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.primary))
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 12.dp))
        Text(subtitle, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp))
    }
}

@Composable
internal fun LibraryOverview(authorCount: Int, bookCount: Int) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f))
    ) {
        Column(Modifier.fillMaxWidth().padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(3.dp).height(13.dp).background(MaterialTheme.colorScheme.primary))
                Text("马 · 恩 · 列 · 斯 · 毛", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 9.dp))
                Spacer(Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.MenuBook, null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            }
            Text("回到原典，\n让阅读更深入。", style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = 20.dp, bottom = 12.dp))
            Text("从一篇文章开始，与经典长久相伴。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(Modifier.padding(vertical = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                LibraryMetric(authorCount.toString(), "经典作者", Modifier.weight(1f))
                LibraryMetric(bookCount.toString(), "全部作品", Modifier.weight(1f))
                LibraryMetric("离线", "随时翻阅", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun LibraryMetric(value: String, label: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold,
            fontSize = 23.sp, lineHeight = 30.sp, color = MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
internal fun BookCoverMark(label: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(topStart = 3.dp, bottomStart = 3.dp, topEnd = 9.dp, bottomEnd = 9.dp),
        color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(5.dp).fillMaxHeight().background(Color.Black.copy(alpha = .16f)))
            Column(Modifier.weight(1f).fillMaxHeight().padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(label.take(1), fontFamily = FontFamily.Serif, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Box(Modifier.padding(top = 7.dp).width(14.dp).height(1.dp)
                    .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = .5f)))
            }
        }
    }
}

@Composable
internal fun ShelfSummary(reading: Int, bookmarks: Int, notes: Int) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))) {
        Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LibraryMetric(reading.toString(), "阅读记录", Modifier.weight(1f))
            LibraryMetric(bookmarks.toString(), "书签", Modifier.weight(1f))
            LibraryMetric(notes.toString(), "高亮与批注", Modifier.weight(1f))
        }
    }
}
