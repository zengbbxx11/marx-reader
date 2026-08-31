package org.marxreader.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.marxreader.app.data.LibraryCatalog
import org.marxreader.app.data.ReadingStatistics
import java.time.LocalDate

@Composable
internal fun ReadingStatisticsView(statistics: ReadingStatistics, catalog: LibraryCatalog) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatisticCard("今日", formatDuration(statistics.todayMillis), Icons.Default.Schedule, Modifier.weight(1f))
            StatisticCard("近 7 天", formatDuration(statistics.lastSevenDaysMillis), Icons.Default.BarChart, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatisticCard("连续阅读", "${statistics.currentStreakDays} 天", Icons.Default.LocalFireDepartment, Modifier.weight(1f))
            StatisticCard("累计活跃", "${statistics.activeDays} 天", Icons.Default.Schedule, Modifier.weight(1f))
        }
        Text("最近 7 天", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
        Row(
            Modifier.fillMaxWidth().height(112.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            val maximum = statistics.daily.maxOfOrNull { it.activeMillis }?.coerceAtLeast(1) ?: 1
            statistics.daily.forEach { day ->
                val fraction = day.activeMillis.toFloat() / maximum
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(formatCompactDuration(day.activeMillis), style = MaterialTheme.typography.labelSmall)
                    Box(
                        Modifier.padding(top = 4.dp).fillMaxWidth().height((12 + 58 * fraction).dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = .28f + .52f * fraction))
                    )
                    Text(
                        LocalDate.ofEpochDay(day.epochDay).dayOfWeek.chineseShortName,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
        Text("作品排行", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
        if (statistics.books.isEmpty()) Text(
            "还没有可统计的有效阅读时长。",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        statistics.books.take(8).forEachIndexed { index, item ->
            ListItem(
                headlineContent = { Text(catalog.book(item.bookId)?.displayTitle ?: item.bookId) },
                supportingContent = { Text("${item.sessionCount} 次前台阅读") },
                leadingContent = { Text("${index + 1}", style = MaterialTheme.typography.titleMedium) },
                trailingContent = { Text(formatDuration(item.activeMillis)) }
            )
        }
        Text(
            "已读完 ${statistics.completedBooks} 部 · 累计 ${formatDuration(statistics.totalMillis)}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun StatisticCard(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier
) {
    Card(modifier) {
        Column(Modifier.padding(14.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Text(value, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

internal fun formatDuration(millis: Long): String {
    val minutes = (millis / 60_000).coerceAtLeast(0)
    return if (minutes < 60) "${minutes} 分钟" else "${minutes / 60} 小时 ${minutes % 60} 分"
}

private fun formatCompactDuration(millis: Long): String {
    val minutes = millis / 60_000
    return when {
        minutes <= 0 -> "0"
        minutes < 60 -> "${minutes}m"
        else -> "${minutes / 60}h"
    }
}

private val java.time.DayOfWeek.chineseShortName: String get() =
    listOf("一", "二", "三", "四", "五", "六", "日")[value - 1]
