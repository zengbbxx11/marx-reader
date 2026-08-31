package org.marxreader.app.ui.reader

import org.marxreader.app.data.Book
import org.marxreader.app.data.ReaderPosition

data class ReaderUiState(
    val loading: Boolean = true,
    val book: Book? = null,
    val savedPosition: ReaderPosition? = null,
    val search: ReaderSearchState = ReaderSearchState(),
    val error: String? = null
)
