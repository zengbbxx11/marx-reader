package org.marxreader.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.marxreader.app.data.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderStyleSheet(
    settings: ReaderSettings,
    update: (ReaderSettings) -> Unit,
    dismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = dismiss) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.fillMaxWidth().widthIn(max = 720.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp).padding(bottom = 34.dp)
            ) {
                Text("阅读排版", style = MaterialTheme.typography.headlineSmall)
                Text("排版预设", modifier = Modifier.padding(top = 12.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ReaderLayoutPreset.entries.filter { it != ReaderLayoutPreset.CUSTOM }.forEach { preset ->
                        FilterChip(
                            selected = settings.layoutPreset == preset,
                            onClick = { update(settings.applyPreset(preset)) },
                            label = { Text(preset.label) }
                        )
                    }
                }
                Text("阅读方式", modifier = Modifier.padding(top = 12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.mode == ReadingMode.PAGE,
                        onClick = { update(settings.copy(mode = ReadingMode.PAGE)) },
                        label = { Text("左右翻页") },
                        leadingIcon = { Icon(Icons.Default.SwapHoriz, null) }
                    )
                    FilterChip(
                        selected = settings.mode == ReadingMode.SCROLL,
                        onClick = { update(settings.copy(mode = ReadingMode.SCROLL)) },
                        label = { Text("连续滚动") },
                        leadingIcon = { Icon(Icons.Default.SwapVert, null) }
                    )
                }
                Text("字体", modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReaderFont.entries.forEach { font ->
                        FilterChip(
                            selected = settings.fontFamily == font,
                            onClick = { update(settings.copy(fontFamily = font)) },
                            label = { Text(if (font == ReaderFont.SERIF) "衬线" else "无衬线") }
                        )
                    }
                    ReaderFontWeight.entries.forEach { weight ->
                        FilterChip(
                            selected = settings.fontWeight == weight,
                            onClick = { update(settings.copy(fontWeight = weight)) },
                            label = { Text(if (weight == ReaderFontWeight.REGULAR) "常规" else "中等") }
                        )
                    }
                }
                SettingSlider(
                    "字号 ${settings.fontSize.toInt()}", settings.fontSize, 15f..32f, 16,
                    { update(settings.copy(fontSize = it)) }
                )
                SettingSlider(
                    "行距 ${"%.1f".format(settings.lineHeight)}", settings.lineHeight, 1.3f..2.2f, 8,
                    { update(settings.copy(lineHeight = it)) }
                )
                SettingSlider(
                    "段距 ${"%.2f".format(settings.paragraphSpacing)}", settings.paragraphSpacing, .35f..1.25f, 8,
                    { update(settings.copy(paragraphSpacing = it)) }
                )
                SettingSlider(
                    "左右留白 ${settings.horizontalPadding}", settings.horizontalPadding.toFloat(), 12f..42f, 14,
                    { update(settings.copy(horizontalPadding = it.toInt())) }
                )
                SettingSlider(
                    "上下留白 ${settings.verticalPadding}", settings.verticalPadding.toFloat(), 12f..48f, 17,
                    { update(settings.copy(verticalPadding = it.toInt())) }
                )
                Text("主题", modifier = Modifier.padding(top = 8.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ReaderTheme.entries.forEach { theme ->
                        FilterChip(
                            selected = settings.theme == theme,
                            onClick = { update(settings.copy(theme = theme)) },
                            label = { Text(theme.label) }
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("使用系统亮度", Modifier.weight(1f))
                    Switch(
                        checked = settings.brightnessMode == ReaderBrightnessMode.SYSTEM,
                        onCheckedChange = {
                            update(settings.copy(
                                brightnessMode = if (it) ReaderBrightnessMode.SYSTEM else ReaderBrightnessMode.CUSTOM
                            ))
                        }
                    )
                }
                if (settings.brightnessMode == ReaderBrightnessMode.CUSTOM) SettingSlider(
                    "阅读亮度 ${(settings.brightness * 100).toInt()}%",
                    settings.brightness, .05f..1f, 18,
                    { update(settings.copy(brightness = it)) }
                )
                SettingSwitch("正文首行缩进两格", settings.firstLineIndent) {
                    update(settings.copy(firstLineIndent = it))
                }
                SettingSwitch("阅读时保持屏幕常亮", settings.keepScreenOn) {
                    update(settings.copy(keepScreenOn = it))
                }
            }
        }
    }
}

@Composable
private fun SettingSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    update: (Float) -> Unit
) {
    Text(label, modifier = Modifier.padding(top = 8.dp))
    Slider(
        value = value,
        onValueChange = update,
        valueRange = range,
        steps = steps,
        modifier = Modifier.semantics { contentDescription = label }
    )
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, update: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, update)
    }
}

private val ReaderLayoutPreset.label: String get() = when (this) {
    ReaderLayoutPreset.DEFAULT -> "默认"
    ReaderLayoutPreset.COMPACT -> "紧凑"
    ReaderLayoutPreset.RELAXED -> "舒展"
    ReaderLayoutPreset.LARGE -> "大字"
    ReaderLayoutPreset.CUSTOM -> "自定义"
}

private val ReaderTheme.label: String get() = when (this) {
    ReaderTheme.PAPER -> "浅色"
    ReaderTheme.SEPIA -> "护眼"
    ReaderTheme.DARK -> "深色"
    ReaderTheme.SYSTEM -> "系统"
}
