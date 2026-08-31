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
internal fun SearchTab(
    catalog: LibraryCatalog,
    repository: LibraryRepository,
    preferences: ReaderPreferences,
    navigate: (Screen) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var searchScope by rememberSaveable { mutableStateOf(SearchScope.ALL) }
    var authorId by rememberSaveable { mutableStateOf<String?>(null) }
    var authorMenuExpanded by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableIntStateOf(0) }
    val indexState by repository.searchIndexState.collectAsState()
    val searchHistory by preferences.searchHistory.collectAsState()
    LaunchedEffect(query, searchScope, authorId, retryKey) {
        delay(250)
        if (query.trim().length >= 2) {
            searching = true
            searchError = null
            try {
                results = withContext(Dispatchers.IO) { repository.search(query, searchScope, authorId) }
                preferences.addSearchHistory(query)
            } catch (error: Throwable) {
                results = emptyList()
                searchError = error.message ?: "搜索失败，请重试"
            } finally {
                searching = false
            }
        } else {
            searching = false
            results = emptyList()
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Text("检索文库", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 12.dp))
        Text(
            "搜索 ${catalog.books.size} 部/篇作品的标题、章节与正文",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 3.dp)
        )
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            placeholder = { Text("搜索全部离线正文") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, null) } },
            shape = MaterialTheme.shapes.medium,
            singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 10.dp)
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            SearchScope.entries.forEach { item ->
                FilterChip(
                    selected = searchScope == item,
                    onClick = { searchScope = item },
                    label = { Text(when (item) { SearchScope.ALL -> "全部"; SearchScope.TITLES -> "标题与章节"; SearchScope.BODY -> "正文" }) }
                )
            }
            Box {
                FilterChip(
                    selected = authorId != null,
                    onClick = { authorMenuExpanded = true },
                    label = { Text(authorId?.let { catalog.author(it)?.nameZh } ?: "全部作者") },
                    trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) }
                )
                DropdownMenu(authorMenuExpanded, { authorMenuExpanded = false }) {
                    DropdownMenuItem(text = { Text("全部作者") }, onClick = { authorId = null; authorMenuExpanded = false })
                    catalog.authors.forEach { author ->
                        DropdownMenuItem(text = { Text(author.nameZh) }, onClick = { authorId = author.id; authorMenuExpanded = false })
                    }
                }
            }
        }
        if (query.length < 2) {
            Column(Modifier.fillMaxWidth()) {
                if (searchHistory.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("最近搜索", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = preferences::clearSearchHistory) { Text("清空") }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        searchHistory.forEach { value -> AssistChip(onClick = { query = value }, label = { Text(value) }) }
                    }
                }
                EmptyHint("输入至少两个字或字母；搜索完全在本机完成。")
            }
        }
        else if (searching) Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                Text(if (indexState.building) "正在建立离线索引…" else "正在搜索离线正文…")
                Text(
                    if (indexState.building && indexState.total > 0) {
                        "正在处理 ${indexState.current}/${indexState.total} 部作品"
                    } else "首次搜索需建立本地索引，请稍候",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        else if (searchError != null) {
            Column(Modifier.fillMaxWidth().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                Text(searchError!!, modifier = Modifier.padding(top = 10.dp))
                TextButton(onClick = { retryKey++ }) { Text("重试") }
            }
        }
        else LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            item {
                Text(
                    if (results.size >= 80) "显示前 80 条结果" else "找到 ${results.size} 条结果",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                if (results.isEmpty()) Text("没有匹配结果，可尝试缩短关键词或切换搜索范围。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(results, key = { "${it.bookId}-${it.chapterId}-${it.paragraphIndex}" }) { hit ->
                Card(
                    onClick = { navigate(Screen.Reader(hit.bookId, hit.chapterId, hit.paragraphIndex)) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(hit.title, style = MaterialTheme.typography.titleSmall)
                        Text(hit.chapterTitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 3.dp))
                        Text(
                            highlightedExcerpt(hit.excerpt, MaterialTheme.colorScheme.primary),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

internal fun highlightedExcerpt(html: String, highlightColor: Color): AnnotatedString {
    val builder = AnnotatedString.Builder()
    var cursor = 0
    var highlightStart = -1
    while (cursor < html.length) {
        when {
            html.startsWith("<b>", cursor, ignoreCase = true) -> { highlightStart = builder.length; cursor += 3 }
            html.startsWith("</b>", cursor, ignoreCase = true) -> {
                if (highlightStart >= 0 && builder.length > highlightStart) {
                    builder.addStyle(SpanStyle(color = highlightColor, fontWeight = FontWeight.Bold), highlightStart, builder.length)
                }
                highlightStart = -1
                cursor += 4
            }
            else -> {
                builder.append(html[cursor])
                cursor++
            }
        }
    }
    if (highlightStart >= 0 && builder.length > highlightStart) {
        builder.addStyle(SpanStyle(color = highlightColor, fontWeight = FontWeight.Bold), highlightStart, builder.length)
    }
    return builder.toAnnotatedString()
}
