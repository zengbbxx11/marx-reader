@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.marxreader.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.Html
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
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
import org.marxreader.app.ui.reader.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ReadingScreen(
    catalog: LibraryCatalog,
    repository: LibraryRepository,
    preferences: ReaderPreferences,
    settings: ReaderSettings,
    bookId: String,
    requestedChapterId: String?,
    requestedParagraph: Int,
    requestedCharacterOffset: Int,
    requestedCompleted: Boolean?,
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
    var chapterIndex by rememberSaveable(bookId) {
        mutableIntStateOf(book.chapters.indexOfFirst { it.id == requestedChapterId }.takeIf { it >= 0 } ?: 0)
    }
    val chapter = book.chapters.getOrNull(chapterIndex) ?: return
    var noteRefreshKey by remember(book.id) { mutableIntStateOf(0) }
    var resolvedNotes by remember(book.id, chapter.id) { mutableStateOf<List<ResolvedNoteAnchor>>(emptyList()) }
    LaunchedEffect(book.id, chapter.id, noteRefreshKey) {
        resolvedNotes = repository.resolveNotes(book, chapter)
    }
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
    var pageAnchorCharacterOffset by rememberSaveable(book.id) {
        mutableIntStateOf(requestedCharacterOffset.coerceAtLeast(0))
    }
    var readingCompleted by rememberSaveable(book.id) {
        mutableStateOf(requestedCompleted ?: readerUiState.savedPosition?.completed ?: false)
    }
    val density = LocalDensity.current
    val context = LocalContext.current
    val accessibilityManager = remember(context) {
        context.getSystemService(AccessibilityManager::class.java)
    }
    var touchExplorationEnabled by remember(accessibilityManager) {
        mutableStateOf(accessibilityManager?.isTouchExplorationEnabled == true)
    }
    DisposableEffect(accessibilityManager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener {
            touchExplorationEnabled = it
        }
        accessibilityManager?.addTouchExplorationStateChangeListener(listener)
        onDispose { accessibilityManager?.removeTouchExplorationStateChangeListener(listener) }
    }
    val readingMode = if (touchExplorationEnabled) {
        ReadingMode.SCROLL
    } else settings.mode
    val expandedLayout = LocalConfiguration.current.screenWidthDp >= 900 && density.fontScale < 1.5f
    val accentColor = MaterialTheme.colorScheme.primary
    val footnoteHitSlopPx = with(density) { 8.dp.toPx() }
    val pages by produceState(
        initialValue = emptyList(),
        book.id,
        chapter.id,
        pageAreaSize,
        settings.fontSize,
        settings.lineHeight,
        settings.paragraphSpacing,
        settings.fontFamily,
        settings.fontWeight,
        settings.horizontalPadding,
        settings.verticalPadding,
        settings.firstLineIndent,
        resolvedNotes
    ) {
        if (pageAreaSize.width <= 0 || pageAreaSize.height <= 0) {
            value = emptyList()
        } else {
            val horizontalInsets = with(density) { (settings.horizontalPadding * 2).dp.roundToPx() }
            val verticalInsets = with(density) { (settings.verticalPadding * 2).dp.roundToPx() }
            val fontSizePx = with(density) { settings.fontSize.sp.toPx() }
            value = withContext(Dispatchers.Default) {
                paginateChapter(
                    book = book,
                    chapterIndex = chapterIndex,
                    widthPx = (pageAreaSize.width - horizontalInsets).coerceAtLeast(1),
                    heightPx = (pageAreaSize.height - verticalInsets).coerceAtLeast(1),
                    fontSizePx = fontSizePx,
                    lineHeightMultiplier = settings.lineHeight,
                    paragraphSpacingMultiplier = settings.paragraphSpacing,
                    fontFamily = settings.fontFamily,
                    fontWeight = settings.fontWeight,
                    firstLineIndent = settings.firstLineIndent,
                    noteAnchors = resolvedNotes
                )
            }
        }
    }
    val pagerState = rememberPagerState(pageCount = { pages.size.coerceAtLeast(1) })
    LaunchedEffect(pages) {
        if (pages.isNotEmpty()) pagerState.scrollToPage(
            pages.pageFor(pageAnchorChapter, pageAnchorParagraph, pageAnchorCharacterOffset)
        )
    }
    val visibleSourcePosition by remember(readingMode, pages) {
        derivedStateOf {
            if (readingMode == ReadingMode.PAGE) {
                pages.getOrNull(pagerState.currentPage)?.firstSourcePosition()
                    ?: (pageAnchorParagraph to pageAnchorCharacterOffset)
            } else {
                (listState.firstVisibleItemIndex - 1).coerceAtLeast(0) to 0
            }
        }
    }
    val visibleParagraph by remember { derivedStateOf { visibleSourcePosition.first } }
    val readingProgressValue by remember(book, chapter.id, visibleSourcePosition, readingCompleted) {
        derivedStateOf {
            book.readingProgress(
                ReaderPosition(
                    bookId = book.id,
                    chapterId = chapter.id,
                    paragraphIndex = visibleSourcePosition.first,
                    characterOffset = visibleSourcePosition.second,
                    completed = readingCompleted,
                    updatedAt = 0L
                )
            )
        }
    }
    TrackReadingSession(
        repository = repository,
        bookId = book.id,
        position = ReaderPosition(
            bookId = book.id,
            chapterId = chapter.id,
            paragraphIndex = visibleSourcePosition.first,
            characterOffset = visibleSourcePosition.second,
            completed = readingCompleted,
            updatedAt = 0L
        )
    )
    var controlsVisible by remember { mutableStateOf(true) }
    var showToc by remember { mutableStateOf(false) }
    var showStyle by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var noteTarget by remember { mutableStateOf<NoteDraftTarget?>(null) }
    var footnoteTarget by remember { mutableStateOf<Footnote?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val readerActivity = context as? Activity
    val originalBrightness = remember(readerActivity) {
        readerActivity?.window?.attributes?.screenBrightness
            ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    }
    SideEffect {
        readerActivity?.window?.let { window ->
            if (settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val attributes = window.attributes
            attributes.screenBrightness = if (settings.brightnessMode == ReaderBrightnessMode.SYSTEM) {
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            } else settings.brightness.coerceIn(.05f, 1f)
            window.attributes = attributes
        }
    }
    DisposableEffect(readerActivity) {
        onDispose {
            readerActivity?.window?.let { window ->
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val attributes = window.attributes
                attributes.screenBrightness = originalBrightness
                window.attributes = attributes
            }
        }
    }
    LaunchedEffect(readingMode, listState, chapter.id) {
        if (readingMode == ReadingMode.SCROLL) {
            snapshotFlow { listState.firstVisibleItemIndex }
                .distinctUntilChanged().collect { index ->
                    readerViewModel.savePosition(ReaderPosition(
                        book.id, chapter.id, (index - 1).coerceAtLeast(0),
                        updatedAt = System.currentTimeMillis(), completed = readingCompleted
                    ))
                }
        }
    }
    LaunchedEffect(readingMode, pagerState, pages) {
        if (readingMode == ReadingMode.PAGE) {
            snapshotFlow { pagerState.currentPage }
                .distinctUntilChanged().collect { pageIndex ->
                    pages.getOrNull(pageIndex)?.let { page ->
                        val sourcePosition = page.firstSourcePosition()
                        pageAnchorChapter = page.chapterId
                        pageAnchorParagraph = sourcePosition.first
                        pageAnchorCharacterOffset = sourcePosition.second
                        readerViewModel.savePosition(ReaderPosition(
                            book.id, page.chapterId, sourcePosition.first,
                            updatedAt = System.currentTimeMillis(),
                            characterOffset = sourcePosition.second,
                            completed = readingCompleted
                        ))
                    }
                }
        }
    }
    DisposableEffect(book.id, chapter.id, readingMode) {
        onDispose {
            if (readingMode == ReadingMode.PAGE) {
                pages.getOrNull(pagerState.currentPage)?.let {
                    val sourcePosition = it.firstSourcePosition()
                    readerViewModel.savePosition(ReaderPosition(
                        book.id, it.chapterId, sourcePosition.first,
                        updatedAt = System.currentTimeMillis(),
                        characterOffset = sourcePosition.second,
                        completed = readingCompleted
                    ))
                }
            } else {
                readerViewModel.savePosition(ReaderPosition(
                    book.id, chapter.id, (listState.firstVisibleItemIndex - 1).coerceAtLeast(0),
                    updatedAt = System.currentTimeMillis(), completed = readingCompleted
                ))
            }
        }
    }
    val jumpToSearchMatch: (ReaderSearchMatch) -> Unit = { match ->
        val targetChapterIndex = book.chapters.indexOfFirst { it.id == match.chapterId }
        if (targetChapterIndex >= 0) {
            val chapterAlreadyVisible = targetChapterIndex == chapterIndex
            pageAnchorChapter = match.chapterId
            pageAnchorParagraph = match.paragraphIndex
            pageAnchorCharacterOffset = match.start
            chapterIndex = targetChapterIndex
            if (readingMode == ReadingMode.PAGE && chapterAlreadyVisible) {
                scope.launch {
                    pagerState.scrollToPage(
                        pages.pageFor(match.chapterId, match.paragraphIndex, match.start)
                    )
                }
            } else if (readingMode == ReadingMode.SCROLL) {
                scope.launch {
                    delay(1)
                    listState.scrollToItem(match.paragraphIndex + 1)
                }
            }
        }
    }
    LaunchedEffect(chapter.id) {
        if (
            readerUiState.search.scope == ReaderSearchScope.CHAPTER &&
            readerUiState.search.query.trim().length >= 2
        ) readerViewModel.search(chapter.id)
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
                    if (!expandedLayout) IconButton(onClick = { showToc = true }) {
                        Icon(Icons.Default.FormatListNumbered, "目录")
                    }
                    IconButton(onClick = { showStyle = true }) { Icon(Icons.Default.TextFields, "排版") }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (controlsVisible) Surface(shadowElevation = 8.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().height(58.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        if (readingMode == ReadingMode.PAGE) {
                            if (pagerState.currentPage > 0) scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage - 1)
                            } else if (chapterIndex > 0) {
                                val targetIndex = chapterIndex - 1
                                val target = book.chapters[targetIndex]
                                pageAnchorChapter = target.id
                                pageAnchorParagraph = target.paragraphs.lastIndex.coerceAtLeast(0)
                                pageAnchorCharacterOffset = target.paragraphs.lastOrNull()
                                    ?.length?.minus(1)?.coerceAtLeast(0) ?: 0
                                chapterIndex = targetIndex
                            }
                        } else if (chapterIndex > 0) {
                            chapterIndex--
                            scope.launch { listState.scrollToItem(0) }
                        }
                    }, enabled = if (readingMode == ReadingMode.PAGE) pagerState.currentPage > 0 || chapterIndex > 0 else chapterIndex > 0) {
                        Icon(Icons.Default.SkipPrevious, if (readingMode == ReadingMode.PAGE) "上一页" else "上一章")
                    }
                    val progressLabel = if (readingMode == ReadingMode.PAGE && pages.isNotEmpty()) {
                            "${pagerState.currentPage + 1}/${pages.size} · ${readingProgressValue.displayPercent}"
                        } else readingProgressValue.displayPercent
                    Text(
                        progressLabel,
                        modifier = Modifier.semantics {
                            contentDescription = "阅读进度"
                            stateDescription = progressLabel
                        }
                    )
                    IconButton(onClick = {
                        if (readingMode == ReadingMode.PAGE) {
                            if (pagerState.currentPage < pages.lastIndex) scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            } else if (chapterIndex < book.chapters.lastIndex) {
                                val targetIndex = chapterIndex + 1
                                val target = book.chapters[targetIndex]
                                pageAnchorChapter = target.id
                                pageAnchorParagraph = 0
                                pageAnchorCharacterOffset = 0
                                chapterIndex = targetIndex
                            } else if (!readingCompleted) {
                                readingCompleted = true
                                val lastParagraph = chapter.paragraphs.lastIndex.coerceAtLeast(0)
                                readerViewModel.savePosition(ReaderPosition(
                                    book.id, chapter.id, lastParagraph,
                                    updatedAt = System.currentTimeMillis(),
                                    characterOffset = chapter.paragraphs.lastOrNull()?.length ?: 0,
                                    completed = true
                                ))
                                scope.launch { snackbarHostState.showSnackbar("已读完全书") }
                            }
                        } else if (chapterIndex < book.chapters.lastIndex) {
                            chapterIndex++
                            scope.launch { listState.scrollToItem(0) }
                        } else if (!readingCompleted) {
                            readingCompleted = true
                            val lastParagraph = chapter.paragraphs.lastIndex.coerceAtLeast(0)
                            readerViewModel.savePosition(ReaderPosition(
                                book.id, chapter.id, lastParagraph,
                                updatedAt = System.currentTimeMillis(),
                                characterOffset = chapter.paragraphs.lastOrNull()?.length ?: 0,
                                completed = true
                            ))
                            scope.launch { snackbarHostState.showSnackbar("已读完全书") }
                        }
                    }, enabled = if (readingMode == ReadingMode.PAGE) {
                        pages.isNotEmpty() && (
                            pagerState.currentPage < pages.lastIndex ||
                                chapterIndex < book.chapters.lastIndex || !readingCompleted
                            )
                    } else chapterIndex < book.chapters.lastIndex || !readingCompleted) {
                        val atBookEnd = chapterIndex == book.chapters.lastIndex &&
                            (readingMode != ReadingMode.PAGE ||
                                (pages.isNotEmpty() && pagerState.currentPage == pages.lastIndex))
                        Icon(
                            if (atBookEnd) Icons.Default.Check else Icons.Default.SkipNext,
                            if (atBookEnd) "标记已读完" else if (readingMode == ReadingMode.PAGE) "下一页" else "下一章"
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
        if (readingMode == ReadingMode.PAGE) Box(
            Modifier.padding(padding)
                .padding(start = if (expandedLayout) 320.dp else 0.dp)
                .fillMaxSize().onSizeChanged { pageAreaSize = it },
            contentAlignment = Alignment.TopCenter
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
                    val pageSearchRanges = remember(page, readerUiState.search.matches, readerUiState.search.selectedMatch) {
                        page.searchRanges(readerUiState.search.matches, readerUiState.search.selectedMatch)
                    }
                    val pageText = remember(page, settings.fontSize, pageSearchRanges) {
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
                            page.notes.forEach { note ->
                                addStyle(
                                    SpanStyle(background = note.note.color.composeColor().copy(alpha = .30f)),
                                    note.start.coerceIn(0, page.text.length),
                                    note.end.coerceIn(0, page.text.length)
                                )
                            }
                            pageSearchRanges.forEach { match ->
                                addStyle(
                                    SpanStyle(
                                        background = if (match.selected) {
                                            accentColor.copy(alpha = .52f)
                                        } else accentColor.copy(alpha = .24f)
                                    ),
                                    match.start.coerceIn(0, page.text.length),
                                    match.end.coerceIn(0, page.text.length)
                                )
                            }
                        }.toAnnotatedString()
                    }
                    Box(
                        Modifier.fillMaxSize()
                            .padding(
                                horizontal = settings.horizontalPadding.dp,
                                vertical = settings.verticalPadding.dp
                            )
                            .pointerInput(pageIndex, pages.size) {
                                detectTapGestures(
                                    onTap = { position ->
                                        val selectedFootnote = pageTextLayout?.let { layout ->
                                            page.footnotes.firstOrNull {
                                                layout.hitsTextRange(position, it.start, it.end, footnoteHitSlopPx)
                                            }
                                        }
                                        val selectedNote = pageTextLayout?.let { layout ->
                                            page.notes.firstOrNull {
                                                layout.hitsTextRange(position, it.start, it.end, footnoteHitSlopPx)
                                            }
                                        }
                                        when {
                                            selectedFootnote != null -> footnoteTarget = selectedFootnote.footnote
                                            selectedNote != null -> {
                                                val anchor = resolvedNotes.firstOrNull { it.note.id == selectedNote.note.id }
                                                if (anchor != null) noteTarget = noteDraftTarget(
                                                    chapter, anchor.paragraphIndex, anchor.start, resolvedNotes
                                                )
                                            }
                                            position.x < size.width * .30f && pagerState.currentPage > 0 ->
                                                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                                            position.x < size.width * .30f && chapterIndex > 0 -> {
                                                val targetIndex = chapterIndex - 1
                                                val target = book.chapters[targetIndex]
                                                pageAnchorChapter = target.id
                                                pageAnchorParagraph = target.paragraphs.lastIndex.coerceAtLeast(0)
                                                pageAnchorCharacterOffset = target.paragraphs.lastOrNull()
                                                    ?.length?.minus(1)?.coerceAtLeast(0) ?: 0
                                                chapterIndex = targetIndex
                                            }
                                            position.x > size.width * .70f && pagerState.currentPage < pages.lastIndex ->
                                                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                                            position.x > size.width * .70f && chapterIndex < book.chapters.lastIndex -> {
                                                val targetIndex = chapterIndex + 1
                                                val target = book.chapters[targetIndex]
                                                pageAnchorChapter = target.id
                                                pageAnchorParagraph = 0
                                                pageAnchorCharacterOffset = 0
                                                chapterIndex = targetIndex
                                            }
                                            else -> controlsVisible = !controlsVisible
                                        }
                                    },
                                    onLongPress = { position ->
                                        val layout = pageTextLayout ?: return@detectTapGestures
                                        val pageOffset = layout.getOffsetForPosition(position)
                                        val source = page.sourcePositionAt(pageOffset)
                                            ?: return@detectTapGestures
                                        noteTarget = noteDraftTarget(
                                            chapter,
                                            source.first,
                                            source.second,
                                            resolvedNotes
                                        )
                                    }
                                )
                            }
                    ) {
                        Text(
                            pageText,
                            fontSize = settings.fontSize.sp,
                            lineHeight = (settings.fontSize * settings.lineHeight).sp,
                            fontFamily = settings.fontFamily.composeFontFamily,
                            fontWeight = settings.fontWeight.composeFontWeight,
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
            modifier = Modifier.padding(padding)
                .padding(start = if (expandedLayout) 320.dp else 0.dp)
                .fillMaxSize().widthIn(max = 840.dp).combinedClickable(
                onClick = { controlsVisible = !controlsVisible },
                onLongClick = {}
            ),
            contentPadding = PaddingValues(
                horizontal = settings.horizontalPadding.dp,
                vertical = settings.verticalPadding.dp
            ),
            verticalArrangement = Arrangement.spacedBy((settings.fontSize * settings.paragraphSpacing).dp)
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
                val paragraphNotes = remember(chapter.id, index, resolvedNotes) {
                    resolvedNotes.filter { it.paragraphIndex == index }
                }
                val paragraphSearchMatches = remember(chapter.id, index, readerUiState.search.matches) {
                    readerUiState.search.matches.filter {
                        it.chapterId == chapter.id && it.paragraphIndex == index
                    }
                }
                var paragraphLayout by remember(chapter.id, index, paragraph) {
                    mutableStateOf<TextLayoutResult?>(null)
                }
                val indentLength = if (settings.firstLineIndent && section == null && shouldIndentParagraph(paragraph)) 2 else 0
                val displayParagraph = if (indentLength > 0) "　　$paragraph" else paragraph
                val annotatedParagraph = remember(
                    displayParagraph, paragraphFootnotes, paragraphNotes,
                    paragraphSearchMatches, readerUiState.search.selectedMatch, settings.fontSize
                ) {
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
                        paragraphNotes.forEach { anchor ->
                            addStyle(
                                SpanStyle(background = anchor.note.color.composeColor().copy(alpha = .30f)),
                                (anchor.start + indentLength).coerceIn(0, displayParagraph.length),
                                (anchor.end + indentLength).coerceIn(0, displayParagraph.length)
                            )
                        }
                        paragraphSearchMatches.forEach { match ->
                            addStyle(
                                SpanStyle(
                                    background = if (match == readerUiState.search.selectedMatch) {
                                        accentColor.copy(alpha = .52f)
                                    } else accentColor.copy(alpha = .24f)
                                ),
                                (match.start + indentLength).coerceIn(0, displayParagraph.length),
                                (match.end + indentLength).coerceIn(0, displayParagraph.length)
                            )
                        }
                    }.toAnnotatedString()
                }
                Text(
                    annotatedParagraph,
                    fontSize = (settings.fontSize + when (section?.level) { 2 -> 3f; 3, 4 -> 1.5f; else -> 0f }).sp,
                    lineHeight = (settings.fontSize * settings.lineHeight).sp,
                    fontFamily = settings.fontFamily.composeFontFamily,
                    fontWeight = if (section != null) FontWeight.Bold else settings.fontWeight.composeFontWeight,
                    color = if (section != null) MaterialTheme.colorScheme.primary else Color.Unspecified,
                    onTextLayout = { paragraphLayout = it },
                    modifier = Modifier.fillMaxWidth()
                        .padding(top = if (section != null) 14.dp else 0.dp, bottom = if (section != null) 4.dp else 0.dp)
                        .pointerInput(chapter.id, index, paragraphFootnotes, paragraphNotes) {
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
                                    val selectedNote = paragraphLayout?.let { layout ->
                                        paragraphNotes.firstOrNull { anchor ->
                                            layout.hitsTextRange(
                                                position,
                                                anchor.start + indentLength,
                                                anchor.end + indentLength,
                                                footnoteHitSlopPx
                                            )
                                        }
                                    }
                                    if (selectedFootnote != null) {
                                        footnoteTarget = selectedFootnote.second
                                    } else if (selectedNote != null) {
                                        noteTarget = noteDraftTarget(chapter, index, selectedNote.start, resolvedNotes)
                                    } else {
                                        controlsVisible = !controlsVisible
                                    }
                                },
                                onLongPress = { position ->
                                    val layout = paragraphLayout ?: return@detectTapGestures
                                    val offset = (layout.getOffsetForPosition(position) - indentLength)
                                        .coerceIn(0, paragraph.lastIndex.coerceAtLeast(0))
                                    noteTarget = noteDraftTarget(chapter, index, offset, resolvedNotes)
                                }
                            )
                        }
                )
                if (paragraphNotes.isNotEmpty()) Text(
                    "${paragraphNotes.count { it.note.kind == NoteKind.HIGHLIGHT }} 条高亮 · ${paragraphNotes.count { it.note.kind == NoteKind.ANNOTATION }} 条批注",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
        if (expandedLayout) Surface(
            modifier = Modifier.padding(padding).width(320.dp).fillMaxHeight(),
            tonalElevation = 2.dp
        ) {
            TocTree(
                nodes = book.toc,
                currentChapterId = chapter.id,
                currentParagraph = visibleParagraph,
                chapterProgress = savedChapterProgress,
                modifier = Modifier.fillMaxSize(),
                autoScrollToCurrent = true,
                onSelect = { chapterId, paragraph ->
                    chapterIndex = book.chapters.indexOfFirst { it.id == chapterId }.coerceAtLeast(0)
                    pageAnchorChapter = chapterId
                    pageAnchorParagraph = paragraph
                    pageAnchorCharacterOffset = 0
                    if (readingMode == ReadingMode.SCROLL) scope.launch {
                        delay(1)
                        listState.scrollToItem(if (paragraph > 0) paragraph + 1 else 0)
                    }
                }
            )
        }
        }
    }

    if (showToc && !expandedLayout) ModalBottomSheet(onDismissRequest = { showToc = false }) {
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
                if (readingMode == ReadingMode.PAGE) {
                    pageAnchorChapter = chapterId
                    pageAnchorParagraph = paragraph
                    pageAnchorCharacterOffset = 0
                    scope.launch { pagerState.scrollToPage(pages.pageFor(chapterId, paragraph)) }
                } else {
                    scope.launch { listState.scrollToItem(if (paragraph > 0) paragraph + 1 else 0) }
                }
            }
        )
    }
    if (showSearch) ReaderSearchSheet(
        state = readerUiState.search,
        onQueryChange = { readerViewModel.search(chapter.id, query = it) },
        onScopeChange = { readerViewModel.search(chapter.id, scope = it) },
        onPrevious = { readerViewModel.moveSearchSelection(-1)?.let(jumpToSearchMatch) },
        onNext = { readerViewModel.moveSearchSelection(1)?.let(jumpToSearchMatch) },
        onSelect = { index, _ -> readerViewModel.selectSearchMatch(index)?.let(jumpToSearchMatch) },
        onDismiss = { showSearch = false }
    )
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
                    fontFamily = settings.fontFamily.composeFontFamily,
                    fontWeight = settings.fontWeight.composeFontWeight
                )
            }
        }
    }
    noteTarget?.let { target ->
        ReaderAnnotationEditor(
            target = target,
            onDismiss = { noteTarget = null },
            onBookmark = {
                repository.toggleBookmark(
                    book.id, chapter.id, target.paragraphIndex, target.selection.text
                )
                noteTarget = null
                scope.launch { snackbarHostState.showSnackbar("已添加书签") }
            },
            onSave = { value ->
                scope.launch {
                    runCatching {
                        val existing = target.existing
                        repository.saveNote(noteForSelection(
                            bookId = book.id,
                            chapterId = chapter.id,
                            paragraphIndex = target.paragraphIndex,
                            paragraph = target.paragraph,
                            selection = target.selection,
                            text = value.text,
                            color = value.color,
                            tags = value.tags,
                            pinned = value.pinned,
                            id = existing?.id ?: 0,
                            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis()
                        ))
                    }.onSuccess {
                        noteRefreshKey++
                        noteTarget = null
                        snackbarHostState.showSnackbar(
                            if (value.kind == NoteKind.HIGHLIGHT) "高亮已保存" else "批注已保存"
                        )
                    }.onFailure {
                        snackbarHostState.showSnackbar(it.message ?: "笔记保存失败")
                    }
                }
            },
            onDelete = target.existing?.let { note ->
                {
                    scope.launch {
                        repository.deleteNote(note.id)
                        noteRefreshKey++
                        noteTarget = null
                        snackbarHostState.showSnackbar("笔记已删除")
                    }
                }
            }
        )
    }
}

internal enum class ShelfSection { RECENT, BOOKMARKS, NOTES, STATISTICS }
internal sealed interface ShelfDeleteTarget {
    data class Progress(val bookId: String) : ShelfDeleteTarget
    data class BookmarkItem(val id: Long) : ShelfDeleteTarget
    data class NoteItem(val id: Long) : ShelfDeleteTarget
}
