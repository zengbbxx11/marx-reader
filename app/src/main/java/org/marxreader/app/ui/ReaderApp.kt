@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.marxreader.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.Html
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.marxreader.app.data.*
import org.marxreader.app.R
import org.marxreader.app.ui.components.TocTree

private sealed interface Screen : java.io.Serializable {
    data object Home : Screen
    data class AuthorDetail(val authorId: String) : Screen
    data class BookDetail(val bookId: String) : Screen
    data class Reader(val bookId: String, val chapterId: String? = null, val paragraph: Int = 0) : Screen
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
            current.bookId, current.chapterId, current.paragraph, ::back, ::navigate
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
                onClick = { navigate(Screen.Reader(book.id, recent.chapterId, recent.paragraphIndex)) },
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
                    val recentChapter = book.chapters.indexOfFirst { it.id == recent.chapterId }.coerceAtLeast(0)
                    LinearProgressIndicator(
                        progress = { (recentChapter + 1f) / book.chapters.size.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(4.dp).clip(MaterialTheme.shapes.extraSmall),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .12f)
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
                        Screen.Reader(book.id, saved?.chapterId ?: book.chapters.first().id, saved?.paragraphIndex ?: 0)
                    )
                    else navigate(Screen.BookDetail(book.id))
                }
            }
        }
    }
}

@Composable
private fun BookCard(book: Book, progress: ReadingProgress?, onClick: () -> Unit) {
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
                    progress = { (chapterIndex + 1f) / book.chapters.size.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth().padding(top = 11.dp).height(3.dp).clip(MaterialTheme.shapes.extraSmall),
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
                Text(
                    "继续：${book.chapters.getOrNull(chapterIndex)?.title.orEmpty()}",
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
                    onClick = { navigate(Screen.Reader(book.id, progress?.chapterId, progress?.paragraphIndex ?: 0)) },
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
    var loadedBook by remember(bookId) { mutableStateOf<Book?>(null) }
    LaunchedEffect(bookId) { loadedBook = repository.loadBook(bookId) }
    val book = loadedBook
    if (book == null) {
        Scaffold(topBar = { ReaderTopBar(metadata.displayTitle, metadata.year, back) }) { padding ->
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
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
                        navigate(Screen.Reader(book.id, progress?.chapterId, progress?.paragraphIndex ?: 0))
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
            if (book.translator.isNotBlank()) Text("译者：${book.translator}", style = MaterialTheme.typography.bodySmall)
            Text("权利状态：${book.rights.name}", style = MaterialTheme.typography.bodySmall)
            Text(book.sourceUrl, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun TextLayoutResult.hitsTextRange(
    position: Offset,
    start: Int,
    end: Int,
    hitSlopPx: Float
): Boolean = (start until end.coerceAtMost(layoutInput.text.length)).any { offset ->
    val box = getBoundingBox(offset)
    position.x >= box.left - hitSlopPx && position.x <= box.right + hitSlopPx &&
        position.y >= box.top - hitSlopPx && position.y <= box.bottom + hitSlopPx
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReadingScreen(
    catalog: LibraryCatalog,
    repository: LibraryRepository,
    preferences: ReaderPreferences,
    settings: ReaderSettings,
    bookId: String,
    requestedChapterId: String?,
    requestedParagraph: Int,
    back: () -> Unit,
    navigate: (Screen) -> Unit
) {
    val metadata = catalog.book(bookId) ?: return
    var loadedBook by remember(bookId) { mutableStateOf<Book?>(null) }
    LaunchedEffect(bookId) { loadedBook = repository.loadBook(bookId) }
    val book = loadedBook
    if (book == null) {
        Scaffold(topBar = { ReaderTopBar(metadata.displayTitle, metadata.year, back) }) { padding ->
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        return
    }
    var chapterIndex by rememberSaveable(bookId) {
        mutableIntStateOf(book.chapters.indexOfFirst { it.id == requestedChapterId }.takeIf { it >= 0 } ?: 0)
    }
    val chapter = book.chapters.getOrNull(chapterIndex) ?: return
    var savedChapterProgress by remember(book.id) { mutableStateOf<Map<String, Int>>(emptyMap()) }
    LaunchedEffect(book.id) {
        savedChapterProgress = withContext(Dispatchers.IO) { repository.chapterProgress(book.id) }
    }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = if (requestedParagraph > 0) {
            (requestedParagraph + 1).coerceAtMost(chapter.paragraphs.size)
        } else 0
    )
    var pageAreaSize by remember { mutableStateOf(IntSize.Zero) }
    var pageAnchorChapter by rememberSaveable(book.id) { mutableStateOf(chapter.id) }
    var pageAnchorParagraph by rememberSaveable(book.id) { mutableIntStateOf(requestedParagraph.coerceAtLeast(0)) }
    val density = LocalDensity.current
    val accentColor = MaterialTheme.colorScheme.primary
    val footnoteHitSlopPx = with(density) { 8.dp.toPx() }
    val pages by produceState(
        initialValue = emptyList(),
        book.id,
        chapter.id,
        pageAreaSize,
        settings.fontSize,
        settings.lineHeight,
        settings.horizontalPadding,
        settings.firstLineIndent
    ) {
        if (pageAreaSize.width <= 0 || pageAreaSize.height <= 0) {
            value = emptyList()
        } else {
            val horizontalInsets = with(density) { (settings.horizontalPadding * 2).dp.roundToPx() }
            val verticalInsets = with(density) { 44.dp.roundToPx() }
            val fontSizePx = with(density) { settings.fontSize.sp.toPx() }
            value = withContext(Dispatchers.Default) {
                paginateChapter(
                    book = book,
                    chapterIndex = chapterIndex,
                    widthPx = (pageAreaSize.width - horizontalInsets).coerceAtLeast(1),
                    heightPx = (pageAreaSize.height - verticalInsets).coerceAtLeast(1),
                    fontSizePx = fontSizePx,
                    lineHeightMultiplier = settings.lineHeight,
                    firstLineIndent = settings.firstLineIndent
                )
            }
        }
    }
    val pagerState = rememberPagerState(pageCount = { pages.size.coerceAtLeast(1) })
    LaunchedEffect(pages) {
        if (pages.isNotEmpty()) pagerState.scrollToPage(pages.pageFor(pageAnchorChapter, pageAnchorParagraph))
    }
    val visibleParagraph by remember(settings.mode, pages) {
        derivedStateOf {
            if (settings.mode == ReadingMode.PAGE) {
                pages.getOrNull(pagerState.currentPage)?.paragraphIndex ?: pageAnchorParagraph
            } else {
                (listState.firstVisibleItemIndex - 1).coerceAtLeast(0)
            }
        }
    }
    val readingPercent by remember(book.id, chapterIndex, chapter.paragraphs.size, settings.mode, pages) {
        derivedStateOf {
            if (settings.mode == ReadingMode.PAGE && pages.isNotEmpty()) {
                ((chapterIndex + (pagerState.currentPage + 1f) / pages.size) /
                    book.chapters.size.coerceAtLeast(1) * 100).toInt()
            } else {
                ((chapterIndex + listState.firstVisibleItemIndex.toFloat() /
                    chapter.paragraphs.size.coerceAtLeast(1)) /
                    book.chapters.size.coerceAtLeast(1) * 100).toInt()
            }
        }
    }
    var controlsVisible by remember { mutableStateOf(true) }
    var showToc by remember { mutableStateOf(false) }
    var showStyle by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var noteTarget by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var footnoteTarget by remember { mutableStateOf<Footnote?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    DisposableEffect(settings.keepScreenOn) {
        val activity = context as? Activity
        if (settings.keepScreenOn) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    LaunchedEffect(settings.mode, listState, chapter.id) {
        if (settings.mode == ReadingMode.SCROLL) {
            snapshotFlow { listState.firstVisibleItemIndex }
                .distinctUntilChanged().collect { index ->
                    repository.saveProgress(book.id, chapter.id, (index - 1).coerceAtLeast(0))
                }
        }
    }
    LaunchedEffect(settings.mode, pagerState, pages) {
        if (settings.mode == ReadingMode.PAGE) {
            snapshotFlow { pagerState.currentPage }
                .distinctUntilChanged().collect { pageIndex ->
                    pages.getOrNull(pageIndex)?.let { page ->
                        pageAnchorChapter = page.chapterId
                        pageAnchorParagraph = page.paragraphIndex
                        repository.saveProgress(book.id, page.chapterId, page.paragraphIndex)
                    }
                }
        }
    }
    DisposableEffect(book.id, chapter.id, settings.mode) {
        onDispose {
            if (settings.mode == ReadingMode.PAGE) {
                pages.getOrNull(pagerState.currentPage)?.let {
                    repository.saveProgress(book.id, it.chapterId, it.paragraphIndex)
                }
            } else {
                repository.saveProgress(book.id, chapter.id, (listState.firstVisibleItemIndex - 1).coerceAtLeast(0))
            }
        }
    }

    Scaffold(
        topBar = {
            if (controlsVisible) ReaderTopBar(
                chapter.title,
                book.breadcrumb(chapter.id, visibleParagraph)
                    .joinToString(" › ") { it.title }
                    .ifBlank { "${book.displayTitle} · ${chapterIndex + 1}/${book.chapters.size}" },
                back,
                actions = {
                    IconButton(onClick = { showSearch = true }) { Icon(Icons.Default.Search, "文内搜索") }
                    IconButton(onClick = { showToc = true }) { Icon(Icons.Default.FormatListNumbered, "目录") }
                    IconButton(onClick = { showStyle = true }) { Icon(Icons.Default.TextFields, "排版") }
                }
            )
        },
        bottomBar = {
            if (controlsVisible) Surface(shadowElevation = 8.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().height(58.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        if (settings.mode == ReadingMode.PAGE) {
                            if (pagerState.currentPage > 0) scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage - 1)
                            } else if (chapterIndex > 0) {
                                val targetIndex = chapterIndex - 1
                                val target = book.chapters[targetIndex]
                                pageAnchorChapter = target.id
                                pageAnchorParagraph = target.paragraphs.lastIndex.coerceAtLeast(0)
                                chapterIndex = targetIndex
                            }
                        } else if (chapterIndex > 0) {
                            chapterIndex--
                            scope.launch { listState.scrollToItem(0) }
                        }
                    }, enabled = if (settings.mode == ReadingMode.PAGE) pagerState.currentPage > 0 || chapterIndex > 0 else chapterIndex > 0) {
                        Icon(Icons.Default.SkipPrevious, if (settings.mode == ReadingMode.PAGE) "上一页" else "上一章")
                    }
                    Text(
                        if (settings.mode == ReadingMode.PAGE && pages.isNotEmpty()) {
                            "${pagerState.currentPage + 1}/${pages.size} · $readingPercent%"
                        } else "$readingPercent%"
                    )
                    IconButton(onClick = {
                        if (settings.mode == ReadingMode.PAGE) {
                            if (pagerState.currentPage < pages.lastIndex) scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            } else if (chapterIndex < book.chapters.lastIndex) {
                                val targetIndex = chapterIndex + 1
                                val target = book.chapters[targetIndex]
                                pageAnchorChapter = target.id
                                pageAnchorParagraph = 0
                                chapterIndex = targetIndex
                            }
                        } else if (chapterIndex < book.chapters.lastIndex) {
                            chapterIndex++
                            scope.launch { listState.scrollToItem(0) }
                        }
                    }, enabled = if (settings.mode == ReadingMode.PAGE) pagerState.currentPage < pages.lastIndex || chapterIndex < book.chapters.lastIndex else chapterIndex < book.chapters.lastIndex) {
                        Icon(Icons.Default.SkipNext, if (settings.mode == ReadingMode.PAGE) "下一页" else "下一章")
                    }
                }
            }
        }
    ) { padding ->
        if (settings.mode == ReadingMode.PAGE) Box(
            Modifier.padding(padding).fillMaxSize().onSizeChanged { pageAreaSize = it }
        ) {
            if (pages.isEmpty()) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Text("正在根据屏幕和字号分页…", modifier = Modifier.padding(top = 12.dp))
                }
            } else {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    beyondViewportPageCount = 1
                ) { pageIndex ->
                    val page = pages[pageIndex]
                    var pageTextLayout by remember(pageIndex, page.text) {
                        mutableStateOf<TextLayoutResult?>(null)
                    }
                    val pageText = remember(page, settings.fontSize) {
                        AnnotatedString.Builder(page.text).apply {
                            page.emphasis.forEach { emphasis ->
                                addStyle(
                                    SpanStyle(
                                        color = accentColor,
                                        fontWeight = if (emphasis.level <= 2) FontWeight.Bold else FontWeight.SemiBold,
                                        fontSize = when (emphasis.level) {
                                            0 -> (settings.fontSize + 6).sp
                                            2 -> (settings.fontSize + 3).sp
                                            else -> (settings.fontSize + 1.5f).sp
                                        }
                                    ),
                                    emphasis.start.coerceIn(0, page.text.length),
                                    emphasis.end.coerceIn(0, page.text.length)
                                )
                            }
                            page.footnotes.forEach { note ->
                                addStyle(
                                    SpanStyle(
                                        color = accentColor,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = (settings.fontSize * .78f).sp,
                                        baselineShift = BaselineShift.Superscript
                                    ),
                                    note.start.coerceIn(0, page.text.length),
                                    note.end.coerceIn(0, page.text.length)
                                )
                            }
                        }.toAnnotatedString()
                    }
                    Box(
                        Modifier.fillMaxSize()
                            .padding(horizontal = settings.horizontalPadding.dp, vertical = 22.dp)
                            .pointerInput(pageIndex, pages.size) {
                                detectTapGestures(
                                    onTap = { position ->
                                        val selectedFootnote = pageTextLayout?.let { layout ->
                                            page.footnotes.firstOrNull {
                                                layout.hitsTextRange(position, it.start, it.end, footnoteHitSlopPx)
                                            }
                                        }
                                        when {
                                            selectedFootnote != null -> footnoteTarget = selectedFootnote.footnote
                                            position.x < size.width * .30f && pagerState.currentPage > 0 ->
                                                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                                            position.x < size.width * .30f && chapterIndex > 0 -> {
                                                val targetIndex = chapterIndex - 1
                                                val target = book.chapters[targetIndex]
                                                pageAnchorChapter = target.id
                                                pageAnchorParagraph = target.paragraphs.lastIndex.coerceAtLeast(0)
                                                chapterIndex = targetIndex
                                            }
                                            position.x > size.width * .70f && pagerState.currentPage < pages.lastIndex ->
                                                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                                            position.x > size.width * .70f && chapterIndex < book.chapters.lastIndex -> {
                                                val targetIndex = chapterIndex + 1
                                                val target = book.chapters[targetIndex]
                                                pageAnchorChapter = target.id
                                                pageAnchorParagraph = 0
                                                chapterIndex = targetIndex
                                            }
                                            else -> controlsVisible = !controlsVisible
                                        }
                                    },
                                    onLongPress = {
                                        val targetChapter = book.chapters.getOrNull(page.chapterIndex)
                                        targetChapter?.paragraphs?.getOrNull(page.paragraphIndex)?.let { text ->
                                            noteTarget = page.paragraphIndex to text
                                        }
                                    }
                                )
                            }
                    ) {
                        Text(
                            pageText,
                            fontSize = settings.fontSize.sp,
                            lineHeight = (settings.fontSize * settings.lineHeight).sp,
                            fontFamily = FontFamily.Serif,
                            onTextLayout = { pageTextLayout = it },
                            modifier = Modifier.fillMaxSize()
                        )
                        Text(
                            "${pageIndex + 1}",
                            modifier = Modifier.align(Alignment.BottomCenter),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .65f)
                        )
                    }
                }
            }
        } else LazyColumn(
            state = listState,
            modifier = Modifier.padding(padding).fillMaxSize().combinedClickable(
                onClick = { controlsVisible = !controlsVisible },
                onLongClick = {}
            ),
            contentPadding = PaddingValues(
                horizontal = settings.horizontalPadding.dp,
                vertical = 28.dp
            ),
            verticalArrangement = Arrangement.spacedBy((settings.fontSize * .72f).dp)
        ) {
            item(key = "heading-${chapter.id}") {
                Text(chapter.title, fontSize = (settings.fontSize + 7).sp, lineHeight = ((settings.fontSize + 7) * 1.3f).sp, fontWeight = FontWeight.Bold)
                HorizontalDivider(Modifier.padding(top = 20.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .28f))
            }
            itemsIndexed(chapter.paragraphs, key = { index, _ -> "${chapter.id}-$index" }) { index, paragraph ->
                val section = book.toc.firstOrNull {
                    it.type == TocNodeType.SECTION && it.chapterId == chapter.id && it.paragraphIndex == index
                }
                val paragraphFootnotes = remember(chapter.id, index, chapter.footnotes) {
                    chapter.footnotes.flatMap { footnote ->
                        footnote.references
                            .filter { it.paragraphIndex == index }
                            .map { reference -> reference to footnote }
                    }
                }
                var paragraphLayout by remember(chapter.id, index, paragraph) {
                    mutableStateOf<TextLayoutResult?>(null)
                }
                val indentLength = if (settings.firstLineIndent && section == null && shouldIndentParagraph(paragraph)) 2 else 0
                val displayParagraph = if (indentLength > 0) "　　$paragraph" else paragraph
                val annotatedParagraph = remember(displayParagraph, paragraphFootnotes, settings.fontSize) {
                    AnnotatedString.Builder(displayParagraph).apply {
                        paragraphFootnotes.forEach { (reference, _) ->
                            addStyle(
                                SpanStyle(
                                    color = accentColor,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = (settings.fontSize * .78f).sp,
                                    baselineShift = BaselineShift.Superscript
                                ),
                                (reference.start + indentLength).coerceIn(0, displayParagraph.length),
                                (reference.end + indentLength).coerceIn(0, displayParagraph.length)
                            )
                        }
                    }.toAnnotatedString()
                }
                Text(
                    annotatedParagraph,
                    fontSize = (settings.fontSize + when (section?.level) { 2 -> 3f; 3, 4 -> 1.5f; else -> 0f }).sp,
                    lineHeight = (settings.fontSize * settings.lineHeight).sp,
                    fontFamily = FontFamily.Serif,
                    fontWeight = if (section != null) FontWeight.Bold else FontWeight.Normal,
                    color = if (section != null) MaterialTheme.colorScheme.primary else Color.Unspecified,
                    onTextLayout = { paragraphLayout = it },
                    modifier = Modifier.fillMaxWidth()
                        .padding(top = if (section != null) 14.dp else 0.dp, bottom = if (section != null) 4.dp else 0.dp)
                        .pointerInput(chapter.id, index, paragraphFootnotes) {
                            detectTapGestures(
                                onTap = { position ->
                                    val selectedFootnote = paragraphLayout?.let { layout ->
                                        paragraphFootnotes.firstOrNull { (reference, _) ->
                                            layout.hitsTextRange(
                                                position,
                                                reference.start + indentLength,
                                                reference.end + indentLength,
                                                footnoteHitSlopPx
                                            )
                                        }
                                    }
                                    if (selectedFootnote != null) {
                                        footnoteTarget = selectedFootnote.second
                                    } else {
                                        controlsVisible = !controlsVisible
                                    }
                                },
                                onLongPress = { noteTarget = index to paragraph }
                            )
                        }
                )
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }

    if (showToc) ModalBottomSheet(onDismissRequest = { showToc = false }) {
        TocTree(
            nodes = book.toc,
            currentChapterId = chapter.id,
            currentParagraph = visibleParagraph,
            chapterProgress = savedChapterProgress,
            modifier = Modifier.fillMaxWidth().fillMaxHeight(.9f),
            autoScrollToCurrent = true,
            onSelect = { chapterId, paragraph ->
                chapterIndex = book.chapters.indexOfFirst { it.id == chapterId }.coerceAtLeast(0)
                showToc = false
                if (settings.mode == ReadingMode.PAGE) {
                    pageAnchorChapter = chapterId
                    pageAnchorParagraph = paragraph
                    scope.launch { pagerState.scrollToPage(pages.pageFor(chapterId, paragraph)) }
                } else {
                    scope.launch { listState.scrollToItem(if (paragraph > 0) paragraph + 1 else 0) }
                }
            }
        )
    }
    if (showSearch) {
        var query by rememberSaveable(book.id) { mutableStateOf("") }
        val hits = remember(query, book.id) {
            if (query.trim().length < 2) emptyList() else buildList {
                book.chapters.forEach { targetChapter ->
                    targetChapter.paragraphs.forEachIndexed { paragraphIndex, text ->
                        if (text.contains(query.trim(), ignoreCase = true)) {
                            add(Triple(targetChapter, paragraphIndex, text))
                            if (size >= 100) return@buildList
                        }
                    }
                }
            }
        }
        ModalBottomSheet(onDismissRequest = { showSearch = false }) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(.86f).padding(horizontal = 18.dp)) {
                Text("在本书中搜索", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    query,
                    { query = it },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    placeholder = { Text("输入正文关键词") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true
                )
                Text(if (query.length < 2) "至少输入两个字" else "找到 ${hits.size} 处（最多显示 100 处）", style = MaterialTheme.typography.labelMedium)
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 10.dp)) {
                    items(hits, key = { "${it.first.id}-${it.second}" }) { hit ->
                        ListItem(
                            headlineContent = { Text(hit.first.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(hit.third, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.clickable {
                                chapterIndex = book.chapters.indexOfFirst { it.id == hit.first.id }.coerceAtLeast(0)
                                pageAnchorChapter = hit.first.id
                                pageAnchorParagraph = hit.second
                                showSearch = false
                                scope.launch {
                                    if (settings.mode == ReadingMode.PAGE) pagerState.scrollToPage(pages.pageFor(hit.first.id, hit.second))
                                    else listState.scrollToItem(hit.second + 1)
                                }
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
    if (showStyle) ReaderStyleSheet(settings, { preferences.update(it) }) { showStyle = false }
    footnoteTarget?.let { footnote ->
        ModalBottomSheet(onDismissRequest = { footnoteTarget = null }) {
            Column(
                Modifier.fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 36.dp)
                    .navigationBarsPadding()
            ) {
                Text("正文注释", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(
                    "注释 ${footnote.marker}",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp)
                )
                HorizontalDivider(Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .22f))
                Text(
                    footnote.displayContent,
                    style = MaterialTheme.typography.bodyLarge,
                    lineHeight = (settings.fontSize * settings.lineHeight).sp,
                    fontFamily = FontFamily.Serif
                )
            }
        }
    }
    noteTarget?.let { target ->
        ParagraphActionDialog(
            excerpt = target.second,
            onDismiss = { noteTarget = null },
            onBookmark = {
                repository.toggleBookmark(book.id, chapter.id, target.first, target.second)
                noteTarget = null
            },
            onNote = { text ->
                repository.saveNote(book.id, chapter.id, target.first, target.second, text)
                noteTarget = null
            }
        )
    }
}

@Composable
private fun ReaderStyleSheet(settings: ReaderSettings, update: (ReaderSettings) -> Unit, dismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp).padding(bottom = 34.dp)
        ) {
            Text("阅读排版", style = MaterialTheme.typography.headlineSmall)
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("字号 ${settings.fontSize.toInt()}", Modifier.weight(1f))
                TextButton(onClick = { update(settings.copy(fontSize = (settings.fontSize - 1).coerceAtLeast(15f))) }) { Text("A−") }
                TextButton(onClick = { update(settings.copy(fontSize = (settings.fontSize + 1).coerceAtMost(32f))) }) { Text("A+") }
            }
            Slider(settings.fontSize, { update(settings.copy(fontSize = it)) }, valueRange = 15f..32f, steps = 16)
            Text("行距 ${"%.1f".format(settings.lineHeight)}")
            Slider(settings.lineHeight, { update(settings.copy(lineHeight = it)) }, valueRange = 1.3f..2.2f, steps = 8)
            Text("页边距 ${settings.horizontalPadding}")
            Slider(settings.horizontalPadding.toFloat(), { update(settings.copy(horizontalPadding = it.toInt())) }, valueRange = 12f..42f, steps = 14)
            Text("主题", modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderTheme.entries.forEach { theme ->
                    FilterChip(
                        selected = settings.theme == theme,
                        onClick = { update(settings.copy(theme = theme)) },
                        label = { Text(when(theme) { ReaderTheme.PAPER -> "纸白"; ReaderTheme.SEPIA -> "护眼"; ReaderTheme.DARK -> "夜间"; ReaderTheme.SYSTEM -> "系统" }) }
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("正文首行缩进两格", Modifier.weight(1f))
                Switch(settings.firstLineIndent, { update(settings.copy(firstLineIndent = it)) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("阅读时保持屏幕常亮", Modifier.weight(1f))
                Switch(settings.keepScreenOn, { update(settings.copy(keepScreenOn = it)) })
            }
        }
    }
}

@Composable
private fun ParagraphActionDialog(
    excerpt: String,
    onDismiss: () -> Unit,
    onBookmark: () -> Unit,
    onNote: (String) -> Unit
) {
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("标记这一段") },
        text = {
            Column {
                Text(excerpt.take(150), maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(note, { note = it }, label = { Text("笔记（可选）") }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            }
        },
        confirmButton = { TextButton(onClick = { if (note.isBlank()) onBookmark() else onNote(note) }) { Text(if (note.isBlank()) "添加书签" else "保存笔记") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private enum class ShelfSection { RECENT, BOOKMARKS, NOTES }
private sealed interface ShelfDeleteTarget {
    data class Progress(val bookId: String) : ShelfDeleteTarget
    data class BookmarkItem(val id: Long) : ShelfDeleteTarget
    data class NoteItem(val id: Long) : ShelfDeleteTarget
}

@Composable
private fun ShelfTab(
    catalog: LibraryCatalog,
    repository: LibraryRepository,
    navigate: (Screen) -> Unit,
    openLibrary: () -> Unit
) {
    var section by rememberSaveable { mutableStateOf(ShelfSection.RECENT) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedBookId by rememberSaveable { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var bookMenuExpanded by remember { mutableStateOf(false) }
    var editingNote by remember { mutableStateOf<Note?>(null) }
    var deleteTarget by remember { mutableStateOf<ShelfDeleteTarget?>(null) }
    val scope = rememberCoroutineScope()
    var snapshot by remember(catalog) {
        mutableStateOf(Triple(emptyList<ReadingProgress>(), emptyList<Bookmark>(), emptyList<Note>()))
    }
    LaunchedEffect(catalog, refreshKey) {
        snapshot = withContext(Dispatchers.IO) {
            Triple(
                repository.allProgress().values.sortedByDescending { it.updatedAt },
                repository.bookmarks(),
                repository.notes()
            )
        }
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
    val visibleNotes = notes.filter { matches(it.bookId, it.chapterId, "${it.excerpt} ${it.text}") }

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
                            ShelfSection.RECENT -> "最近 ${progress.size}"
                            ShelfSection.BOOKMARKS -> "书签 ${bookmarks.size}"
                            ShelfSection.NOTES -> "笔记 ${notes.size}"
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
        }
        when (section) {
            ShelfSection.RECENT -> {
                if (visibleProgress.isEmpty()) item {
                    EmptyShelfAction("没有符合条件的阅读记录", openLibrary)
                }
                items(visibleProgress, key = { "p-${it.bookId}" }) { item ->
                    val book = catalog.book(item.bookId)
                    val chapter = book?.chapters?.firstOrNull { it.id == item.chapterId }
                    Card(onClick = { navigate(Screen.Reader(item.bookId, item.chapterId, item.paragraphIndex)) }) {
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
                if (visibleNotes.isEmpty()) item { EmptyShelfAction("没有符合条件的笔记", openLibrary) }
                items(visibleNotes, key = { "n-${it.id}" }) { item ->
                    val book = catalog.book(item.bookId)
                    val chapter = book?.chapters?.firstOrNull { it.id == item.chapterId }
                    Card(onClick = { navigate(Screen.Reader(item.bookId, item.chapterId, item.paragraphIndex)) }) {
                        Column(Modifier.fillMaxWidth().padding(15.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(book?.displayTitle ?: item.bookId, style = MaterialTheme.typography.titleSmall)
                                    Text(chapter?.title ?: item.chapterId, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                                }
                                IconButton(onClick = { editingNote = item }) { Icon(Icons.Default.Edit, "编辑笔记") }
                                IconButton(onClick = { deleteTarget = ShelfDeleteTarget.NoteItem(item.id) }) { Icon(Icons.Default.DeleteOutline, "删除笔记") }
                            }
                            Text(item.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                            Text("摘录：${item.excerpt}", maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 7.dp))
                            Text(formatShelfTime(item.updatedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    editingNote?.let { note ->
        var text by remember(note.id) { mutableStateOf(note.text) }
        AlertDialog(
            onDismissRequest = { editingNote = null },
            title = { Text("编辑笔记") },
            text = { OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 4) },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    scope.launch { repository.updateNote(note.id, text); refreshKey++ }
                    editingNote = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editingNote = null }) { Text("取消") } }
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

@Composable
private fun SearchTab(
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

private enum class ClearDataTarget { PROGRESS, BOOKMARKS, NOTES }

@Composable
private fun SettingsTab(repository: LibraryRepository, preferences: ReaderPreferences, settings: ReaderSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var packToDelete by remember { mutableStateOf<ImportedPack?>(null) }
    var packToInspect by remember { mutableStateOf<ImportedPack?>(null) }
    var clearTarget by remember { mutableStateOf<ClearDataTarget?>(null) }
    val packs by repository.importedPacks.collectAsState()
    val packLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            repository.importPack(uri).onSuccess { message = it }.onFailure { message = it.message ?: "导入失败" }
        }
    }
    val backupExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            runCatching { repository.exportBackup(uri, settings) }
                .onSuccess { message = "完整备份已导出" }
                .onFailure { message = it.message ?: "备份导出失败" }
        }
    }
    val markdownExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) scope.launch {
            runCatching { repository.exportMarkdown(uri) }
                .onSuccess { message = "Markdown 笔记已导出" }
                .onFailure { message = it.message ?: "笔记导出失败" }
        }
    }
    val backupImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            runCatching { repository.restoreBackup(uri) }
                .onSuccess { restored -> preferences.update(restored); message = "备份已合并恢复" }
                .onFailure { message = it.message ?: "备份恢复失败" }
        }
    }
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0.3.0"
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { SectionTitle("离线内容") }
        item {
            SettingCard(Icons.Default.FileOpen, "导入 .marxpack 内容包", "从手机本地文件导入，不需要网络") {
                packLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
            }
        }
        if (packs.isEmpty()) item { Text("尚未导入扩展内容包", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        items(packs, key = { it.fileName }) { pack ->
            Card(
                onClick = { packToInspect = pack },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                ListItem(
                    headlineContent = { Text(pack.title) },
                    supportingContent = {
                        Text("版本 ${pack.version} · ${pack.bookIds.size} 部作品 · ${formatFileSize(pack.sizeBytes)}\n导入于 ${formatShelfTime(pack.importedAt)}")
                    },
                    leadingContent = { Icon(Icons.Default.Inventory2, null) },
                    trailingContent = {
                        IconButton(onClick = { packToDelete = pack }) { Icon(Icons.Default.DeleteOutline, "删除内容包") }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
        }
        item { SectionTitle("我的数据", "通过本地文件备份、迁移或整理阅读数据") }
        item { SettingCard(Icons.Default.Backup, "导出完整 JSON 备份", "包含进度、书签、笔记和阅读设置") { backupExporter.launch("marxreader-backup.json") } }
        item { SettingCard(Icons.Default.Description, "导出 Markdown 笔记", "生成便于阅读和归档的中文笔记文档") { markdownExporter.launch("marxreader-notes.md") } }
        item { SettingCard(Icons.Default.Restore, "从 JSON 备份恢复", "与本机数据安全合并，不会清空现有记录") { backupImporter.launch(arrayOf("application/json", "text/json", "application/octet-stream")) } }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("清理本机数据", style = MaterialTheme.typography.titleSmall)
                    Text("每项操作都会再次确认，不影响内置或导入正文。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
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
                        Text("应用不会上传正文或阅读记录；Android 可按你的系统设置备份阅读数据。", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 3.dp))
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
                    Text("本版本新增书架管理、数据备份、内容包管理与搜索筛选。", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 5.dp))
                }
            }
        }
        item {
            SettingCard(Icons.Default.Info, "系统应用信息", "管理存储、备份与权限") {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }
        }
        message?.let { value -> item { AssistChip(onClick = { message = null }, label = { Text(value) }, leadingIcon = { Icon(Icons.Default.CheckCircle, null) }) } }
    }

    packToInspect?.let { pack ->
        AlertDialog(
            onDismissRequest = { packToInspect = null },
            title = { Text(pack.title) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text("版本 ${pack.version} · ${pack.bookIds.size} 部作品", color = MaterialTheme.colorScheme.primary)
                    if (pack.bookIds.isEmpty()) {
                        Text("无法读取该内容包的作品信息。你仍可以删除这个损坏的内容包。", modifier = Modifier.padding(top = 12.dp))
                    } else pack.bookIds.forEachIndexed { index, id ->
                        Text("${index + 1}. ${repository.catalog.value.book(id)?.displayTitle ?: id}", modifier = Modifier.padding(top = 8.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { packToInspect = null }) { Text("关闭") } }
        )
    }

    packToDelete?.let { pack ->
        AlertDialog(
            onDismissRequest = { packToDelete = null },
            title = { Text("删除内容包") },
            text = { Text("将移除“${pack.title}”及其中 ${pack.bookIds.size} 部作品。阅读进度、书签和笔记会保留。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        repository.deleteImportedPack(pack.fileName)
                            .onSuccess { message = it }.onFailure { message = it.message ?: "删除失败" }
                    }
                    packToDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { packToDelete = null }) { Text("取消") } }
        )
    }
    clearTarget?.let { target ->
        val label = when (target) { ClearDataTarget.PROGRESS -> "全部阅读记录"; ClearDataTarget.BOOKMARKS -> "全部书签"; ClearDataTarget.NOTES -> "全部笔记" }
        AlertDialog(
            onDismissRequest = { clearTarget = null },
            title = { Text("清除$label") },
            text = { Text("该操作无法撤销。建议先导出完整 JSON 备份。") },
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

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
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

@Composable
private fun ReaderTopBar(title: String, subtitle: String, back: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
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
private fun SectionTitle(text: String, supporting: String? = null) = Row(
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
private fun IconBadge(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.primary,
        shape = MaterialTheme.shapes.small
    ) { Icon(icon, null, modifier = Modifier.padding(9.dp).size(20.dp)) }
}

@Composable
private fun EmptyHint(text: String) = Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) {
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
