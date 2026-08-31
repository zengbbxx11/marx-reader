package org.marxreader.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.marxreader.app.ui.reader.ReaderSearchMatch
import org.marxreader.app.ui.reader.ReaderSearchScope
import org.marxreader.app.ui.reader.ReaderSearchState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderSearchSheet(
    state: ReaderSearchState,
    onQueryChange: (String) -> Unit,
    onScopeChange: (ReaderSearchScope) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSelect: (Int, ReaderSearchMatch) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Box(Modifier.fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
            Column(
                Modifier.fillMaxWidth().widthIn(max = 720.dp).fillMaxHeight(.88f)
                    .padding(horizontal = 18.dp)
            ) {
                Text("文内查找", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    placeholder = { Text("输入至少两个字符") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = state.scope == ReaderSearchScope.CHAPTER,
                        onClick = { onScopeChange(ReaderSearchScope.CHAPTER) },
                        label = { Text("本章") }
                    )
                    FilterChip(
                        selected = state.scope == ReaderSearchScope.BOOK,
                        onClick = { onScopeChange(ReaderSearchScope.BOOK) },
                        label = { Text("全书") }
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = onPrevious,
                        enabled = state.matches.isNotEmpty(),
                        modifier = Modifier.semantics { contentDescription = "上一个搜索结果" }
                    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                    IconButton(
                        onClick = onNext,
                        enabled = state.matches.isNotEmpty(),
                        modifier = Modifier.semantics { contentDescription = "下一个搜索结果" }
                    ) { Icon(Icons.AutoMirrored.Filled.ArrowForward, null) }
                }
                val status = when {
                    state.query.trim().length < 2 -> "至少输入两个字符"
                    state.searching -> "正在查找…"
                    state.matches.isEmpty() -> "没有找到匹配内容"
                    else -> "${state.selectedIndex + 1}/${state.matches.size} 处匹配"
                }
                Text(status, style = MaterialTheme.typography.labelMedium)
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 10.dp)
                ) {
                    itemsIndexed(
                        state.matches,
                        key = { index, match ->
                            "${match.chapterId}-${match.paragraphIndex}-${match.start}-$index"
                        }
                    ) { index, match ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    match.chapterTitle,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (index == state.selectedIndex) FontWeight.Bold else null
                                )
                            },
                            supportingContent = {
                                Text(match.excerpt, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = if (index == state.selectedIndex) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = .45f)
                                } else MaterialTheme.colorScheme.surface
                            ),
                            modifier = Modifier.clickable { onSelect(index, match) }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
