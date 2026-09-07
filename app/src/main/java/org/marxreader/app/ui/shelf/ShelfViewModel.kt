package org.marxreader.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.marxreader.app.data.*

internal data class ShelfUiState(
    val progress: List<ReadingProgress> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
    val notes: List<Note> = emptyList(),
    val statistics: ReadingStatistics = ReadingStatistics(),
    val loading: Boolean = true,
    val error: String? = null
)

internal class ShelfViewModel(private val repository: LibraryRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(ShelfUiState())
    val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.dataRevision.collectLatest { load() }
        }
    }

    fun retry() { viewModelScope.launch { load() } }

    private suspend fun load() {
        readerOperation {
            withContext(Dispatchers.IO) {
                ShelfUiState(
                    progress = repository.allProgress().values.sortedByDescending { it.updatedAt },
                    bookmarks = repository.bookmarks(),
                    notes = repository.notes(),
                    statistics = repository.readingStatistics(),
                    loading = false
                )
            }
        }.onSuccess { mutableState.value = it }
            .onFailure { mutableState.value = mutableState.value.copy(loading = false, error = "书架加载失败，请重试") }
    }

    companion object {
        fun factory(repository: LibraryRepository) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ShelfViewModel(repository) as T
        }
    }
}
