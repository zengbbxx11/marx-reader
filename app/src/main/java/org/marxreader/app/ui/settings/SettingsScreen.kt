package org.marxreader.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.marxreader.app.data.*

private enum class ClearDataTarget(val label: String) {
    PROGRESS("阅读记录"), BOOKMARKS("书签"), NOTES("笔记")
}

@Composable
internal fun SettingsTab(repository: LibraryRepository, preferences: ReaderPreferences, settings: ReaderSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var clearTarget by remember { mutableStateOf<ClearDataTarget?>(null) }
    var aboutExpanded by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val version = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull().orEmpty()
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item { PageHeading("设置") }
            item {
                SettingsGroup("排版") {
                    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background,
                        shape = MaterialTheme.shapes.medium) {
                        Text("字号预览", modifier = Modifier.padding(horizontal = 18.dp, vertical = 24.dp),
                            fontSize = settings.fontSize.sp,
                            lineHeight = (settings.fontSize * settings.lineHeight).sp,
                            fontFamily = if (settings.fontFamily == ReaderFont.SERIF) FontFamily.Serif else FontFamily.SansSerif,
                            fontWeight = if (settings.fontWeight == ReaderFontWeight.MEDIUM) FontWeight.Medium else FontWeight.Normal)
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("字号", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(settings.fontSize.toInt().toString(), style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(value = settings.fontSize.coerceIn(15f, 32f),
                        onValueChange = { preferences.update(settings.copy(fontSize = it)) },
                        valueRange = 15f..32f, steps = 16)
                    SettingsSwitch("首行缩进", settings.firstLineIndent) {
                        preferences.update(settings.copy(firstLineIndent = it))
                    }
                    SettingsSwitch("横向翻页", settings.mode == ReadingMode.PAGE) {
                        preferences.update(settings.copy(mode = if (it) ReadingMode.PAGE else ReadingMode.SCROLL))
                    }
                }
            }
            item {
                SettingsGroup("外观") {
                    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ReaderTheme.entries.forEach { theme ->
                            val selected = settings.theme == theme
                            val swatch = when (theme) {
                                ReaderTheme.PAPER -> Color(0xFFF5F6F8)
                                ReaderTheme.SEPIA -> Color(0xFFF4EBD8)
                                ReaderTheme.DARK -> Color(0xFF101114)
                                ReaderTheme.SYSTEM -> MaterialTheme.colorScheme.secondaryContainer
                            }
                            Column(
                                Modifier.weight(1f).clip(MaterialTheme.shapes.small)
                                    .selectable(selected = selected, role = Role.RadioButton,
                                        onClick = { preferences.update(settings.copy(theme = theme)) })
                                    .padding(3.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(Modifier.fillMaxWidth().height(64.dp).clip(MaterialTheme.shapes.small)
                                    .background(swatch)
                                    .border(if (selected) 2.dp else 1.dp,
                                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                        MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
                                    if (selected) Surface(shape = RoundedCornerShape(50),
                                        color = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary) {
                                        Icon(Icons.Default.Check, null, Modifier.padding(3.dp).size(18.dp))
                                    }
                                }
                                Text(when (theme) {
                                    ReaderTheme.PAPER -> "浅色"
                                    ReaderTheme.SEPIA -> "护眼"
                                    ReaderTheme.DARK -> "深色"
                                    ReaderTheme.SYSTEM -> "系统"
                                }, style = MaterialTheme.typography.labelMedium,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 9.dp, bottom = 5.dp))
                            }
                        }
                    }
                }
            }
            item {
                SettingsGroup("本地阅读数据") {
                    ClearDataTarget.entries.forEachIndexed { index, target ->
                        Row(Modifier.fillMaxWidth().clickable { clearTarget = target }
                            .padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("清除${target.label}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (index < ClearDataTarget.entries.lastIndex) HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
            item {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().clickable { aboutExpanded = !aboutExpanded }.padding(18.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("关于", style = MaterialTheme.typography.titleSmall)
                                Text(version, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(if (aboutExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                if (aboutExpanded) "收起关于" else "展开关于")
                        }
                        if (aboutExpanded) Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp)) {
                            Text("内容来源：Marxists Internet Archive。非官方客户端。",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("阅读数据仅保存在本机，不支持备份或迁移。",
                                modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = {
                                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${context.packageName}")))
                            }) { Text("系统应用信息") }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
    clearTarget?.let { target ->
        AlertDialog(onDismissRequest = { clearTarget = null },
            title = { Text("清除${target.label}？") },
            text = { Text("此操作无法撤销，作品正文不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    clearTarget = null
                    scope.launch {
                        readerOperation {
                            when (target) {
                                ClearDataTarget.PROGRESS -> repository.clearProgress()
                                ClearDataTarget.BOOKMARKS -> repository.clearBookmarks()
                                ClearDataTarget.NOTES -> repository.clearNotes()
                            }
                        }.onSuccess { snackbar.showSnackbar("已清除${target.label}") }
                            .onFailure { snackbar.showSnackbar("清理失败，请重试") }
                    }
                }) { Text("清除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { clearTarget = null }) { Text("取消") } })
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(18.dp), content = content)
        }
    }
}

@Composable
private fun SettingsSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
