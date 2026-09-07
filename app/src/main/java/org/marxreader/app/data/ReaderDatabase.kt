package org.marxreader.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class ReaderDatabase(context: Context) :
    SQLiteOpenHelper(context, "reader.db", null, 6) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE progress(
                book_id TEXT PRIMARY KEY,
                chapter_id TEXT NOT NULL,
                paragraph_index INTEGER NOT NULL DEFAULT 0,
                character_offset INTEGER NOT NULL DEFAULT 0,
                completed INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE bookmarks(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                book_id TEXT NOT NULL,
                chapter_id TEXT NOT NULL,
                paragraph_index INTEGER NOT NULL,
                excerpt TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                character_offset INTEGER NOT NULL DEFAULT 0,
                UNIQUE(book_id, chapter_id, paragraph_index)
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE notes(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                book_id TEXT NOT NULL,
                chapter_id TEXT NOT NULL,
                paragraph_index INTEGER NOT NULL,
                excerpt TEXT NOT NULL,
                note_text TEXT NOT NULL,
                selection_start INTEGER NOT NULL DEFAULT 0,
                selection_end INTEGER NOT NULL DEFAULT 0,
                paragraph_hash TEXT NOT NULL DEFAULT '',
                anchor_prefix TEXT NOT NULL DEFAULT '',
                anchor_suffix TEXT NOT NULL DEFAULT '',
                anchor_state TEXT NOT NULL DEFAULT 'EXACT',
                note_kind TEXT NOT NULL DEFAULT 'ANNOTATION',
                highlight_color TEXT NOT NULL DEFAULT 'YELLOW',
                pinned INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX notes_location ON notes(book_id, chapter_id, paragraph_index)")
        createNoteTags(db)
        createReadingSessions(db)
        db.execSQL("""
            CREATE VIRTUAL TABLE content_fts USING fts4(
                book_id, chapter_id, paragraph_index, title, chapter_title, content,
                tokenize=unicode61
            )
        """.trimIndent())
        db.execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        createChapterProgress(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createChapterProgress(db)
        if (oldVersion < 3) migrateNotesV3(db)
        if (oldVersion < 4) migrateProgressV4(db)
        if (oldVersion < 5) migrateReaderDataV5(db)
        if (oldVersion < 6) migrateBookmarksV6(db)
    }

    private fun migrateNotesV3(db: SQLiteDatabase) {
        // SQLiteOpenHelper already wraps onUpgrade in a transaction.
        db.execSQL("ALTER TABLE notes RENAME TO notes_v2")
        db.execSQL("""
                CREATE TABLE notes(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    book_id TEXT NOT NULL,
                    chapter_id TEXT NOT NULL,
                    paragraph_index INTEGER NOT NULL,
                    excerpt TEXT NOT NULL,
                    note_text TEXT NOT NULL,
                    selection_start INTEGER NOT NULL DEFAULT 0,
                    selection_end INTEGER NOT NULL DEFAULT 0,
                    paragraph_hash TEXT NOT NULL DEFAULT '',
                    anchor_prefix TEXT NOT NULL DEFAULT '',
                    anchor_suffix TEXT NOT NULL DEFAULT '',
                    anchor_state TEXT NOT NULL DEFAULT 'LEGACY',
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
        """.trimIndent())
        db.execSQL("""
                INSERT INTO notes(
                    id, book_id, chapter_id, paragraph_index, excerpt, note_text,
                    selection_start, selection_end, paragraph_hash, anchor_prefix,
                    anchor_suffix, anchor_state, created_at, updated_at
                )
                SELECT id, book_id, chapter_id, paragraph_index, excerpt, note_text,
                    0, length(excerpt), '', '', '', 'LEGACY', updated_at, updated_at
                FROM notes_v2
        """.trimIndent())
        db.execSQL("DROP TABLE notes_v2")
        db.execSQL("CREATE INDEX notes_location ON notes(book_id, chapter_id, paragraph_index)")
    }

    private fun createChapterProgress(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS chapter_progress(
                book_id TEXT NOT NULL,
                chapter_id TEXT NOT NULL,
                paragraph_index INTEGER NOT NULL DEFAULT 0,
                character_offset INTEGER NOT NULL DEFAULT 0,
                completed INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(book_id, chapter_id)
            )
        """.trimIndent())
    }

    private fun migrateProgressV4(db: SQLiteDatabase) {
        db.addColumnIfMissing("progress", "character_offset", "INTEGER NOT NULL DEFAULT 0")
        db.addColumnIfMissing("progress", "completed", "INTEGER NOT NULL DEFAULT 0")
        db.addColumnIfMissing("chapter_progress", "character_offset", "INTEGER NOT NULL DEFAULT 0")
        db.addColumnIfMissing("chapter_progress", "completed", "INTEGER NOT NULL DEFAULT 0")
    }

    private fun migrateReaderDataV5(db: SQLiteDatabase) {
        db.addColumnIfMissing("notes", "note_kind", "TEXT NOT NULL DEFAULT 'ANNOTATION'")
        db.addColumnIfMissing("notes", "highlight_color", "TEXT NOT NULL DEFAULT 'YELLOW'")
        db.addColumnIfMissing("notes", "pinned", "INTEGER NOT NULL DEFAULT 0")
        createNoteTags(db)
        createReadingSessions(db)
    }

    private fun migrateBookmarksV6(db: SQLiteDatabase) {
        // Keep upgrades safe for legacy databases that may not have a bookmarks table.
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS bookmarks(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                book_id TEXT NOT NULL,
                chapter_id TEXT NOT NULL,
                paragraph_index INTEGER NOT NULL,
                excerpt TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                character_offset INTEGER NOT NULL DEFAULT 0,
                UNIQUE(book_id, chapter_id, paragraph_index)
            )
        """.trimIndent())
        db.addColumnIfMissing("bookmarks", "character_offset", "INTEGER NOT NULL DEFAULT 0")
    }

    private fun createNoteTags(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS note_tags(
                note_id INTEGER NOT NULL,
                tag TEXT NOT NULL,
                PRIMARY KEY(note_id, tag),
                FOREIGN KEY(note_id) REFERENCES notes(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS note_tags_tag ON note_tags(tag)")
    }

    private fun createReadingSessions(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS reading_sessions(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                book_id TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                ended_at INTEGER NOT NULL,
                active_millis INTEGER NOT NULL DEFAULT 0,
                start_chapter_id TEXT NOT NULL,
                start_paragraph_index INTEGER NOT NULL DEFAULT 0,
                end_chapter_id TEXT NOT NULL,
                end_paragraph_index INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS reading_sessions_time ON reading_sessions(started_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS reading_sessions_book ON reading_sessions(book_id)")
    }

    fun rebuildSearchIndex(fingerprint: String, books: Sequence<Book>) {
        val current = readableDatabase.rawQuery(
            "SELECT value FROM metadata WHERE key='catalog_fingerprint'", null
        ).use { if (it.moveToFirst()) it.getString(0) else null }
        if (current == fingerprint) return

        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("content_fts", null, null)
            books.forEach { book ->
                book.chapters.forEach { chapter ->
                    chapter.paragraphs.forEachIndexed { index, paragraph ->
                        writableDatabase.insertOrThrow("content_fts", null, ContentValues().apply {
                            put("book_id", book.id)
                            put("chapter_id", chapter.id)
                            put("paragraph_index", index)
                            put("title", book.displayTitle)
                            put("chapter_title", chapter.title)
                            put("content", paragraph)
                        })
                    }
                }
            }
            writableDatabase.insertWithOnConflict(
                "metadata", null, ContentValues().apply {
                    put("key", "catalog_fingerprint")
                    put("value", fingerprint)
                }, SQLiteDatabase.CONFLICT_REPLACE
            )
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun saveProgress(
        bookId: String,
        chapterId: String,
        paragraphIndex: Int,
        characterOffset: Int = 0,
        completed: Boolean = false
    ) {
        writableDatabase.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            check(writableDatabase.insertWithOnConflict("progress", null, ContentValues().apply {
                put("book_id", bookId)
                put("chapter_id", chapterId)
                put("paragraph_index", paragraphIndex)
                put("character_offset", characterOffset.coerceAtLeast(0))
                put("completed", if (completed) 1 else 0)
                put("updated_at", now)
            }, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "阅读进度写入失败" }
            check(writableDatabase.insertWithOnConflict("chapter_progress", null, ContentValues().apply {
                put("book_id", bookId)
                put("chapter_id", chapterId)
                put("paragraph_index", paragraphIndex)
                put("character_offset", characterOffset.coerceAtLeast(0))
                put("completed", if (completed) 1 else 0)
                put("updated_at", now)
            }, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "阅读进度写入失败" }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun progress(bookId: String): ReadingProgress? = readableDatabase.rawQuery(
        "SELECT chapter_id, paragraph_index, updated_at, character_offset, completed FROM progress WHERE book_id=?",
        arrayOf(bookId)
    ).use {
        if (!it.moveToFirst()) null
        else ReadingProgress(
            bookId = bookId,
            chapterId = it.getString(0),
            paragraphIndex = it.getInt(1),
            updatedAt = it.getLong(2),
            characterOffset = it.getInt(3),
            completed = it.getInt(4) != 0
        )
    }

    fun allProgress(): Map<String, ReadingProgress> = readableDatabase.rawQuery(
        "SELECT book_id, chapter_id, paragraph_index, updated_at, character_offset, completed FROM progress", null
    ).use { cursor ->
        buildMap {
            while (cursor.moveToNext()) {
                val item = ReadingProgress(
                    bookId = cursor.getString(0),
                    chapterId = cursor.getString(1),
                    paragraphIndex = cursor.getInt(2),
                    updatedAt = cursor.getLong(3),
                    characterOffset = cursor.getInt(4),
                    completed = cursor.getInt(5) != 0
                )
                put(item.bookId, item)
            }
        }
    }

    fun chapterProgress(bookId: String): Map<String, Int> = readableDatabase.rawQuery(
        "SELECT chapter_id, paragraph_index FROM chapter_progress WHERE book_id=?",
        arrayOf(bookId)
    ).use { cursor ->
        buildMap {
            while (cursor.moveToNext()) put(cursor.getString(0), cursor.getInt(1))
        }
    }

    fun toggleBookmark(bookId: String, chapterId: String, paragraphIndex: Int, excerpt: String): Boolean {
        val exists = readableDatabase.rawQuery(
            "SELECT id FROM bookmarks WHERE book_id=? AND chapter_id=? AND paragraph_index=?",
            arrayOf(bookId, chapterId, paragraphIndex.toString())
        ).use { it.moveToFirst() }
        if (exists) {
            writableDatabase.delete(
                "bookmarks", "book_id=? AND chapter_id=? AND paragraph_index=?",
                arrayOf(bookId, chapterId, paragraphIndex.toString())
            )
            return false
        }
        writableDatabase.insertOrThrow("bookmarks", null, ContentValues().apply {
            put("book_id", bookId)
            put("chapter_id", chapterId)
            put("paragraph_index", paragraphIndex)
            put("excerpt", excerpt.take(180))
            put("created_at", System.currentTimeMillis())
        })
        return true
    }

    fun bookmarks(): List<Bookmark> = readableDatabase.rawQuery(
        "SELECT id, book_id, chapter_id, paragraph_index, excerpt, created_at FROM bookmarks ORDER BY created_at DESC",
        null
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(
                Bookmark(cursor.getLong(0), cursor.getString(1), cursor.getString(2),
                    cursor.getInt(3), cursor.getString(4), cursor.getLong(5))
            )
        }
    }

    fun deleteBookmark(id: Long) {
        writableDatabase.delete("bookmarks", "id=?", arrayOf(id.toString()))
    }

    fun saveNote(note: Note): Long {
        require(note.kind == NoteKind.HIGHLIGHT || note.text.isNotBlank()) { "批注内容不能为空" }
        val existingId = if (note.id > 0) note.id else readableDatabase.rawQuery(
            """SELECT id FROM notes WHERE book_id=? AND chapter_id=? AND paragraph_index=?
                AND selection_start=? AND selection_end=? LIMIT 1""".trimIndent(),
            arrayOf(
                note.bookId, note.chapterId, note.paragraphIndex.toString(),
                note.selectionStart.toString(), note.selectionEnd.toString()
            )
        ).use { if (it.moveToFirst()) it.getLong(0) else null }
        val values = ContentValues().apply {
            put("book_id", note.bookId)
            put("chapter_id", note.chapterId)
            put("paragraph_index", note.paragraphIndex)
            put("excerpt", note.excerpt)
            put("note_text", note.text.trim())
            put("selection_start", note.selectionStart)
            put("selection_end", note.selectionEnd)
            put("paragraph_hash", note.paragraphHash)
            put("anchor_prefix", note.anchorPrefix)
            put("anchor_suffix", note.anchorSuffix)
            put("anchor_state", note.anchorState)
            put("note_kind", note.kind.name)
            put("highlight_color", note.color.name)
            put("pinned", if (note.pinned) 1 else 0)
            put("created_at", note.createdAt)
            put("updated_at", note.updatedAt)
        }
        writableDatabase.beginTransaction()
        return try {
            val id = if (existingId != null) {
                writableDatabase.update("notes", values, "id=?", arrayOf(existingId.toString()))
                existingId
            } else writableDatabase.insertOrThrow("notes", null, values)
            replaceNoteTags(id, note.tags)
            writableDatabase.setTransactionSuccessful()
            id
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun notes(): List<Note> = readableDatabase.rawQuery(
        "$NOTE_COLUMNS FROM notes ORDER BY pinned DESC, updated_at DESC",
        null
    ).use(::readNotes)

    fun notesForChapter(bookId: String, chapterId: String): List<Note> = readableDatabase.rawQuery(
        "$NOTE_COLUMNS FROM notes WHERE book_id=? AND chapter_id=? ORDER BY paragraph_index, selection_start",
        arrayOf(bookId, chapterId)
    ).use(::readNotes)

    fun updateNote(id: Long, text: String) {
        writableDatabase.update("notes", ContentValues().apply {
            put("note_text", text.trim())
            put("note_kind", if (text.isBlank()) NoteKind.HIGHLIGHT.name else NoteKind.ANNOTATION.name)
            put("updated_at", System.currentTimeMillis())
        }, "id=?", arrayOf(id.toString()))
    }

    private fun replaceNoteTags(noteId: Long, tags: List<String>) {
        writableDatabase.delete("note_tags", "note_id=?", arrayOf(noteId.toString()))
        normalizeNoteTags(tags).forEach { tag ->
            writableDatabase.insert("note_tags", null, ContentValues().apply {
                put("note_id", noteId)
                put("tag", tag)
            })
        }
    }

    fun deleteNote(id: Long) {
        writableDatabase.delete("notes", "id=?", arrayOf(id.toString()))
    }

    fun updateNoteAnchor(anchor: ResolvedNoteAnchor) {
        writableDatabase.update("notes", ContentValues().apply {
            put("paragraph_index", anchor.paragraphIndex)
            put("selection_start", anchor.start)
            put("selection_end", anchor.end)
            put("anchor_state", anchor.status.name)
        }, "id=?", arrayOf(anchor.note.id.toString()))
    }

    fun deleteProgress(bookId: String) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("progress", "book_id=?", arrayOf(bookId))
            writableDatabase.delete("chapter_progress", "book_id=?", arrayOf(bookId))
            writableDatabase.delete("reading_sessions", "book_id=?", arrayOf(bookId))
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun clearProgress() {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("progress", null, null)
            writableDatabase.delete("chapter_progress", null, null)
            writableDatabase.delete("reading_sessions", null, null)
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun clearBookmarks() { writableDatabase.delete("bookmarks", null, null) }
    fun clearNotes() {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("note_tags", null, null)
            writableDatabase.delete("notes", null, null)
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun startReadingSession(
        bookId: String,
        chapterId: String,
        paragraphIndex: Int,
        now: Long = System.currentTimeMillis()
    ): Long = writableDatabase.insertOrThrow("reading_sessions", null, ContentValues().apply {
        put("book_id", bookId)
        put("started_at", now)
        put("ended_at", now)
        put("active_millis", 0)
        put("start_chapter_id", chapterId)
        put("start_paragraph_index", paragraphIndex)
        put("end_chapter_id", chapterId)
        put("end_paragraph_index", paragraphIndex)
    })

    fun addReadingTime(
        sessionId: Long,
        activeMillis: Long,
        chapterId: String,
        paragraphIndex: Int,
        now: Long = System.currentTimeMillis()
    ) {
        if (activeMillis <= 0) return
        writableDatabase.execSQL(
            """UPDATE reading_sessions SET active_millis=active_millis+?, ended_at=?,
                end_chapter_id=?, end_paragraph_index=? WHERE id=?""".trimIndent(),
            arrayOf(activeMillis.coerceAtMost(60_000), now, chapterId, paragraphIndex, sessionId)
        )
    }

    fun readingSessions(): List<ReadingSession> = readableDatabase.rawQuery(
        """SELECT id, book_id, started_at, ended_at, active_millis, start_chapter_id,
            start_paragraph_index, end_chapter_id, end_paragraph_index
            FROM reading_sessions WHERE active_millis > 0 ORDER BY started_at DESC""".trimIndent(),
        null
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(ReadingSession(
                id = cursor.getLong(0), bookId = cursor.getString(1),
                startedAt = cursor.getLong(2), endedAt = cursor.getLong(3),
                activeMillis = cursor.getLong(4), startChapterId = cursor.getString(5),
                startParagraphIndex = cursor.getInt(6), endChapterId = cursor.getString(7),
                endParagraphIndex = cursor.getInt(8)
            ))
        }
    }

    fun readingStatistics(now: Long = System.currentTimeMillis()): ReadingStatistics =
        calculateReadingStatistics(
            readingSessions(),
            allProgress().values.count { it.completed },
            now
        )

    fun search(
        query: String,
        scope: SearchScope = SearchScope.ALL,
        bookIds: Set<String>? = null,
        limit: Int = 80
    ): List<SearchHit> {
        val match = buildFtsMatchQuery(query, scope) ?: return emptyList()
        if (bookIds != null && bookIds.isEmpty()) return emptyList()
        val filter = bookIds?.joinToString(",", prefix = " AND book_id IN (", postfix = ")") { "?" }.orEmpty()
        val arguments = buildList {
            add(match)
            bookIds?.let { addAll(it) }
            add(limit.toString())
        }.toTypedArray()
        val sql = """SELECT book_id, chapter_id, paragraph_index, title, chapter_title,
            snippet(content_fts, '<b>', '</b>', '...', -1, 24)
            FROM content_fts WHERE content_fts MATCH ?$filter LIMIT ?""".trimIndent()
        return readableDatabase.rawQuery(sql, arguments).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(
                    SearchHit(
                        cursor.getString(0), cursor.getString(1), cursor.getInt(2),
                        cursor.getString(3), cursor.getString(4), cursor.getString(5)
                    )
                )
            }
        }
    }
}

private const val NOTE_COLUMNS = """SELECT id, book_id, chapter_id, paragraph_index,
    excerpt, note_text, updated_at, selection_start, selection_end, paragraph_hash,
    anchor_prefix, anchor_suffix, created_at, anchor_state, note_kind, highlight_color,
    pinned, COALESCE((SELECT group_concat(tag, char(31)) FROM note_tags WHERE note_id=notes.id), '')"""

private fun SQLiteDatabase.addColumnIfMissing(table: String, column: String, definition: String) {
    val exists = rawQuery("PRAGMA table_info($table)", null).use { cursor ->
        val nameIndex = cursor.getColumnIndexOrThrow("name")
        var found = false
        while (cursor.moveToNext()) {
            if (cursor.getString(nameIndex) == column) {
                found = true
                break
            }
        }
        found
    }
    if (!exists) execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
}

private fun readNotes(cursor: android.database.Cursor): List<Note> = buildList {
    while (cursor.moveToNext()) add(
        Note(
            id = cursor.getLong(0), bookId = cursor.getString(1), chapterId = cursor.getString(2),
            paragraphIndex = cursor.getInt(3), excerpt = cursor.getString(4), text = cursor.getString(5),
            updatedAt = cursor.getLong(6), selectionStart = cursor.getInt(7), selectionEnd = cursor.getInt(8),
            paragraphHash = cursor.getString(9), anchorPrefix = cursor.getString(10),
            anchorSuffix = cursor.getString(11), createdAt = cursor.getLong(12), anchorState = cursor.getString(13),
            kind = runCatching { NoteKind.valueOf(cursor.getString(14)) }.getOrDefault(NoteKind.ANNOTATION),
            color = runCatching { HighlightColor.valueOf(cursor.getString(15)) }.getOrDefault(HighlightColor.YELLOW),
            pinned = cursor.getInt(16) != 0,
            tags = cursor.getString(17).split('\u001F').filter { it.isNotBlank() }
        )
    )
}

internal fun buildFtsMatchQuery(query: String, scope: SearchScope = SearchScope.ALL): String? {
    val terms = query.trim().split(Regex("\\s+"))
        .map { it.replace("\"", "").replace("*", "").trim() }
        .filter { it.isNotEmpty() }
    return terms.takeIf { it.isNotEmpty() }?.joinToString(" AND ") { term ->
        val value = "\"${term.take(80)}\"*"
        when (scope) {
            SearchScope.ALL -> value
            SearchScope.TITLES -> "(title:$value OR chapter_title:$value)"
            SearchScope.BODY -> "content:$value"
        }
    }
}
