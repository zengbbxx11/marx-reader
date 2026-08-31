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

internal sealed interface Screen : java.io.Serializable {
    data object Home : Screen
    data class AuthorDetail(val authorId: String) : Screen
    data class BookDetail(val bookId: String) : Screen
    data class Reader(
        val bookId: String,
        val chapterId: String? = null,
        val paragraph: Int = 0,
        val characterOffset: Int = 0,
        val completed: Boolean? = null
    ) : Screen
}

private enum class HomeTab { LIBRARY, SEARCH, SHELF, SETTINGS }

@Composable
fun ReaderApp(repository: LibraryRepository, preferences: ReaderPreferences) {
    val settings by preferences.settings.collectAsState()
    MarxReaderTheme(settings.theme) {
        var initialized by remember { mutableStateOf(false) }
        var initializationError by remember { mutableStateOf<String?>(null) }
        val catalog by repository.catalog.collectAsState()
        LaunchedEffect(Unit) {
            runCatching { repository.initialize() }
                .onSuccess { initialized = true }
                .onFailure { initializationError = it.message ?: "书库初始化失败" }
        }
        when {
            initializationError != null -> ErrorScreen(initializationError!!)
            !initialized -> LoadingScreen()
            else -> AppNavigator(catalog, repository, preferences, settings)
        }
    }
}

@Composable
private fun AppNavigator(
    catalog: LibraryCatalog,
    repository: LibraryRepository,
    preferences: ReaderPreferences,
    settings: ReaderSettings
) {
    var screen by rememberSaveable { mutableStateOf<Screen>(Screen.Home) }
    val history = remember { mutableStateListOf<Screen>() }
    fun navigate(next: Screen) {
        history += screen
        screen = next
    }
    fun back() {
        screen = history.removeLastOrNull() ?: Screen.Home
    }
    BackHandler(screen !is Screen.Home) { back() }

    when (val current = screen) {
        Screen.Home -> HomeScreen(catalog, repository, preferences, settings, ::navigate)
        is Screen.AuthorDetail -> AuthorScreen(catalog, repository, current.authorId, ::back, ::navigate)
        is Screen.BookDetail -> BookScreen(catalog, repository, current.bookId, ::back, ::navigate)
        is Screen.Reader -> ReadingScreen(
            catalog, repository, preferences, settings,
            current.bookId, current.chapterId, current.paragraph, current.characterOffset,
            current.completed, ::back, ::navigate
        )
    }
}

@Composable
private fun LoadingScreen() = Box(
    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
    contentAlignment = Alignment.Center
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(18.dp))
        Text("正在打开离线书库…", style = MaterialTheme.typography.titleMedium)
        Text("正在读取内置中文书目", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ErrorScreen(message: String) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text("无法打开离线书库", style = MaterialTheme.typography.titleLarge)
        Text(message, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun HomeScreen(
    catalog: LibraryCatalog,
    repository: LibraryRepository,
    preferences: ReaderPreferences,
    settings: ReaderSettings,
    navigate: (Screen) -> Unit
) {
    var tab by rememberSaveable { mutableStateOf(HomeTab.LIBRARY) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().height(72.dp).padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BrandMark(42.dp)
                    Column(Modifier.weight(1f).padding(start = 13.dp)) {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                        Text(
                            stringResource(R.string.app_tagline),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) { Text("纯中文", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall) }
                }
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 0.dp
            ) {
                HomeTab.entries.forEach { item ->
                    val pair = when (item) {
                        HomeTab.LIBRARY -> Icons.Default.LocalLibrary to "书库"
                        HomeTab.SEARCH -> Icons.Default.Search to "搜索"
                        HomeTab.SHELF -> Icons.Default.Bookmarks to "书架"
                        HomeTab.SETTINGS -> Icons.Default.Tune to "设置"
                    }
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(pair.first, pair.second) },
                        label = { Text(pair.second) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                HomeTab.LIBRARY -> LibraryTab(catalog, repository, navigate) { tab = HomeTab.SEARCH }
                HomeTab.SEARCH -> SearchTab(catalog, repository, preferences, navigate)
                HomeTab.SHELF -> ShelfTab(catalog, repository, navigate) { tab = HomeTab.LIBRARY }
                HomeTab.SETTINGS -> SettingsTab(repository, preferences, settings)
            }
        }
    }
}

@Composable
private fun LibraryTab(
    catalog: LibraryCatalog,
    repository: LibraryRepository,
    navigate: (Screen) -> Unit,
    openSearch: () -> Unit
) {
    var progress by remember(catalog) { mutableStateOf<Map<String, ReadingProgress>>(emptyMap()) }
    LaunchedEffect(catalog) {
        progress = withContext(Dispatchers.IO) { repository.allProgress() }
    }
    val recent = remember(progress) { progress.values.maxByOrNull { it.updatedAt } }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, top = 12.dp, end = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("经典著作，静心阅读", style = MaterialTheme.typography.headlineMedium)
            Text(
                "五位思想家 · ${catalog.books.size} 部/篇馆藏",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        item {
            Card(
                onClick = openSearch,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Default.Search)
                    Column(Modifier.weight(1f).padding(horizontal = 13.dp)) {
                        Text("搜索离线文库", style = MaterialTheme.typography.titleSmall)
                        Text("作品标题、章节与全部正文", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (recent != null) item {
            val book = catalog.book(recent.bookId)
            if (book != null) ElevatedCard(
                onClick = {
                    navigate(Screen.Reader(
                        book.id, recent.chapterId, recent.paragraphIndex,
                        recent.characterOffset, recent.completed
                    ))
                },
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            shape = MaterialTheme.shapes.medium
                        ) { Icon(Icons.Default.PlayArrow, null, Modifier.padding(10.dp).size(24.dp)) }
                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                            Text("继续上次阅读", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                            Text(book.displayTitle, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.primary)
                    }
                    Text(
                        book.chapters.firstOrNull { it.id == recent.chapterId }?.title.orEmpty(),
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .76f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 13.dp)
                    )
                    val recentProgress = book.readingProgress(recent)
                    LinearProgressIndicator(
                        progress = { recentProgress.fraction },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(4.dp).clip(MaterialTheme.shapes.extraSmall),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .12f)
                    )
                    Text(
                        if (recentProgress.completed) "已读完" else "全书 ${recentProgress.displayPercent}",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
        item { SectionTitle("典藏作者", "按作者浏览全部著作与文章") }
        items(catalog.authors, key = { it.id }) { author ->
            val books = catalog.booksForAuthor(author.id)
            val read = books.count { progress.containsKey(it.id) }
            AuthorCard(author, books.size, read) { navigate(Screen.AuthorDetail(author.id)) }
        }
    }
}

@Composable
private fun AuthorCard(author: Author, bookCount: Int, readCount: Int, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(56.dp).clip(MaterialTheme.shapes.medium).background(Color(author.color or 0xFF000000)),
                contentAlignment = Alignment.Center
            ) { Text(author.nameZh.take(1), color = Color.White, fontFamily = FontFamily.Serif, fontSize = 25.sp, fontWeight = FontWeight.Bold) }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(author.nameZh, style = MaterialTheme.typography.titleMedium)
                Text(author.years, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                Text("$bookCount 部/篇${if (readCount > 0) " · 已读 $readCount" else " · 尚未阅读"}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun AuthorScreen(
    catalog: LibraryCatalog,
    repository: LibraryRepository,
    authorId: String,
    back: () -> Unit,
    navigate: (Screen) -> Unit
) {
    val author = catalog.author(authorId) ?: return
    var category by remember { mutableStateOf("全部") }
    var query by rememberSaveable(authorId) { mutableStateOf("") }
    var newestFirst by rememberSaveable(authorId) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var readingProgress by remember(authorId) { mutableStateOf<Map<String, ReadingProgress>>(emptyMap()) }
    LaunchedEffect(authorId) {
        readingProgress = withContext(Dispatchers.IO) { repository.allProgress() }
    }
    val showScrollToTop by remember { derivedStateOf { listState.firstVisibleItemIndex > 2 } }
    val allBooks = catalog.booksForAuthor(authorId).sortedWith(compareBy<Book> { it.year }.thenBy { it.displayTitle })
    val books = allBooks.filter {
        (category == "全部" || it.category == category) &&
            (query.isBlank() || it.displayTitle.contains(query.trim(), ignoreCase = true))
    }.let { if (newestFirst) it.asReversed() else it }
    Scaffold(
        topBar = { ReaderTopBar(author.nameZh, "${author.years} · ${allBooks.size} 部/篇", back) },
        floatingActionButton = {
            if (showScrollToTop) SmallFloatingActionButton(
                onClick = { scope.launch { listState.animateScrollToItem(0) } }
            ) { Icon(Icons.Default.KeyboardArrowUp, "回到顶部") }
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.Top) {
                        Box(
                            Modifier.size(64.dp).clip(MaterialTheme.shapes.medium).background(Color(author.color or 0xFF000000)),
                            contentAlignment = Alignment.Center
                        ) { Text(author.nameZh.take(1), color = Color.White, fontFamily = FontFamily.Serif, fontSize = 28.sp, fontWeight = FontWeight.Bold) }
                        Column(Modifier.weight(1f).padding(start = 15.dp)) {
                            Text(author.years, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Text("${allBooks.size} 部/篇典藏作品", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 3.dp))
                            if (author.description.isNotBlank()) Text(
                                author.description,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 7.dp)
                            )
                        }
                    }
                }
                SingleChoiceSegmentedButtonRow(Modifier.padding(top = 16.dp)) {
                    listOf("全部", "著作", "文章", "书信").forEachIndexed { index, item ->
                        SegmentedButton(
                            selected = category == item,
                            onClick = { category = item },
                            shape = SegmentedButtonDefaults.itemShape(index, 4)
                        ) { Text(item) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (query.isNotBlank()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "清空") }
                            TextButton(onClick = { newestFirst = !newestFirst }) { Text(if (newestFirst) "新→旧" else "旧→新") }
                        }
                    },
                    placeholder = { Text("在${author.nameZh}的作品标题中搜索") }
                )
                Text("显示 ${books.size} 部/篇 · 可搜索、分类和排序", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
            }
            items(books, key = { it.id }) { book ->
                BookCard(book, readingProgress[book.id]) {
                    val saved = readingProgress[book.id]
                    if (book.chapters.size == 1) navigate(
                        Screen.Reader(
                            book.id,
                            saved?.chapterId ?: book.chapters.first().id,
                            saved?.paragraphIndex ?: 0,
                            saved?.characterOffset ?: 0,
                            saved?.completed ?: false
                        )
                    )
                    else navigate(Screen.BookDetail(book.id))
                }
            }
        }
    }
}

@Composable
private fun BookCard(book: Book, progress: ReadingProgress?, onClick: () -> Unit) {
    val progressValue = remember(book, progress) { book.readingProgress(progress) }
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.Top) {
            Surface(
                modifier = Modifier.width(42.dp).height(58.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.primary,
                shape = MaterialTheme.shapes.small
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, null, modifier = Modifier.size(21.dp))
                }
            }
            Column(Modifier.weight(1f).padding(start = 13.dp)) {
                Text(book.displayTitle, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(
                    modifier = Modifier.padding(top = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    book.category.takeIf { it.isNotBlank() }?.let { MetadataPill(it) }
                    book.year.takeIf { it.isNotBlank() }?.let { MetadataPill(it) }
                    Text("${book.chapters.size} 章 · ${book.paragraphCount} 段", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                }
            if (progress != null) {
                val chapterIndex = book.chapters.indexOfFirst { it.id == progress.chapterId }.coerceAtLeast(0)
                LinearProgressIndicator(
                    progress = { progressValue.fraction },
                    modifier = Modifier.fillMaxWidth().padding(top = 11.dp).height(3.dp).clip(MaterialTheme.shapes.extraSmall),
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
                Text(
                    if (progressValue.completed) "已读完" else
                        "继续：${book.chapters.getOrNull(chapterIndex)?.title.orEmpty()} · ${progressValue.displayPercent}",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 5.dp)
                )
            }
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 17.dp))
        }
    }
}

@Composable
private fun BookScreen(
    catalog: LibraryCatalog,
    repository: LibraryRepository,
    bookId: String,
    back: () -> Unit,
    navigate: (Screen) -> Unit
) {
    val metadata = catalog.book(bookId) ?: return
    var loaded by remember(bookId) { mutableStateOf<Book?>(null) }
    LaunchedEffect(bookId) { loaded = repository.loadBook(bookId) }
    val book = loaded
    if (book == null) {
        Scaffold(topBar = { ReaderTopBar(metadata.displayTitle, metadata.year, back) }) { padding ->
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        return
    }
    var progress by remember(bookId) { mutableStateOf<ReadingProgress?>(null) }
    var savedChapterProgress by remember(bookId) { mutableStateOf<Map<String, Int>>(emptyMap()) }
    LaunchedEffect(bookId) {
        val loadedProgress = withContext(Dispatchers.IO) {
            repository.progress(bookId) to repository.chapterProgress(bookId)
        }
        progress = loadedProgress.first
        savedChapterProgress = loadedProgress.second
    }
    Scaffold(
        topBar = { ReaderTopBar(book.displayTitle, book.year, back) }
    ) { padding ->
        TocTree(
            nodes = book.toc,
            currentChapterId = progress?.chapterId,
            currentParagraph = progress?.paragraphIndex ?: 0,
            chapterProgress = savedChapterProgress,
            modifier = Modifier.padding(padding),
            headerContent = {
                BookHero(book)
                Button(
                    onClick = {
                        navigate(Screen.Reader(
                            book.id, progress?.chapterId, progress?.paragraphIndex ?: 0,
                            progress?.characterOffset ?: 0, progress?.completed ?: false
                        ))
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp).height(52.dp),
                    enabled = book.hasContent
                ) {
                    Icon(if (progress == null) Icons.AutoMirrored.Filled.MenuBook else Icons.Default.PlayArrow, null)
                    Text(if (progress == null) "开始离线阅读" else "继续阅读", Modifier.padding(start = 8.dp))
                }
                RightsPanel(book)
            },
            onSelect = { chapterId, paragraph -> navigate(Screen.Reader(book.id, chapterId, paragraph)) }
        )
    }
}

@Composable
private fun LegacyBookScreen(
    catalog: LibraryCatalog,
    repository: LibraryRepository,
    bookId: String,
    back: () -> Unit,
    navigate: (Screen) -> Unit
) {
    val metadata = catalog.book(bookId) ?: return
    val readerViewModel: ReaderViewModel = viewModel(
        key = "reader-$bookId",
        factory = ReaderViewModel.factory(repository, bookId)
    )
    val readerUiState by readerViewModel.uiState.collectAsState()
    val book = readerUiState.book
    if (book == null) {
        Scaffold(topBar = { ReaderTopBar(metadata.displayTitle, metadata.year, back) }) { padding ->
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                if (readerUiState.error == null) CircularProgressIndicator()
                else Text(readerUiState.error!!, color = MaterialTheme.colorScheme.error)
            }
        }
        return
    }
    var progress by remember(bookId) { mutableStateOf<ReadingProgress?>(null) }
    LaunchedEffect(bookId) {
        progress = withContext(Dispatchers.IO) { repository.progress(bookId) }
    }
    Scaffold(topBar = { ReaderTopBar(book.displayTitle, book.year, back) }) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                if (book.description.isNotBlank()) Text(book.description, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = {
                        navigate(Screen.Reader(
                            book.id, progress?.chapterId, progress?.paragraphIndex ?: 0,
                            progress?.characterOffset ?: 0, progress?.completed ?: false
                        ))
                    }, modifier = Modifier.fillMaxWidth(), enabled = book.chapters.any { it.paragraphs.isNotEmpty() }
                ) {
                    Icon(if (progress == null) Icons.AutoMirrored.Filled.MenuBook else Icons.Default.PlayArrow, null)
                    Text(if (progress == null) "开始离线阅读" else "继续阅读", Modifier.padding(start = 8.dp))
                }
                RightsPanel(book)
                SectionTitle("目录 · ${book.chapters.size} 章")
            }
            items(book.chapters, key = { it.id }) { chapter ->
                ListItem(
                    headlineContent = { Text(chapter.title, fontWeight = if (chapter.level == 1) FontWeight.SemiBold else FontWeight.Normal) },
                    supportingContent = { Text("${chapter.paragraphs.size} 段") },
                    leadingContent = { Text((book.chapters.indexOf(chapter) + 1).toString(), color = MaterialTheme.colorScheme.primary) },
                    trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable {
                        navigate(Screen.Reader(book.id, chapter.id, 0))
                    }
                )
            }
        }
    }
}

@Composable
private fun BookHero(book: Book) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.width(58.dp).height(78.dp),
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = MaterialTheme.shapes.medium,
                    shadowElevation = 2.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Filled.MenuBook, null, modifier = Modifier.size(28.dp))
                    }
                }
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text(book.displayTitle, style = MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(book.category.takeIf { it.isNotBlank() }, book.year.takeIf { it.isNotBlank() }).joinToString(" · "),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    Text("${book.chapters.size} 章 · ${book.paragraphCount} 段", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (book.description.isNotBlank()) Text(
                book.description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}

@Composable
private fun RightsPanel(book: Book) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
    ) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.Verified, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(21.dp))
            Column(Modifier.padding(start = 11.dp)) {
            Text("版本与来源", style = MaterialTheme.typography.titleSmall)
            Text(book.sourceCredit, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            if (book.year.isNotBlank()) Text(
                "年份：${book.year}${book.yearType.displayYearType()}",
                style = MaterialTheme.typography.bodySmall
            )
            if (book.yearBasis.isNotBlank()) Text(
                "年份依据：${book.yearBasis}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            if (book.yearNote.isNotBlank()) Text(
                book.yearNote,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            if (book.translator.isNotBlank()) Text("译者：${book.translator}", style = MaterialTheme.typography.bodySmall)
            if (book.translatorBasis.isNotBlank()) Text(
                "译者依据：${book.translatorBasis}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            if (book.editionNote.isNotBlank()) Text(
                "版本说明：${book.editionNote}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            Text("权利状态：${book.rights.name}", style = MaterialTheme.typography.bodySmall)
            Text(book.sourceUrl, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun String.displayYearType(): String = when (this) {
    "WRITTEN" -> "（写作）"
    "PUBLISHED" -> "（发表）"
    "TRANSLATION_PUBLICATION" -> "（译本出版）"
    "EDITION" -> "（版本）"
    "SOURCE_DATE" -> "（来源标注）"
    else -> ""
}

internal fun TextLayoutResult.hitsTextRange(
    position: Offset,
    start: Int,
    end: Int,
    hitSlopPx: Float
): Boolean = (start until end.coerceAtMost(layoutInput.text.length)).any { offset ->
    val box = getBoundingBox(offset)
    position.x >= box.left - hitSlopPx && position.x <= box.right + hitSlopPx &&
        position.y >= box.top - hitSlopPx && position.y <= box.bottom + hitSlopPx
}

internal data class NoteDraftTarget(
    val paragraphIndex: Int,
    val paragraph: String,
    val selection: TextSelection,
    val existing: Note? = null
)

internal fun noteDraftTarget(
    chapter: Chapter,
    paragraphIndex: Int,
    touchedOffset: Int,
    anchors: List<ResolvedNoteAnchor>
): NoteDraftTarget? {
    val paragraph = chapter.paragraphs.getOrNull(paragraphIndex) ?: return null
    val offset = touchedOffset.coerceIn(0, paragraph.lastIndex.coerceAtLeast(0))
    val existing = anchors.firstOrNull {
        it.paragraphIndex == paragraphIndex && offset in it.start until it.end
    }
    val selection = existing?.let {
        TextSelection(it.start, it.end, paragraph.substring(it.start, it.end))
    } ?: sentenceSelection(paragraph, offset)
    return NoteDraftTarget(paragraphIndex, paragraph, selection, existing?.note)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ReaderTopBar(title: String, subtitle: String, back: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
            }
        },
        navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
        actions = actions,
        modifier = Modifier.statusBarsPadding(),
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    )
}

@Composable
internal fun SectionTitle(text: String, supporting: String? = null) = Row(
    Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 3.dp),
    verticalAlignment = Alignment.Bottom
) {
    Column(Modifier.weight(1f)) {
        Text(text, style = MaterialTheme.typography.titleLarge)
        if (supporting != null) Text(
            supporting,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
    Box(Modifier.width(26.dp).height(3.dp).clip(MaterialTheme.shapes.extraSmall).background(MaterialTheme.colorScheme.secondary))
}

@Composable
private fun MetadataPill(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.extraSmall
    ) { Text(text, Modifier.padding(horizontal = 7.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall) }
}

@Composable
private fun BrandMark(size: androidx.compose.ui.unit.Dp) {
    Surface(
        modifier = Modifier.size(size),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shadowElevation = 2.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.MenuBook, null, modifier = Modifier.size(size * .55f))
        }
    }
}

@Composable
internal fun IconBadge(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.primary,
        shape = MaterialTheme.shapes.small
    ) { Icon(icon, null, modifier = Modifier.padding(9.dp).size(20.dp)) }
}

@Composable
internal fun EmptyHint(text: String) = Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.AutoMirrored.Filled.MenuBook, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(32.dp))
        Text(
            text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 10.dp)
        )
    }
}
