package org.marxreader.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class ReaderDatabase(context: Context) :
    SQLiteOpenHelper(context, "reader.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE progress(
                book_id TEXT PRIMARY KEY,
                chapter_id TEXT NOT NULL,
                paragraph_index INTEGER NOT NULL DEFAULT 0,
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
                updated_at INTEGER NOT NULL,
                UNIQUE(book_id, chapter_id, paragraph_index)
            )
        """.trimIndent())
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
    }

    private fun createChapterProgress(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS chapter_progress(
                book_id TEXT NOT NULL,
                chapter_id TEXT NOT NULL,
                paragraph_index INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(book_id, chapter_id)
            )
        """.trimIndent())
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
                        writableDatabase.insert("content_fts", null, ContentValues().apply {
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

    fun saveProgress(bookId: String, chapterId: String, paragraphIndex: Int) {
        val now = System.currentTimeMillis()
        writableDatabase.insertWithOnConflict("progress", null, ContentValues().apply {
            put("book_id", bookId)
            put("chapter_id", chapterId)
            put("paragraph_index", paragraphIndex)
            put("updated_at", now)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        writableDatabase.insertWithOnConflict("chapter_progress", null, ContentValues().apply {
            put("book_id", bookId)
            put("chapter_id", chapterId)
            put("paragraph_index", paragraphIndex)
            put("updated_at", now)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun progress(bookId: String): ReadingProgress? = readableDatabase.rawQuery(
        "SELECT chapter_id, paragraph_index, updated_at FROM progress WHERE book_id=?",
        arrayOf(bookId)
    ).use {
        if (!it.moveToFirst()) null
        else ReadingProgress(bookId, it.getString(0), it.getInt(1), it.getLong(2))
    }

    fun allProgress(): Map<String, ReadingProgress> = readableDatabase.rawQuery(
        "SELECT book_id, chapter_id, paragraph_index, updated_at FROM progress", null
    ).use { cursor ->
        buildMap {
            while (cursor.moveToNext()) {
                val item = ReadingProgress(
                    cursor.getString(0), cursor.getString(1), cursor.getInt(2), cursor.getLong(3)
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

    fun allChapterProgress(): List<ChapterReadingProgress> = readableDatabase.rawQuery(
        "SELECT book_id, chapter_id, paragraph_index, updated_at FROM chapter_progress", null
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(
                ChapterReadingProgress(
                    cursor.getString(0), cursor.getString(1), cursor.getInt(2), cursor.getLong(3)
                )
            )
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
        writableDatabase.insert("bookmarks", null, ContentValues().apply {
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

    fun saveNote(bookId: String, chapterId: String, paragraphIndex: Int, excerpt: String, text: String) {
        if (text.isBlank()) {
            writableDatabase.delete(
                "notes", "book_id=? AND chapter_id=? AND paragraph_index=?",
                arrayOf(bookId, chapterId, paragraphIndex.toString())
            )
            return
        }
        writableDatabase.insertWithOnConflict("notes", null, ContentValues().apply {
            put("book_id", bookId)
            put("chapter_id", chapterId)
            put("paragraph_index", paragraphIndex)
            put("excerpt", excerpt.take(180))
            put("note_text", text.trim())
            put("updated_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun notes(): List<Note> = readableDatabase.rawQuery(
        "SELECT id, book_id, chapter_id, paragraph_index, excerpt, note_text, updated_at FROM notes ORDER BY updated_at DESC",
        null
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(
                Note(cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getInt(3),
                    cursor.getString(4), cursor.getString(5), cursor.getLong(6))
            )
        }
    }

    fun updateNote(id: Long, text: String) {
        if (text.isBlank()) return deleteNote(id)
        writableDatabase.update("notes", ContentValues().apply {
            put("note_text", text.trim())
            put("updated_at", System.currentTimeMillis())
        }, "id=?", arrayOf(id.toString()))
    }

    fun deleteNote(id: Long) {
        writableDatabase.delete("notes", "id=?", arrayOf(id.toString()))
    }

    fun deleteProgress(bookId: String) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("progress", "book_id=?", arrayOf(bookId))
            writableDatabase.delete("chapter_progress", "book_id=?", arrayOf(bookId))
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
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun clearBookmarks() { writableDatabase.delete("bookmarks", null, null) }
    fun clearNotes() { writableDatabase.delete("notes", null, null) }

    fun restoreUserData(snapshot: UserDataSnapshot) {
        writableDatabase.beginTransaction()
        try {
            snapshot.progress.forEach { item ->
                val existing = progress(item.bookId)
                if (existing == null || item.updatedAt >= existing.updatedAt) {
                    writableDatabase.insertWithOnConflict("progress", null, ContentValues().apply {
                        put("book_id", item.bookId)
                        put("chapter_id", item.chapterId)
                        put("paragraph_index", item.paragraphIndex)
                        put("updated_at", item.updatedAt)
                    }, SQLiteDatabase.CONFLICT_REPLACE)
                }
            }
            snapshot.chapterProgress.forEach { item ->
                val currentUpdatedAt = readableDatabase.rawQuery(
                    "SELECT updated_at FROM chapter_progress WHERE book_id=? AND chapter_id=?",
                    arrayOf(item.bookId, item.chapterId)
                ).use { if (it.moveToFirst()) it.getLong(0) else null }
                if (currentUpdatedAt == null || item.updatedAt >= currentUpdatedAt) {
                    writableDatabase.insertWithOnConflict("chapter_progress", null, ContentValues().apply {
                        put("book_id", item.bookId)
                        put("chapter_id", item.chapterId)
                        put("paragraph_index", item.paragraphIndex)
                        put("updated_at", item.updatedAt)
                    }, SQLiteDatabase.CONFLICT_REPLACE)
                }
            }
            snapshot.bookmarks.forEach { item ->
                writableDatabase.insertWithOnConflict("bookmarks", null, ContentValues().apply {
                    put("book_id", item.bookId)
                    put("chapter_id", item.chapterId)
                    put("paragraph_index", item.paragraphIndex)
                    put("excerpt", item.excerpt.take(180))
                    put("created_at", item.createdAt)
                }, SQLiteDatabase.CONFLICT_IGNORE)
            }
            snapshot.notes.forEach { item ->
                val currentUpdatedAt = readableDatabase.rawQuery(
                    "SELECT updated_at FROM notes WHERE book_id=? AND chapter_id=? AND paragraph_index=?",
                    arrayOf(item.bookId, item.chapterId, item.paragraphIndex.toString())
                ).use { if (it.moveToFirst()) it.getLong(0) else null }
                if (currentUpdatedAt == null || item.updatedAt >= currentUpdatedAt) {
                    writableDatabase.insertWithOnConflict("notes", null, ContentValues().apply {
                        put("book_id", item.bookId)
                        put("chapter_id", item.chapterId)
                        put("paragraph_index", item.paragraphIndex)
                        put("excerpt", item.excerpt.take(180))
                        put("note_text", item.text.trim())
                        put("updated_at", item.updatedAt)
                    }, SQLiteDatabase.CONFLICT_REPLACE)
                }
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

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
