@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.marxreader.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.Html
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntSize
import androidx.core.text.HtmlCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.marxreader.app.data.*
import org.marxreader.app.R
import org.marxreader.app.ui.components.TocTree
import org.marxreader.app.ui.reader.ReaderViewModel

private enum class ClearDataTarget { PROGRESS, BOOKMARKS, NOTES }

@Composable
internal fun SettingsTab(repository: LibraryRepository, preferences: ReaderPreferences, settings: ReaderSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var clearTarget by remember { mutableStateOf<ClearDataTarget?>(null) }
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0.4.1"
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { SectionTitle("本地阅读数据", "阅读记录、书签和笔记只保存在本机") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("清理本机数据", style = MaterialTheme.typography.titleSmall)
                    Text("每项操作都会再次确认，不影响内置正文。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { clearTarget = ClearDataTarget.PROGRESS }) { Text("阅读记录") }
                        TextButton(onClick = { clearTarget = ClearDataTarget.BOOKMARKS }) { Text("全部书签") }
                        TextButton(onClick = { clearTarget = ClearDataTarget.NOTES }) { Text("全部笔记") }
                    }
                }
            }
        }
        item { SectionTitle("阅读设置", "调整后会自动保存到本机") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    ) {
                        Text(
                            "读书须用意，一字值千金。",
                            fontFamily = FontFamily.Serif,
                            fontSize = settings.fontSize.sp,
                            lineHeight = (settings.fontSize * settings.lineHeight).sp,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("正文字号", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        FilledTonalIconButton(onClick = { preferences.update(settings.copy(fontSize = (settings.fontSize - 1).coerceAtLeast(15f))) }) { Text("A−") }
                        Text("${settings.fontSize.toInt()}", modifier = Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.titleSmall)
                        FilledTonalIconButton(onClick = { preferences.update(settings.copy(fontSize = (settings.fontSize + 1).coerceAtMost(32f))) }) { Text("A+") }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("首行缩进两格", Modifier.weight(1f))
                        Switch(settings.firstLineIndent, { preferences.update(settings.copy(firstLineIndent = it)) })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("默认左右翻页", Modifier.weight(1f))
                        Switch(settings.mode == ReadingMode.PAGE, {
                            preferences.update(settings.copy(mode = if (it) ReadingMode.PAGE else ReadingMode.SCROLL))
                        })
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Default.Palette)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text("阅读主题", style = MaterialTheme.typography.titleSmall)
                            Text("选择最舒适的纸张与光线", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        ReaderTheme.entries.forEach { theme ->
                            FilterChip(
                                selected = settings.theme == theme,
                                onClick = { preferences.update(settings.copy(theme = theme)) },
                                label = { Text(when (theme) { ReaderTheme.PAPER -> "纸白"; ReaderTheme.SEPIA -> "护眼"; ReaderTheme.DARK -> "夜间"; ReaderTheme.SYSTEM -> "系统" }) }
                            )
                        }
                    }
                }
            }
        }
        item { SectionTitle("隐私与应用", "无账户、无广告、无网络权限") }
        item {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                Row(Modifier.padding(17.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.secondary)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text("完全离线", style = MaterialTheme.typography.titleSmall)
                        Text("应用不联网，也不会上传、导入或导出正文及阅读数据。系统备份和设备迁移均已关闭。", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 3.dp))
                        Text("数据来源：Marxists Internet Archive。非官方阅读客户端。", color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = .72f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 7.dp))
                    }
                }
            }
        }
        item {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(17.dp)) {
                    Text("版本 $versionName", style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.app_subtitle), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    Text("内置 5 位作者 · 650 部作品 · 884 章节", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Text("专注内置文库的离线阅读、搜索、书签和精确笔记。", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 5.dp))
                }
            }
        }
        item {
            SettingCard(Icons.Default.Info, "系统应用信息", "管理本机存储与应用权限") {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }
        }
        message?.let { value -> item { AssistChip(onClick = { message = null }, label = { Text(value) }, leadingIcon = { Icon(Icons.Default.CheckCircle, null) }) } }
    }

    clearTarget?.let { target ->
        val label = when (target) { ClearDataTarget.PROGRESS -> "全部阅读记录"; ClearDataTarget.BOOKMARKS -> "全部书签"; ClearDataTarget.NOTES -> "全部笔记" }
        AlertDialog(
            onDismissRequest = { clearTarget = null },
            title = { Text("清除$label") },
            text = { Text("该操作只清除本机数据，且无法撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        when (target) {
                            ClearDataTarget.PROGRESS -> repository.clearProgress()
                            ClearDataTarget.BOOKMARKS -> repository.clearBookmarks()
                            ClearDataTarget.NOTES -> repository.clearNotes()
                        }
                        message = "已清除$label"
                    }
                    clearTarget = null
                }) { Text("确认清除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { clearTarget = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun SettingCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        ListItem(
            headlineContent = { Text(title, fontWeight = FontWeight.SemiBold) },
            supportingContent = { Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            leadingContent = { IconBadge(icon) },
            trailingContent = { Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.outline) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}
