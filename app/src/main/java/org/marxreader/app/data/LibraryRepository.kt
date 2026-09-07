package org.marxreader.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.update
import java.security.MessageDigest

data class SearchIndexState(
    val building: Boolean = false,
    val current: Int = 0,
    val total: Int = 0,
    val error: String? = null
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryRepository(private val context: Context) {
    private val database = ReaderDatabase(context)
    private val writeDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val ioScope = CoroutineScope(SupervisorJob() + writeDispatcher)
    private val mutableCatalog = MutableStateFlow(LibraryCatalog(emptyList(), emptyList()))
    private val loadedBooks = object : LinkedHashMap<String, Book>(6, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Book>?): Boolean = size > 4
    }
    private val mutableSearchIndexState = MutableStateFlow(SearchIndexState())
    private val searchIndexMutex = Mutex()
    private val initializationMutex = Mutex()
    private val writeMutex = Mutex()
    private var indexBuild: Deferred<Unit>? = null
    private val mutableDataRevision = MutableStateFlow(0L)
    private val mutableNotesRevision = MutableStateFlow(0L)
    private val mutableWriteError = MutableStateFlow<String?>(null)
    val dataRevision = mutableDataRevision.asStateFlow()
    val notesRevision = mutableNotesRevision.asStateFlow()
    val writeError = mutableWriteError.asStateFlow()
    @Volatile private var catalogFingerprint = ""
    @Volatile private var searchReadyFor = ""
    val catalog = mutableCatalog.asStateFlow()
    val searchIndexState = mutableSearchIndexState.asStateFlow()

    suspend fun initialize() = withContext(Dispatchers.IO) {
        initializationMutex.withLock {
            if (catalogFingerprint.isEmpty()) reload()
        }
    }

    suspend fun reload() = withContext(Dispatchers.IO) {
        val bundledJson = context.assets.open("library/catalog.json").bufferedReader().use { it.readText() }
        val bundled = parseCatalog(bundledJson)
        synchronized(loadedBooks) { loadedBooks.clear() }
        mutableCatalog.value = bundled.copy(books = bundled.books.sortedBy { it.displayTitle })
        catalogFingerprint = sha256(bundledJson)
    }

    suspend fun loadBook(bookId: String): Book? = withContext(Dispatchers.IO) {
        synchronized(loadedBooks) { loadedBooks[bookId] } ?: readBook(bookId)?.also {
            synchronized(loadedBooks) { loadedBooks[bookId] = it }
        }
    }

    fun progress(bookId: String) = database.progress(bookId)
    fun allProgress() = database.allProgress()
    fun chapterProgress(bookId: String) = database.chapterProgress(bookId)
    fun saveProgress(
        bookId: String,
        chapterId: String,
        paragraphIndex: Int,
        characterOffset: Int = 0,
        completed: Boolean = false
    ) {
        ioScope.launch {
            try {
                write { database.saveProgress(bookId, chapterId, paragraphIndex, characterOffset, completed) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableWriteError.value = "阅读进度保存失败，请稍后重试"
            }
        }
    }
    fun dismissWriteError() { mutableWriteError.value = null }
    private suspend fun <T> write(notesChanged: Boolean = false, block: () -> T): T = withContext(writeDispatcher) {
        writeMutex.withLock {
            block().also {
                mutableDataRevision.update { it + 1 }
                if (notesChanged) mutableNotesRevision.update { it + 1 }
            }
        }
    }
    suspend fun toggleBookmark(bookId: String, chapterId: String, paragraphIndex: Int, excerpt: String): Boolean =
        write { database.toggleBookmark(bookId, chapterId, paragraphIndex, excerpt) }
    fun bookmarks() = database.bookmarks()
    suspend fun saveNote(note: Note): Long = write(notesChanged = true) { database.saveNote(note) }
    fun notes() = database.notes()
    suspend fun notesForChapter(bookId: String, chapterId: String): List<Note> =
        withContext(Dispatchers.IO) { database.notesForChapter(bookId, chapterId) }
    suspend fun resolveNotes(book: Book, chapter: Chapter): List<ResolvedNoteAnchor> = withContext(Dispatchers.IO) {
        database.notesForChapter(book.id, chapter.id).map { note ->
            resolveNoteAnchor(note, chapter.paragraphs).also { resolved ->
                if (resolved.status == NoteAnchorStatus.RELOCATED || note.anchorState != resolved.status.name) {
                    database.updateNoteAnchor(resolved)
                }
            }
        }
    }
    suspend fun deleteBookmark(id: Long) = write { database.deleteBookmark(id) }
    suspend fun updateNote(id: Long, text: String) = write(notesChanged = true) { database.updateNote(id, text) }
    suspend fun deleteNote(id: Long) = write(notesChanged = true) { database.deleteNote(id) }
    suspend fun startReadingSession(
        bookId: String,
        chapterId: String,
        paragraphIndex: Int
    ): Long = withContext(Dispatchers.IO) {
        database.startReadingSession(bookId, chapterId, paragraphIndex)
    }
    suspend fun addReadingTime(
        sessionId: Long,
        activeMillis: Long,
        chapterId: String,
        paragraphIndex: Int
    ) = write {
        database.addReadingTime(sessionId, activeMillis, chapterId, paragraphIndex)
    }
    suspend fun readingStatistics(): ReadingStatistics = withContext(Dispatchers.IO) {
        database.readingStatistics()
    }
    suspend fun deleteProgress(bookId: String) = write { database.deleteProgress(bookId) }
    suspend fun clearProgress() = write { database.clearProgress() }
    suspend fun clearBookmarks() = write { database.clearBookmarks() }
    suspend fun clearNotes() = write(notesChanged = true) { database.clearNotes() }

    suspend fun search(
        query: String,
        scope: SearchScope = SearchScope.ALL,
        authorId: String? = null
    ): List<SearchHit> = withContext(Dispatchers.IO) {
        initialize()
        val build = searchIndexMutex.withLock {
            indexBuild?.takeIf { it.isActive } ?: ioScope.async {
            if (searchReadyFor != catalogFingerprint) {
                val books = mutableCatalog.value.books
                val targetFingerprint = catalogFingerprint
                mutableSearchIndexState.value = SearchIndexState(building = true, total = books.size)
                try {
                    database.rebuildSearchIndex(targetFingerprint, sequence {
                        books.forEachIndexed { index, metadata ->
                            yield(requireNotNull(readBook(metadata.id)) { "作品正文缺失：${metadata.displayTitle}" })
                            mutableSearchIndexState.value = SearchIndexState(
                                building = true,
                                current = index + 1,
                                total = books.size
                            )
                        }
                    })
                    searchReadyFor = targetFingerprint
                    mutableSearchIndexState.value = SearchIndexState(current = books.size, total = books.size)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    mutableSearchIndexState.value = SearchIndexState(
                        total = books.size,
                        error = error.message ?: "搜索索引建立失败"
                    )
                    throw error
                }
            }
            }.also { indexBuild = it }
        }
        // Cancelling a query must not cancel the shared first-run index build.
        build.await()
        val bookIds = authorId?.let { id -> mutableCatalog.value.booksForAuthor(id).map { it.id }.toSet() }
        database.search(query, scope, bookIds)
    }

    private fun readBook(bookId: String): Book? {
        val json = runCatching {
            context.assets.open("library/books/$bookId.json").bufferedReader().use { it.readText() }
        }.getOrNull() ?: return null
        return parseCatalog(json).book(bookId)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

}
