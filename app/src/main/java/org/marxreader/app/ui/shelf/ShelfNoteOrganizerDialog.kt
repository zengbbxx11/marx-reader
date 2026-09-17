package org.marxreader.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.marxreader.app.data.*

@Composable
internal fun ShelfNoteOrganizerDialog(
    note: Note,
    onDismiss: () -> Unit,
    onSave: (Note) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var text by remember(note.id) { mutableStateOf(note.text) }
    var color by remember(note.id) { mutableStateOf(note.color) }
    var tagsText by remember(note.id) { mutableStateOf(note.tags.joinToString("，")) }
    var pinned by remember(note.id) { mutableStateOf(note.pinned) }
    var deleteArmed by remember(note.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("整理高亮与批注") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    note.excerpt,
                    maxLines = 7,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.background(
                        color.composeColor().copy(alpha = .25f), MaterialTheme.shapes.small
                    ).padding(10.dp)
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    HighlightColor.entries.forEach { item ->
                        Surface(
                            onClick = { color = item }, color = item.composeColor(),
                            shape = CircleShape, modifier = Modifier.size(40.dp)
                        ) {
                            if (color == item) Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Check, item.displayName, tint = androidx.compose.ui.graphics.Color.Black)
                            }
                        }
                    }
                }
                OutlinedTextField(
                    text, { text = it },
                    label = { Text("批注（留空则为纯高亮）") },
                    minLines = 3,
                    maxLines = 7,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                )
                OutlinedTextField(
                    tagsText, { tagsText = it }, label = { Text("标签") },
                    supportingText = { Text("用逗号分隔") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("置顶", Modifier.weight(1f))
                    Switch(pinned, { pinned = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(note.copy(
                    text = text.trim(),
                    kind = if (text.isBlank()) NoteKind.HIGHLIGHT else NoteKind.ANNOTATION,
                    color = color,
                    tags = normalizeNoteTags(listOf(tagsText)),
                    pinned = pinned,
                    updatedAt = System.currentTimeMillis()
                ))
            }) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = {
                    if (deleteArmed) onDelete() else deleteArmed = true
                }) {
                    Text(if (deleteArmed) "确认删除" else "删除", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}
