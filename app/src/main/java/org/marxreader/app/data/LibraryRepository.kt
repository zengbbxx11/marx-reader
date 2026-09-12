package org.marxreader.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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

/** Equal consecutive errors must still re-notify, so each report carries an occurrence id. */
data class WriteError(val message: String, val occurrence: Long)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryRepository(private val context: Context) {
    private val database = ReaderDatabase(context)
    private val writeDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val ioScope = CoroutineScope(SupervisorJob() + writeDispatcher)
    private val pendingProgress = mutableMapOf<String, ReaderPosition>()
    private val mutableCatalog = MutableStateFlow(LibraryCatalog(emptyList(), emptyList()))
    private val loadedBookBudget = readerPageCacheBudget(context) * 4
    private val bookLoadMutex = Mutex()
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
    private val mutableProgressRevision = MutableStateFlow(0L)
    private val mutableWriteError = MutableStateFlow<WriteError?>(null)
    val dataRevision = mutableDataRevision.asStateFlow()
    val notesRevision = mutableNotesRevision.asStateFlow()
    /** Live position ticks (throttled saves); dataRevision stays quiet so off-reader screens do not reload. */
    val progressRevision = mutableProgressRevision.asStateFlow()
    val writeError = mutableWriteError.asStateFlow()
    private var writeErrorCount = 0L
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
        bookLoadMutex.withLock {
            synchronized(loadedBooks) { loadedBooks[bookId] } ?: readBook(bookId)?.also { book ->
                synchronized(loadedBooks) {
                    if (book.characterCount <= loadedBookBudget) {
                        loadedBooks[bookId] = book
                        val iterator = loadedBooks.entries.iterator()
                        var weight = loadedBooks.values.sumOf { it.characterCount.toLong() }
                        while (weight > loadedBookBudget && iterator.hasNext()) {
                            weight -= iterator.next().value.characterCount
                            iterator.remove()
                        }
                    }
                }
            }
        }
    }

    /** Release rebuildable content without disk IO or forcing lazy initialization. */
    fun trimLoadedBooks(keep: Int = 1) {
        synchronized(loadedBooks) {
            val iterator = loadedBooks.entries.iterator()
            while (loadedBooks.size > keep.coerceAtLeast(0) && iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
    }

    fun progress(bookId: String): ReaderPosition? =
        synchronized(pendingProgress) { pendingProgress[bookId] } ?: database.progress(bookId)
    fun allProgress() = database.allProgress()
    fun chapterProgress(bookId: String) = database.chapterProgress(bookId)
    fun saveProgress(
        bookId: String,
        chapterId: String,
        paragraphIndex: Int,
        characterOffset: Int = 0,
        completed: Boolean = false,
        progressOnly: Boolean = true
    ) {
        enqueueProgress(bookId, chapterId, paragraphIndex, characterOffset, completed, progressOnly)
    }

    /** Register the latest position before returning; the application owns its disk write. */
    private fun enqueueProgress(
        bookId: String,
        chapterId: String,
        paragraphIndex: Int,
        characterOffset: Int,
        completed: Boolean,
        progressOnly: Boolean
    ): Deferred<Unit> = synchronized(pendingProgress) {
        val position = ReaderPosition(bookId, chapterId, paragraphIndex,
            System.currentTimeMillis(), characterOffset.coerceAtLeast(0), completed)
        pendingProgress[bookId] = position
        ioScope.async {
            try {
                write(progressTick = progressOnly) {
                    database.saveProgress(bookId, chapterId, paragraphIndex, characterOffset, completed)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportWriteError("阅读进度保存失败，请稍后重试")
                throw error
            } finally {
                synchronized(pendingProgress) {
                    // An older write must never discard a newer pending position.
                    if (pendingProgress[bookId] === position) pendingProgress.remove(bookId)
                }
            }
        }
    }

    suspend fun saveProgressNow(
        bookId: String,
        chapterId: String,
        paragraphIndex: Int,
        characterOffset: Int = 0,
        completed: Boolean = false,
        progressOnly: Boolean = true
    ) {
        enqueueProgress(bookId, chapterId, paragraphIndex, characterOffset, completed, progressOnly).await()
    }
    fun dismissWriteError(error: WriteError) { mutableWriteError.compareAndSet(error, null) }
    @Synchronized private fun reportWriteError(message: String) {
        mutableWriteError.value = WriteError(message, ++writeErrorCount)
    }
    private suspend fun <T> write(notesChanged: Boolean = false, progressTick: Boolean = false, block: () -> T): T = withContext(writeDispatcher) {
        writeMutex.withLock {
            block().also {
                if (progressTick) mutableProgressRevision.update { it + 1 }
                else mutableDataRevision.update { it + 1 }
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
        try {
            database.notesForChapter(book.id, chapter.id).map { note ->
                resolveNoteAnchor(note, chapter.paragraphs).also { resolved ->
                    if (resolved.status == NoteAnchorStatus.RELOCATED || note.anchorState != resolved.status.name) {
                        database.updateNoteAnchor(resolved)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            reportWriteError("笔记加载失败，请稍后重试")
            emptyList()
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
            indexBuild?.takeIf { it.isActive } ?: ioScope.async(Dispatchers.IO) {
            if (searchReadyFor != catalogFingerprint) {
                val books = mutableCatalog.value.books
                val targetFingerprint = catalogFingerprint
                mutableSearchIndexState.value = SearchIndexState(building = true, total = books.size)
                try {
                    database.rebuildSearchIndex(targetFingerprint, sequence {
                        books.forEachIndexed { index, metadata ->
                            // Skip one unreadable book so a single bad file cannot fail the whole index.
                            readBook(metadata.id)?.let { yield(it) }
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
        // One malformed book file must degrade to "missing", never break the reader or the index build.
        return runCatching { parseCatalog(json).book(bookId) }.getOrNull()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

}
