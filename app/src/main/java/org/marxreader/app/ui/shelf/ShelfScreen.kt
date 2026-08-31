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

@Composable
internal fun ShelfTab(
    catalog: LibraryCatalog,
    repository: LibraryRepository,
    navigate: (Screen) -> Unit,
    openLibrary: () -> Unit
) {
    var section by rememberSaveable { mutableStateOf(ShelfSection.RECENT) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedBookId by rememberSaveable { mutableStateOf<String?>(null) }
    var noteKindFilter by rememberSaveable { mutableStateOf<NoteKind?>(null) }
    var noteColorFilter by rememberSaveable { mutableStateOf<HighlightColor?>(null) }
    var selectedTag by rememberSaveable { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var bookMenuExpanded by remember { mutableStateOf(false) }
    var editingNote by remember { mutableStateOf<Note?>(null) }
    var deleteTarget by remember { mutableStateOf<ShelfDeleteTarget?>(null) }
    val scope = rememberCoroutineScope()
    var snapshot by remember(catalog) {
        mutableStateOf(Triple(emptyList<ReadingProgress>(), emptyList<Bookmark>(), emptyList<Note>()))
    }
    var statistics by remember { mutableStateOf(ReadingStatistics()) }
    LaunchedEffect(catalog, refreshKey) {
        val loaded = withContext(Dispatchers.IO) {
            Triple(
                repository.allProgress().values.sortedByDescending { it.updatedAt },
                repository.bookmarks(),
                repository.notes()
            ) to repository.readingStatistics()
        }
        snapshot = loaded.first
        statistics = loaded.second
    }
    val (progress, bookmarks, notes) = snapshot
    val availableBookIds = remember(snapshot) {
        (progress.map { it.bookId } + bookmarks.map { it.bookId } + notes.map { it.bookId }).distinct()
    }
    fun matches(bookId: String, chapterId: String, text: String): Boolean {
        if (selectedBookId != null && selectedBookId != bookId) return false
        val value = query.trim()
        if (value.isEmpty()) return true
        val book = catalog.book(bookId)
        val chapter = book?.chapters?.firstOrNull { it.id == chapterId }
        return listOf(book?.displayTitle.orEmpty(), chapter?.title.orEmpty(), text)
            .any { it.contains(value, ignoreCase = true) }
    }
    val visibleProgress = progress.filter { matches(it.bookId, it.chapterId, "") }
    val visibleBookmarks = bookmarks.filter { matches(it.bookId, it.chapterId, it.excerpt) }
    val allTags = remember(notes) { notes.flatMap { it.tags }.distinct().sorted() }
    val visibleNotes = notes.filter {
        matches(it.bookId, it.chapterId, "${it.excerpt} ${it.text} ${it.tags.joinToString()}") &&
            (noteKindFilter == null || it.kind == noteKindFilter) &&
            (noteColorFilter == null || it.color == noteColorFilter) &&
            (selectedTag == null || selectedTag in it.tags)
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        item {
            Text("我的书架", style = MaterialTheme.typography.headlineMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 14.dp)) {
                ShelfSection.entries.forEachIndexed { index, item ->
                    SegmentedButton(
                        selected = section == item,
                        onClick = { section = item },
                        shape = SegmentedButtonDefaults.itemShape(index, ShelfSection.entries.size)
                    ) {
                        Text(when (item) {
                            ShelfSection.RECENT -> "最近"
                            ShelfSection.BOOKMARKS -> "书签"
                            ShelfSection.NOTES -> "整理"
                            ShelfSection.STATISTICS -> "统计"
                        })
                    }
                }
            }
            OutlinedTextField(
                query, { query = it },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                placeholder = { Text("搜索作品、章节、摘录或笔记") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (query.isNotBlank()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "清空") } },
                singleLine = true
            )
            Box(Modifier.padding(top = 8.dp)) {
                FilterChip(
                    selected = selectedBookId != null,
                    onClick = { bookMenuExpanded = true },
                    label = { Text(selectedBookId?.let { catalog.book(it)?.displayTitle } ?: "全部作品") },
                    trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) }
                )
                DropdownMenu(bookMenuExpanded, { bookMenuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("全部作品") },
                        onClick = { selectedBookId = null; bookMenuExpanded = false }
                    )
                    availableBookIds.forEach { id ->
                        DropdownMenuItem(
                            text = { Text(catalog.book(id)?.displayTitle ?: id) },
                            onClick = { selectedBookId = id; bookMenuExpanded = false }
                        )
                    }
                }
            }
            if (section == ShelfSection.NOTES) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    FilterChip(noteKindFilter == null, { noteKindFilter = null }, { Text("全部") })
                    FilterChip(noteKindFilter == NoteKind.HIGHLIGHT, { noteKindFilter = NoteKind.HIGHLIGHT }, { Text("高亮") })
                    FilterChip(noteKindFilter == NoteKind.ANNOTATION, { noteKindFilter = NoteKind.ANNOTATION }, { Text("批注") })
                    HighlightColor.entries.forEach { color ->
                        FilterChip(
                            selected = noteColorFilter == color,
                            onClick = { noteColorFilter = if (noteColorFilter == color) null else color },
                            label = { Text(color.displayName) },
                            leadingIcon = {
                                Box(Modifier.size(12.dp).clip(RoundedCornerShape(50)).background(color.composeColor()))
                            }
                        )
                    }
                }
                if (allTags.isNotEmpty()) Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    allTags.forEach { tag ->
                        FilterChip(
                            selected = selectedTag == tag,
                            onClick = { selectedTag = if (selectedTag == tag) null else tag },
                            label = { Text("#$tag") }
                        )
                    }
                }
            }
        }
        when (section) {
            ShelfSection.RECENT -> {
                if (visibleProgress.isEmpty()) item {
                    EmptyShelfAction("没有符合条件的阅读记录", openLibrary)
                }
                items(visibleProgress, key = { "p-${it.bookId}" }) { item ->
                    val book = catalog.book(item.bookId)
                    val chapter = book?.chapters?.firstOrNull { it.id == item.chapterId }
                    Card(onClick = {
                        navigate(Screen.Reader(
                            item.bookId, item.chapterId, item.paragraphIndex,
                            item.characterOffset, item.completed
                        ))
                    }) {
                        ListItem(
                            headlineContent = { Text(book?.displayTitle ?: item.bookId) },
                            supportingContent = {
                                Text("${chapter?.title ?: item.chapterId} · 第 ${item.paragraphIndex + 1} 段\n${formatShelfTime(item.updatedAt)}")
                            },
                            leadingContent = { Icon(Icons.Default.History, null) },
                            trailingContent = {
                                IconButton(onClick = { deleteTarget = ShelfDeleteTarget.Progress(item.bookId) }) {
                                    Icon(Icons.Default.DeleteOutline, "删除阅读记录")
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
            }
            ShelfSection.BOOKMARKS -> {
                if (visibleBookmarks.isEmpty()) item { EmptyShelfAction("没有符合条件的书签", openLibrary) }
                items(visibleBookmarks, key = { "b-${it.id}" }) { item ->
                    val book = catalog.book(item.bookId)
                    val chapter = book?.chapters?.firstOrNull { it.id == item.chapterId }
                    Card(onClick = { navigate(Screen.Reader(item.bookId, item.chapterId, item.paragraphIndex)) }) {
                        Column(Modifier.fillMaxWidth().padding(15.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(book?.displayTitle ?: item.bookId, style = MaterialTheme.typography.titleSmall)
                                    Text(chapter?.title ?: item.chapterId, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                                }
                                IconButton(onClick = { deleteTarget = ShelfDeleteTarget.BookmarkItem(item.id) }) {
                                    Icon(Icons.Default.DeleteOutline, "删除书签")
                                }
                            }
                            Text(item.excerpt, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                            Text(formatShelfTime(item.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            ShelfSection.NOTES -> {
                if (visibleNotes.isEmpty()) item { EmptyShelfAction("没有符合条件的高亮或批注", openLibrary) }
                items(visibleNotes, key = { "n-${it.id}" }) { item ->
                    val book = catalog.book(item.bookId)
                    val chapter = book?.chapters?.firstOrNull { it.id == item.chapterId }
                    Card(onClick = {
                        navigate(Screen.Reader(
                            item.bookId, item.chapterId, item.paragraphIndex, item.selectionStart
                        ))
                    }) {
                        Column(Modifier.fillMaxWidth().padding(15.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(11.dp).clip(RoundedCornerShape(50)).background(item.color.composeColor()))
                                        Text(
                                            book?.displayTitle ?: item.bookId,
                                            style = MaterialTheme.typography.titleSmall,
                                            modifier = Modifier.padding(start = 7.dp)
                                        )
                                        if (item.pinned) Icon(Icons.Default.PushPin, "已置顶", Modifier.padding(start = 5.dp).size(16.dp))
                                    }
                                    Text(chapter?.title ?: item.chapterId, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                                }
                                IconButton(onClick = { editingNote = item }) { Icon(Icons.Default.Edit, "编辑笔记") }
                                IconButton(onClick = { deleteTarget = ShelfDeleteTarget.NoteItem(item.id) }) { Icon(Icons.Default.DeleteOutline, "删除笔记") }
                            }
                            if (item.kind == NoteKind.ANNOTATION) Text(
                                item.text, style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(top = 8.dp)
                            ) else Text(
                                "纯高亮", style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)
                            )
                            Text(
                                item.excerpt, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 7.dp)
                                    .background(item.color.composeColor().copy(alpha = .22f), MaterialTheme.shapes.small)
                                    .padding(8.dp)
                            )
                            if (item.tags.isNotEmpty()) Text(
                                item.tags.joinToString("  ") { "#$it" },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                            Text(
                                "${when (item.anchorState) { "RELOCATED" -> "已重定位"; "FALLBACK", "LEGACY" -> "段落级定位"; else -> "精确定位" }} · 创建于 ${formatShelfTime(item.createdAt)} · 更新于 ${formatShelfTime(item.updatedAt)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (item.anchorState == "FALLBACK") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            ShelfSection.STATISTICS -> item {
                ReadingStatisticsView(statistics, catalog)
            }
        }
    }

    editingNote?.let { note ->
        ShelfNoteOrganizerDialog(
            note = note,
            onDismiss = { editingNote = null },
            onSave = { updated ->
                scope.launch { repository.saveNote(updated); refreshKey++ }
                editingNote = null
            }
        )
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("确认删除") },
            text = { Text("此操作只删除这条本地记录，不会删除作品正文。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        when (target) {
                            is ShelfDeleteTarget.Progress -> repository.deleteProgress(target.bookId)
                            is ShelfDeleteTarget.BookmarkItem -> repository.deleteBookmark(target.id)
                            is ShelfDeleteTarget.NoteItem -> repository.deleteNote(target.id)
                        }
                        refreshKey++
                    }
                    deleteTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun EmptyShelfAction(text: String, openLibrary: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = openLibrary) { Text("去书库阅读") }
    }
}

private fun formatShelfTime(value: Long): String =
    android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", value).toString()
