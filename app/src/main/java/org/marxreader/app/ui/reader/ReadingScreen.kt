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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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
    val initialPosition = remember(book.id) {
        if (requestedChapterId == null) readerUiState.savedPosition else null
    }
    var chapterIndex by rememberSaveable(bookId) {
        mutableIntStateOf(book.chapters.indexOfFirst {
            it.id == (requestedChapterId ?: initialPosition?.chapterId)
        }.takeIf { it >= 0 } ?: 0)
    }
    val chapter = book.chapters.getOrNull(chapterIndex) ?: return
    val dataRevision by repository.dataRevision.collectAsState()
    val notesRevision by repository.notesRevision.collectAsState()
    var resolvedNotes by remember(book.id, chapter.id) { mutableStateOf<List<ResolvedNoteAnchor>>(emptyList()) }
    LaunchedEffect(book.id, chapter.id, notesRevision) {
        resolvedNotes = repository.resolveNotes(book, chapter)
    }
    var savedChapterProgress by remember(book.id) { mutableStateOf<Map<String, Int>>(emptyMap()) }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = if (requestedParagraph > 0) {
            (requestedParagraph + 1).coerceAtMost(chapter.paragraphs.size)
        } else 0
    )
    var pageAreaSize by remember { mutableStateOf(IntSize.Zero) }
    var pageAnchorChapter by rememberSaveable(book.id) { mutableStateOf(chapter.id) }
    var pageAnchorParagraph by rememberSaveable(book.id) {
        mutableIntStateOf((initialPosition?.paragraphIndex ?: requestedParagraph).coerceAtLeast(0))
    }
    var pageAnchorCharacterOffset by rememberSaveable(book.id) {
        mutableIntStateOf((initialPosition?.characterOffset ?: requestedCharacterOffset).coerceAtLeast(0))
    }
    var positionRequest by remember { mutableIntStateOf(0) }
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
    val layoutSpec = with(density) {
        ReaderLayoutSpec(
            (pageAreaSize.width - 2 * settings.horizontalPadding.dp.roundToPx()).coerceAtLeast(1),
            (pageAreaSize.height - 2 * settings.verticalPadding.dp.roundToPx()).coerceAtLeast(1),
            settings.fontSize.sp.toPx(), settings.lineHeight, settings.paragraphSpacing,
            settings.fontFamily, settings.fontWeight, settings.firstLineIndent
        )
    }
    val pageCache = remember(book.id) { ChapterPageCache() }
    val layoutResult by produceState<ChapterLayoutResult?>(
        null, book.id, chapter.id, layoutSpec, readingMode
    ) {
        value = null
        if (readingMode == ReadingMode.PAGE && pageAreaSize.width > 0 && pageAreaSize.height > 0) {
            // Coalesce slider updates before doing an entire chapter's layout.
            delay(80)
            value = ChapterLayoutResult(chapter.id, layoutSpec, pageCache.pages(book, chapterIndex, layoutSpec))
            delay(150)
            if (chapterIndex < book.chapters.lastIndex && book.chapters[chapterIndex + 1].characterCount <= 600_000) {
                pageCache.pages(book, chapterIndex + 1, layoutSpec)
            }
        }
    }
    val layoutPages = layoutResult?.takeIf { it.chapterId == chapter.id && it.spec == layoutSpec }?.pages.orEmpty()
    val pages = remember(layoutPages, resolvedNotes, chapter.id) {
        if (layoutPages.firstOrNull()?.chapterId == chapter.id) layoutPages.withNoteAnchors(resolvedNotes)
        else emptyList()
    }
    val pagerWindow = ChapterPagerWindow(pages.size, chapterIndex > 0, chapterIndex < book.chapters.lastIndex)
    val pagerState = rememberPagerState(pageCount = { pagerWindow.slotCount })
    val contentPageIndex by remember(pagerWindow) {
        derivedStateOf { pagerWindow.contentPage(pagerState.currentPage) }
    }
    val paragraphLayouts = remember(chapter.id, layoutSpec.width, layoutSpec.fontSize,
        layoutSpec.lineHeight, layoutSpec.font, layoutSpec.weight, layoutSpec.indent) {
        mutableStateMapOf<Int, ParagraphLineMap>()
    }
    var positionReady by remember(chapter.id, readingMode, positionRequest, layoutSpec) { mutableStateOf(false) }
    val sectionsByParagraph = remember(book.id, chapter.id) {
        book.toc.filter { it.type == TocNodeType.SECTION && it.chapterId == chapter.id }
            .associateBy { it.paragraphIndex }
    }
    val scrollTopPadding = with(density) { settings.verticalPadding.dp.roundToPx() }
    val sectionTopPadding = with(density) { 14.dp.roundToPx() }
    val visibleSourcePosition by remember(chapter.id, layoutSpec, readingMode, pages, positionReady, pagerWindow, paragraphLayouts) {
        derivedStateOf {
            if (!positionReady) pageAnchorParagraph to pageAnchorCharacterOffset
            else if (readingMode == ReadingMode.PAGE) {
                pages.getOrNull(pagerWindow.contentPage(pagerState.settledPage))?.firstSourcePosition()
                    ?: (pageAnchorParagraph to pageAnchorCharacterOffset)
            } else {
                if (listState.firstVisibleItemIndex == 0) return@derivedStateOf 0 to 0
                if (listState.firstVisibleItemIndex > chapter.paragraphs.size) {
                    return@derivedStateOf chapter.paragraphs.lastIndex.coerceAtLeast(0) to (chapter.paragraphs.lastOrNull()?.length ?: 0)
                }
                val paragraph = (listState.firstVisibleItemIndex - 1).coerceIn(0, chapter.paragraphs.lastIndex.coerceAtLeast(0))
                val inset = if (sectionsByParagraph.containsKey(paragraph)) sectionTopPadding else 0
                paragraph to (paragraphLayouts[paragraph]?.sourceAt(
                    listState.firstVisibleItemScrollOffset - scrollTopPadding - inset
                ) ?: 0)
            }
        }
    }
    val visibleParagraph = visibleSourcePosition.first
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
    var searchNavigationVisible by rememberSaveable(book.id) { mutableStateOf(false) }
    var returnChapter by rememberSaveable(book.id) { mutableStateOf<String?>(null) }
    var returnParagraph by rememberSaveable(book.id) { mutableIntStateOf(0) }
    var returnCharacter by rememberSaveable(book.id) { mutableIntStateOf(0) }
    var returnCompleted by rememberSaveable(book.id) { mutableStateOf(false) }
    LaunchedEffect(book.id, dataRevision, showToc, expandedLayout) {
        if (showToc || expandedLayout) {
            savedChapterProgress = withContext(Dispatchers.IO) { repository.chapterProgress(book.id) }
        }
    }
    var noteTarget by remember { mutableStateOf<NoteDraftTarget?>(null) }
    var footnoteTarget by remember { mutableStateOf<Footnote?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val readerActivity = context as? Activity
    val originalBrightness = remember(readerActivity) {
        readerActivity?.window?.attributes?.screenBrightness
            ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    }
    LaunchedEffect(readerActivity, settings.keepScreenOn, settings.brightnessMode, settings.brightness) {
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
    LaunchedEffect(readingMode, listState, chapter.id, positionRequest, layoutSpec) {
        if (readingMode == ReadingMode.SCROLL) {
            val paragraph = pageAnchorParagraph.coerceIn(0, chapter.paragraphs.lastIndex.coerceAtLeast(0))
            val character = pageAnchorCharacterOffset
            if (paragraph == 0 && character == 0) listState.scrollToItem(0)
            else if (chapter.paragraphs.isNotEmpty()) {
                listState.scrollToItem(paragraph + 1)
                val lines = snapshotFlow { paragraphLayouts[paragraph] }.filterNotNull().first()
                val inset = if (sectionsByParagraph.containsKey(paragraph)) sectionTopPadding else 0
                listState.scrollToItem(paragraph + 1, lines.topFor(character) + inset - scrollTopPadding)
            }
            positionReady = true
            snapshotFlow { visibleSourcePosition }.distinctUntilChanged().collect { (paragraphIndex, characterOffset) ->
                if (!positionReady || pageAnchorChapter != chapter.id) return@collect
                pageAnchorChapter = chapter.id
                pageAnchorParagraph = paragraphIndex
                pageAnchorCharacterOffset = characterOffset
                readerViewModel.savePosition(ReaderPosition(
                    book.id, chapter.id, paragraphIndex, characterOffset = characterOffset,
                    updatedAt = System.currentTimeMillis(), completed = readingCompleted
                ))
            }
        }
    }
    // Read the latest visible position when leaving or moving to the background.
    // A chapter still being laid out must not overwrite the last valid position.
    val latestPosition = rememberUpdatedState(
        if (positionReady) {
            ReaderPosition(book.id, chapter.id, visibleSourcePosition.first,
                characterOffset = visibleSourcePosition.second,
                completed = readingCompleted, updatedAt = System.currentTimeMillis())
        } else ReaderPosition(book.id, pageAnchorChapter, pageAnchorParagraph,
            characterOffset = pageAnchorCharacterOffset, completed = readingCompleted, updatedAt = 0L)
    )
    DisposableEffect(book.id, readerActivity) {
        fun persist() { latestPosition.value.let { readerViewModel.savePosition(it, immediate = true) } }
        val owner = readerActivity as? androidx.lifecycle.LifecycleOwner
        val observer = object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onPause(owner: androidx.lifecycle.LifecycleOwner) = persist()
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose {
            owner?.lifecycle?.removeObserver(observer)
            persist()
        }
    }
    val writeError by repository.writeError.collectAsState()
    LaunchedEffect(writeError) {
        writeError?.let {
            snackbarHostState.showSnackbar(it)
            repository.dismissWriteError()
        }
    }

    fun jumpToPosition(chapterId: String, paragraph: Int, characterOffset: Int = 0) {
        val targetIndex = book.chapters.indexOfFirst { it.id == chapterId }
        if (targetIndex < 0) return
        positionReady = false
        pageAnchorChapter = chapterId
        pageAnchorParagraph = paragraph.coerceAtLeast(0)
        pageAnchorCharacterOffset = characterOffset.coerceAtLeast(0)
        chapterIndex = targetIndex
        positionRequest++

    }
    fun markCompleted() {
        if (readingCompleted) return
        readingCompleted = true
        readerViewModel.savePosition(ReaderPosition(
            book.id, chapter.id, chapter.paragraphs.lastIndex.coerceAtLeast(0),
            characterOffset = chapter.paragraphs.lastOrNull()?.length ?: 0,
            completed = true, updatedAt = System.currentTimeMillis()
        ))
        scope.launch { snackbarHostState.showSnackbar("已读完全书") }
    }
    fun turnPage(delta: Int, markEnd: Boolean = false) {
        if (!positionReady) return
        val targetPage = contentPageIndex + delta
        if (readingMode == ReadingMode.PAGE && targetPage in pages.indices) {
            scope.launch { pagerState.animateScrollToPage(targetPage + pagerWindow.firstPage) }
        } else {
            val target = book.chapters.getOrNull(chapterIndex + delta)
            if (target != null) {
                val atEnd = delta < 0 && readingMode == ReadingMode.PAGE
                jumpToPosition(target.id,
                    if (atEnd) target.paragraphs.lastIndex.coerceAtLeast(0) else 0,
                    if (atEnd) target.paragraphs.lastOrNull()?.length?.minus(1)?.coerceAtLeast(0) ?: 0 else 0)
            } else if (delta > 0 && markEnd) markCompleted()
            else controlsVisible = !controlsVisible
        }
    }
    fun rememberReturnPosition() {
        if (returnChapter != null) return
        returnChapter = chapter.id
        returnParagraph = visibleSourcePosition.first
        returnCharacter = visibleSourcePosition.second
        returnCompleted = readingCompleted
    }
    fun returnToReading() {
        val target = returnChapter ?: return
        readingCompleted = returnCompleted
        jumpToPosition(target, returnParagraph, returnCharacter)
        returnChapter = null
        searchNavigationVisible = false
        showSearch = false
        readerViewModel.search(chapter.id, query = "")
    }
    fun jumpFromToc(chapterId: String, paragraph: Int) {
        rememberReturnPosition()
        controlsVisible = false
        jumpToPosition(chapterId, paragraph)
    }
    LaunchedEffect(readingMode, pagerState, layoutPages, chapter.id, positionRequest, layoutSpec) {
        if (readingMode == ReadingMode.PAGE && pages.isNotEmpty()) {
            pagerState.scrollToPage(layoutPages.pageFor(pageAnchorChapter, pageAnchorParagraph, pageAnchorCharacterOffset) + pagerWindow.firstPage)
            positionReady = true
            snapshotFlow { pagerState.settledPage }.distinctUntilChanged().collect { slot ->
                if (!positionReady || pageAnchorChapter != chapter.id) return@collect
                val delta = pagerWindow.chapterDelta(slot)
                if (delta != 0) {
                    val target = book.chapters[chapterIndex + delta]
                    jumpToPosition(target.id,
                        if (delta < 0) target.paragraphs.lastIndex.coerceAtLeast(0) else 0,
                        if (delta < 0) target.paragraphs.lastOrNull()?.length?.minus(1)?.coerceAtLeast(0) ?: 0 else 0)
                } else layoutPages.getOrNull(pagerWindow.contentPage(slot))?.let { page ->
                    val source = page.firstSourcePosition()
                    pageAnchorChapter = chapter.id
                    pageAnchorParagraph = source.first
                    pageAnchorCharacterOffset = source.second
                    readerViewModel.savePosition(ReaderPosition(book.id, chapter.id, source.first,
                        characterOffset = source.second, completed = readingCompleted, updatedAt = System.currentTimeMillis()))
                }
            }
        }
    }
    val jumpToSearchMatch: (ReaderSearchMatch) -> Unit = { match ->
        rememberReturnPosition()
        controlsVisible = false
        showSearch = false
        searchNavigationVisible = true
        jumpToPosition(match.chapterId, match.paragraphIndex, match.start)
    }
    fun openSourceSelection(paragraphIndex: Int, start: Int, end: Int) {
        val paragraph = chapter.paragraphs.getOrNull(paragraphIndex) ?: return
        val safeStart = minOf(start, end).coerceIn(0, paragraph.length)
        val safeEnd = maxOf(start, end).coerceIn(safeStart, paragraph.length)
        if (safeEnd <= safeStart) return
        noteTarget = noteDraftTarget(
            chapter,
            paragraphIndex,
            TextSelection(safeStart, safeEnd, paragraph.substring(safeStart, safeEnd)),
            resolvedNotes
        )
    }
    fun openNoteAnchor(anchor: ResolvedNoteAnchor) {
        openSourceSelection(anchor.paragraphIndex, anchor.start, anchor.end)
    }
    fun rejectCrossParagraphSelection() {
        scope.launch {
            snackbarHostState.showSnackbar("高亮和批注目前只支持同一段正文，请重新选择")
        }
    }
    LaunchedEffect(chapter.id) {
        if (
            readerUiState.search.scope == ReaderSearchScope.CHAPTER &&
            readerUiState.search.query.trim().length >= 2
        ) readerViewModel.search(chapter.id)
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Box(Modifier.fillMaxSize()) {
        if (readingMode == ReadingMode.PAGE) Box(
            Modifier.padding(padding)
                .padding(start = if (expandedLayout) 320.dp else 0.dp, bottom = 72.dp)
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
                    userScrollEnabled = positionReady,
                    beyondViewportPageCount = 1
                ) { slot ->
                    val boundary = pagerWindow.chapterDelta(slot)
                    if (boundary != 0) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(if (boundary > 0) "下一章" else "上一章")
                        }
                        return@HorizontalPager
                    }
                    val pageIndex = pagerWindow.contentPage(slot)
                    val page = pages[pageIndex]
                    val pageSearchRanges = remember(page, readerUiState.search.matches, readerUiState.search.selectedMatch) {
                        page.searchRanges(readerUiState.search.matches, readerUiState.search.selectedMatch)
                    }
                    val pageFontSizePx = with(density) { settings.fontSize.sp.toPx() }
                    val pageLineHeightPx = with(density) {
                        (settings.fontSize * settings.lineHeight).sp.toPx()
                    }
                    val selectionText = remember(page, pageFontSizePx, pageSearchRanges, accentColor) {
                        page.selectionOverlayText(
                            fontSizePx = pageFontSizePx,
                            accentColor = accentColor,
                            backgrounds = page.notes.map { note ->
                                ReaderTextBackground(
                                    note.start,
                                    note.end,
                                    note.note.color.composeColor().copy(alpha = .30f)
                                )
                            } + pageSearchRanges.map { match ->
                                ReaderTextBackground(
                                    match.start,
                                    match.end,
                                    accentColor.copy(alpha = if (match.selected) .52f else .24f)
                                )
                            }
                        )
                    }
                    Box(
                        Modifier.fillMaxSize()
                            .padding(
                                horizontal = settings.horizontalPadding.dp,
                                vertical = settings.verticalPadding.dp
                            )
                    ) {
                        ReaderSelectableText(
                            text = selectionText,
                            lockPageScroll = true,
                            interactiveRanges = remember(page.footnotes) {
                                page.footnotes.map { ReaderInteractiveRange(it.start, it.end) }
                            },
                            fontSizePx = pageFontSizePx,
                            lineHeightPx = pageLineHeightPx,
                            fontFamily = settings.fontFamily,
                            fontWeight = settings.fontWeight,
                            contentKey = "page-$pageIndex-${page.hashCode()}-${pageSearchRanges.hashCode()}-${accentColor.hashCode()}-${settings.fontSize}-${settings.lineHeight}-${settings.fontFamily}-${settings.fontWeight}",
                            textColorArgb = MaterialTheme.colorScheme.onBackground.toArgb(),
                            modifier = Modifier.fillMaxSize(),
                            onTextTap = { offset, x, _ ->
                                if (offset >= 0) {
                                    val selectedFootnote = page.footnotes.firstOrNull {
                                        textOffsetHitsRange(offset, it.start, it.end)
                                    }
                                    val selectedNote = page.notes.firstOrNull {
                                        textOffsetHitsRange(offset, it.start, it.end)
                                    }
                                    when {
                                        selectedFootnote != null -> footnoteTarget = selectedFootnote.footnote
                                        selectedNote != null -> {
                                            resolvedNotes.firstOrNull { it.note.id == selectedNote.note.id }
                                                ?.let(::openNoteAnchor)
                                        }
                                        x < pageAreaSize.width * .30f -> turnPage(-1)
                                        x > pageAreaSize.width * .70f -> turnPage(1)
                                        else -> controlsVisible = !controlsVisible
                                    }
                                }
                            },
                            onAnnotateSelection = { start, end ->
                                page.sourceSelection(start, end)?.let { source ->
                                    openSourceSelection(source.paragraphIndex, source.start, source.end)
                                } ?: rejectCrossParagraphSelection()
                            }
                        )
                    }
                }
            }
        } else LazyColumn(
            state = listState,
            modifier = Modifier.padding(padding)
                .padding(start = if (expandedLayout) 320.dp else 0.dp, bottom = 72.dp)
                .fillMaxSize().onSizeChanged { pageAreaSize = it }.widthIn(max = 840.dp).combinedClickable(
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
                val section = sectionsByParagraph[index]
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
                val indentLength = if (settings.firstLineIndent && section == null && shouldIndentParagraph(paragraph)) 2 else 0
                val displayParagraph = if (indentLength > 0) "　　$paragraph" else paragraph
                val paragraphFontSize = settings.fontSize + when (section?.level) {
                    2 -> 3f
                    3, 4 -> 1.5f
                    else -> 0f
                }
                val paragraphFontSizePx = with(density) { paragraphFontSize.sp.toPx() }
                val paragraphLineHeightPx = with(density) {
                    (settings.fontSize * settings.lineHeight).sp.toPx()
                }
                val selectionText = remember(
                    displayParagraph,
                    paragraphFootnotes,
                    paragraphNotes,
                    paragraphSearchMatches,
                    readerUiState.search.selectedMatch,
                    accentColor
                ) {
                    selectionOverlayParagraphText(
                        text = displayParagraph,
                        footnotes = paragraphFootnotes,
                        indentLength = indentLength,
                        accentColor = accentColor,
                        backgrounds = paragraphNotes.map { anchor ->
                            ReaderTextBackground(
                                anchor.start + indentLength,
                                anchor.end + indentLength,
                                anchor.note.color.composeColor().copy(alpha = .30f)
                            )
                        } + paragraphSearchMatches.map { match ->
                            ReaderTextBackground(
                                match.start + indentLength,
                                match.end + indentLength,
                                accentColor.copy(
                                    alpha = if (match == readerUiState.search.selectedMatch) .52f else .24f
                                )
                            )
                        }
                    )
                }
                Box(
                    Modifier.fillMaxWidth()
                        .padding(
                            top = if (section != null) 14.dp else 0.dp,
                            bottom = if (section != null) 4.dp else 0.dp
                        )
                ) {
                    ReaderSelectableText(
                        text = selectionText,
                        interactiveRanges = remember(paragraphFootnotes, indentLength) {
                            paragraphFootnotes.map { (reference, _) ->
                                ReaderInteractiveRange(reference.start + indentLength, reference.end + indentLength)
                            }
                        },
                        fontSizePx = paragraphFontSizePx,
                        lineHeightPx = paragraphLineHeightPx,
                        fontFamily = settings.fontFamily,
                        fontWeight = settings.fontWeight,
                        contentKey = "paragraph-${chapter.id}-$index-${displayParagraph.hashCode()}-${paragraphNotes.hashCode()}-${paragraphSearchMatches.hashCode()}-${readerUiState.search.selectedMatch?.hashCode()}-${accentColor.hashCode()}-${settings.fontSize}-${settings.lineHeight}-${settings.fontFamily}-${settings.fontWeight}-${section?.level}",
                        textColorArgb = (if (section != null) accentColor else MaterialTheme.colorScheme.onBackground).toArgb(),
                        modifier = Modifier.fillMaxWidth(),
                        bold = section != null,
                        onTextLayout = { layout ->
                            val lines = ParagraphLineMap(
                                List(layout.lineCount) { layout.getLineStart(it) },
                                List(layout.lineCount) { layout.getLineTop(it) }, indentLength, paragraph.length
                            )
                            if (paragraphLayouts[index] != lines) paragraphLayouts[index] = lines
                        },
                        onTextTap = { offset, _, _ ->
                            if (offset >= 0) {
                                val sourceOffset = (offset - indentLength).coerceIn(0, paragraph.length)
                                val selectedFootnote = paragraphFootnotes.firstOrNull { (reference, _) ->
                                    textOffsetHitsRange(sourceOffset, reference.start, reference.end)
                                }
                                val selectedNote = paragraphNotes.firstOrNull { anchor ->
                                    textOffsetHitsRange(sourceOffset, anchor.start, anchor.end)
                                }
                                if (selectedFootnote != null) {
                                    footnoteTarget = selectedFootnote.second
                                } else if (selectedNote != null) {
                                    openNoteAnchor(selectedNote)
                                } else {
                                    controlsVisible = !controlsVisible
                                }
                            }
                        },
                        onAnnotateSelection = { start, end ->
                            val sourceStart = (start - indentLength).coerceIn(0, paragraph.length)
                            val sourceEnd = (end - indentLength).coerceIn(0, paragraph.length)
                            if (sourceEnd > sourceStart) {
                                openSourceSelection(index, sourceStart, sourceEnd)
                            } else {
                                rejectCrossParagraphSelection()
                            }
                        }
                    )
                }
                if (paragraphNotes.isNotEmpty()) Text(
                    "${paragraphNotes.count { it.note.kind == NoteKind.HIGHLIGHT }} 条高亮 · ${paragraphNotes.count { it.note.kind == NoteKind.ANNOTATION }} 条批注",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            item(key = "chapter-end") {
                FilledTonalButton(
                    onClick = { turnPage(1, markEnd = true) },
                    enabled = chapterIndex < book.chapters.lastIndex || !readingCompleted,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)
                ) { Text(if (chapterIndex < book.chapters.lastIndex) "下一章" else if (readingCompleted) "已读完" else "标记已读完") }
                Spacer(Modifier.height(96.dp))
            }
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
                onSelect = { chapterId, paragraph -> jumpFromToc(chapterId, paragraph) }
            )
        }
            // Menus overlay the fixed text viewport and never trigger repagination.
            if (!controlsVisible && !searchNavigationVisible && returnChapter == null) Text(
                if (readingMode == ReadingMode.PAGE) "${contentPageIndex + 1}/${pages.size.coerceAtLeast(1)}" else readingProgressValue.displayPercent,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (controlsVisible) Box(Modifier.align(Alignment.TopCenter)) { ReaderTopBar(
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
            }
             if ((controlsVisible || returnChapter != null) && !searchNavigationVisible) Surface(
                modifier = Modifier.align(Alignment.BottomCenter),tonalElevation = 2.dp, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().heightIn(min = 64.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { turnPage(-1) }, enabled = if (readingMode == ReadingMode.PAGE) contentPageIndex > 0 || chapterIndex > 0 else chapterIndex > 0) {
                        Icon(Icons.Default.ChevronLeft, if (readingMode == ReadingMode.PAGE) "上一页" else "上一章")
                    }
                    val progressLabel = if (readingMode == ReadingMode.PAGE && pages.isNotEmpty()) {
                            "${contentPageIndex + 1}/${pages.size} · ${readingProgressValue.displayPercent}"
                        } else readingProgressValue.displayPercent
                    Text(
                        progressLabel,
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics {
                            contentDescription = "阅读进度"
                            stateDescription = progressLabel
                        }
                    )
                    if (returnChapter != null) TextButton(onClick = { returnToReading() }) {
                        Text("返回原位")
                    }
                    IconButton(onClick = { turnPage(1, markEnd = true) }, enabled = if (readingMode == ReadingMode.PAGE) {
                        pages.isNotEmpty() && (
                            contentPageIndex < pages.lastIndex ||
                                chapterIndex < book.chapters.lastIndex || !readingCompleted
                            )
                    } else chapterIndex < book.chapters.lastIndex || !readingCompleted) {
                        val atBookEnd = chapterIndex == book.chapters.lastIndex &&
                            (readingMode != ReadingMode.PAGE ||
                                (pages.isNotEmpty() && contentPageIndex == pages.lastIndex))
                        Icon(
                            if (atBookEnd) Icons.Default.Check else Icons.Default.ChevronRight,
                            if (atBookEnd) "标记已读完" else if (readingMode == ReadingMode.PAGE) "下一页" else "下一章"
                        )
                    }
                }
            }
            if (searchNavigationVisible) Surface(
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp),
                shape = RoundedCornerShape(20.dp), shadowElevation = 8.dp
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showSearch = true }) { Icon(Icons.Default.Search, "搜索结果列表") }
                    Text("${(readerUiState.search.selectedIndex + 1).coerceAtLeast(0)}/${readerUiState.search.matches.size}")
                    if (returnChapter != null) TextButton(onClick = { returnToReading() }) {
                        Text("返回原位")
                    }
                    IconButton(onClick = { readerViewModel.moveSearchSelection(-1)?.let(jumpToSearchMatch) }, enabled = readerUiState.search.matches.isNotEmpty()) {
                        Icon(Icons.Default.ChevronLeft, "上一个搜索结果")
                    }
                    IconButton(onClick = { readerViewModel.moveSearchSelection(1)?.let(jumpToSearchMatch) }, enabled = readerUiState.search.matches.isNotEmpty()) {
                        Icon(Icons.Default.ChevronRight, "下一个搜索结果")
                    }
                    IconButton(onClick = {
                        searchNavigationVisible = false
                        readerViewModel.search(chapter.id, query = "")
                    }) { Icon(Icons.Default.Close, "退出文内搜索") }
                }
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
                showToc = false
                jumpFromToc(chapterId, paragraph)
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
                Text("正文自带注释 · 只读", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
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
                scope.launch {
                    readerOperation {
                        repository.toggleBookmark(book.id, chapter.id, target.paragraphIndex, target.selection.text)
                    }.onSuccess { added ->
                        noteTarget = null
                        snackbarHostState.showSnackbar(if (added) "已添加书签" else "已移除书签")
                    }.onFailure {
                        snackbarHostState.showSnackbar("书签保存失败，请重试")
                    }
                }
            },
            onSave = { value ->
                scope.launch {
                    readerOperation {
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
                        readerOperation { repository.deleteNote(note.id) }
                            .onSuccess {
                                        noteTarget = null
                                snackbarHostState.showSnackbar("笔记已删除")
                            }
                            .onFailure {
                                snackbarHostState.showSnackbar(it.message ?: "笔记删除失败")
                            }
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
