package org.marxreader.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.marxreader.app.data.HighlightColor
import org.marxreader.app.data.NoteKind
import org.marxreader.app.data.normalizeNoteTags

internal data class AnnotationEditorValue(
    val text: String,
    val color: HighlightColor,
    val tags: List<String>,
    val pinned: Boolean
) {
    val kind: NoteKind get() = if (text.isBlank()) NoteKind.HIGHLIGHT else NoteKind.ANNOTATION
}

@Composable
internal fun ReaderAnnotationEditor(
    target: NoteDraftTarget,
    onDismiss: () -> Unit,
    onSave: (AnnotationEditorValue) -> Unit,
    onBookmark: () -> Unit,
    onDelete: (() -> Unit)?
) {
    var text by remember(target.existing?.id, target.selection) {
        mutableStateOf(target.existing?.text.orEmpty())
    }
    var color by remember(target.existing?.id) {
        mutableStateOf(target.existing?.color ?: HighlightColor.YELLOW)
    }
    var tagsText by remember(target.existing?.id) {
        mutableStateOf(target.existing?.tags?.joinToString("，").orEmpty())
    }
    var pinned by remember(target.existing?.id) {
        mutableStateOf(target.existing?.pinned == true)
    }
    var deleteArmed by remember(target.existing?.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (target.existing == null) "保存高亮或批注" else "编辑高亮与批注") },
        text = {
            Column {
                Text(
                    target.selection.text.take(500),
                    maxLines = 7,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.background(
                        color.composeColor().copy(alpha = .24f),
                        MaterialTheme.shapes.small
                    ).padding(10.dp)
                )
                Text("高亮颜色", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 14.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    HighlightColor.entries.forEach { item ->
                        Surface(
                            onClick = { color = item },
                            shape = CircleShape,
                            color = item.composeColor(),
                            modifier = Modifier.size(42.dp)
                        ) {
                            if (color == item) Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Check, item.displayName, tint = androidx.compose.ui.graphics.Color.Black)
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("批注（可留空，仅保存高亮）") },
                    minLines = 3,
                    maxLines = 7,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                )
                OutlinedTextField(
                    value = tagsText,
                    onValueChange = { tagsText = it },
                    label = { Text("标签") },
                    supportingText = { Text("用逗号分隔，最多 12 个") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("置顶这条记录", Modifier.weight(1f))
                    Switch(pinned, { pinned = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(AnnotationEditorValue(text.trim(), color, normalizeNoteTags(listOf(tagsText)), pinned))
            }) { Text(if (text.isBlank()) "保存高亮" else "保存批注") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = {
                    if (deleteArmed) onDelete() else deleteArmed = true
                }) { Text(if (deleteArmed) "确认删除" else "删除", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onBookmark) { Text("另加书签") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}
