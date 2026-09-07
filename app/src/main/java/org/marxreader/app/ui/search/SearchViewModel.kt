package org.marxreader.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.marxreader.app.data.*

internal data class SearchUiState(
    val results: List<SearchHit> = emptyList(),
    val searching: Boolean = false,
    val error: String? = null
)

internal class SearchViewModel(
    private val repository: LibraryRepository,
    private val preferences: ReaderPreferences
) : ViewModel() {
    private val mutableState = MutableStateFlow(SearchUiState())
    val state = mutableState.asStateFlow()
    private var searchJob: Job? = null

    fun search(query: String, scope: SearchScope, authorId: String?) {
        searchJob?.cancel()
        val normalized = query.trim()
        mutableState.value = SearchUiState(searching = normalized.length >= 2)
        if (normalized.length < 2) return
        searchJob = viewModelScope.launch {
            delay(250)
            readerOperation { repository.search(normalized, scope, authorId) }
                .onSuccess {
                    mutableState.value = SearchUiState(results = it)
                    preferences.addSearchHistory(normalized)
                }.onFailure {
                    mutableState.value = SearchUiState(error = "搜索失败，请重试")
                }
        }
    }

    companion object {
        fun factory(repository: LibraryRepository, preferences: ReaderPreferences) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SearchViewModel(repository, preferences) as T
            }
    }
}
