package org.marxreader.app.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.marxreader.app.data.readerOperation
import org.marxreader.app.data.LibraryRepository
import org.marxreader.app.data.ReaderPosition

class ReaderViewModel(
    private val repository: LibraryRepository,
    private val bookId: String
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = mutableUiState.asStateFlow()
    private var searchJob: Job? = null
    private var progressJob: Job? = null
    private var lastRequestedPosition: ReaderPosition? = null
    private var pendingPosition: ReaderPosition? = null
    private var lastEnqueuedPosition: ReaderPosition? = null
    private var lastProgressWrite: Deferred<Unit>? = null

    init {
        viewModelScope.launch {
            readerOperation {
                val book = repository.loadBook(bookId)
                val savedPosition = withContext(Dispatchers.IO) { repository.progress(bookId) }
                book to savedPosition
            }
                .onSuccess { (book, savedPosition) ->
                    mutableUiState.value = if (book == null) {
                        ReaderUiState(loading = false, error = "作品正文不存在")
                    } else ReaderUiState(
                        loading = false,
                        book = book,
                        savedPosition = savedPosition
                    )
                }
                .onFailure { error ->
                    mutableUiState.value = ReaderUiState(
                        loading = false,
                        error = error.message ?: "作品正文加载失败"
                    )
                }
        }
    }

    fun savePosition(position: ReaderPosition, immediate: Boolean = false) {
        val normalized = position.copy(updatedAt = 0L)
        if (!immediate && lastRequestedPosition == normalized) return
        lastRequestedPosition = normalized
        pendingPosition = position
        if (immediate) {
            progressJob?.cancel()
            // Immediate saves (exit/pause/mark-completed) also refresh library screens.
            persistPosition(position, progressOnly = false)
        } else if (progressJob?.isActive != true) progressJob = viewModelScope.launch {
            // Sample the latest position even during a long, uninterrupted scroll.
            delay(400)
            pendingPosition?.let { persistPosition(it, progressOnly = true) }
        }
    }

    private fun persistPosition(position: ReaderPosition, progressOnly: Boolean) {
        mutableUiState.value = mutableUiState.value.copy(savedPosition = position)
        lastEnqueuedPosition = position.copy(updatedAt = 0L)
        lastProgressWrite = repository.saveProgress(
            bookId = position.bookId,
            chapterId = position.chapterId,
            paragraphIndex = position.paragraphIndex,
            characterOffset = position.characterOffset,
            completed = position.completed,
            progressOnly = progressOnly
        )
    }

    /** Awaitable persist for the exit flush; failures surface through writeError like the async path. */
    suspend fun persistPositionNow(position: ReaderPosition) {
        mutableUiState.value = mutableUiState.value.copy(savedPosition = position)
        try {
            // Exit already enqueued an immediate save. Await that application-owned write,
            // including if it has finished, rather than issuing the same SQLite write twice.
            val write = lastProgressWrite.takeIf {
                lastEnqueuedPosition == position.copy(updatedAt = 0L)
            }
            if (write != null) write.await()
            else {
                persistPosition(position, progressOnly = false)
                lastProgressWrite?.await()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // The repository reports this write failure through writeError.
        }
    }

    fun search(
        currentChapterId: String,
        query: String = mutableUiState.value.search.query,
        scope: ReaderSearchScope = mutableUiState.value.search.scope
    ) {
        searchJob?.cancel()
        val normalized = query.trim()
        mutableUiState.value = mutableUiState.value.copy(
            search = mutableUiState.value.search.copy(
                query = query,
                scope = scope,
                matches = if (normalized.length < 2) emptyList() else mutableUiState.value.search.matches,
                selectedIndex = if (normalized.length < 2) -1 else mutableUiState.value.search.selectedIndex,
                searching = normalized.length >= 2
            )
        )
        val book = mutableUiState.value.book ?: return
        if (normalized.length < 2) return
        searchJob = viewModelScope.launch {
            delay(160)
            val matches = withContext(Dispatchers.Default) {
                val context = currentCoroutineContext()
                findReaderMatches(book, currentChapterId, normalized, scope) { context.ensureActive() }
            }
            val current = mutableUiState.value.search
            if (current.query.trim() == normalized && current.scope == scope) {
                mutableUiState.value = mutableUiState.value.copy(
                    search = current.copy(
                        matches = matches,
                        selectedIndex = if (matches.isEmpty()) -1 else 0,
                        searching = false
                    )
                )
            }
        }
    }

    fun selectSearchMatch(index: Int): ReaderSearchMatch? {
        val current = mutableUiState.value.search
        if (current.matches.isEmpty()) return null
        val selectedIndex = index.coerceIn(current.matches.indices)
        mutableUiState.value = mutableUiState.value.copy(
            search = current.copy(selectedIndex = selectedIndex)
        )
        return current.matches[selectedIndex]
    }

    fun moveSearchSelection(delta: Int): ReaderSearchMatch? {
        val current = mutableUiState.value.search
        if (current.matches.isEmpty()) return null
        val selected = if (current.selectedIndex < 0) 0 else {
            (current.selectedIndex + delta).mod(current.matches.size)
        }
        return selectSearchMatch(selected)
    }

    companion object {
        fun factory(repository: LibraryRepository, bookId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(ReaderViewModel::class.java))
                    return ReaderViewModel(repository, bookId) as T
                }
            }
    }
}
