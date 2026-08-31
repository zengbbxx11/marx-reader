package org.marxreader.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.marxreader.app.data.ReaderDatabase

@RunWith(AndroidJUnit4::class)
class ReaderDatabaseMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun prepareV3Database() {
        context.deleteDatabase("reader.db")
        val path = context.getDatabasePath("reader.db")
        path.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { database ->
            database.execSQL("""
                CREATE TABLE progress(
                    book_id TEXT PRIMARY KEY,
                    chapter_id TEXT NOT NULL,
                    paragraph_index INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL
                )
            """.trimIndent())
            database.execSQL("""
                CREATE TABLE chapter_progress(
                    book_id TEXT NOT NULL,
                    chapter_id TEXT NOT NULL,
                    paragraph_index INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY(book_id, chapter_id)
                )
            """.trimIndent())
            database.execSQL(
                "INSERT INTO progress(book_id, chapter_id, paragraph_index, updated_at) VALUES(?, ?, ?, ?)",
                arrayOf("book", "chapter", 7, 1234L)
            )
            database.execSQL("""
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
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
            """.trimIndent())
            database.execSQL(
                """INSERT INTO notes(book_id, chapter_id, paragraph_index, excerpt, note_text,
                    selection_start, selection_end, created_at, updated_at)
                    VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
                arrayOf("book", "chapter", 7, "摘录", "旧笔记", 0, 2, 1000L, 1234L)
            )
            database.version = 3
        }
    }

    @After
    fun cleanUp() {
        context.deleteDatabase("reader.db")
    }

    @Test
    fun v3ProgressIsPreservedAndReceivesPrecisePositionColumns() {
        val database = ReaderDatabase(context)
        val progress = database.progress("book")

        assertEquals("chapter", progress?.chapterId)
        assertEquals(7, progress?.paragraphIndex)
        assertEquals(0, progress?.characterOffset)
        assertFalse(progress?.completed ?: true)
        val migratedNote = database.notes().single()
        assertEquals("ANNOTATION", migratedNote.kind.name)
        assertEquals("YELLOW", migratedNote.color.name)
        assertFalse(migratedNote.pinned)
        val sessionId = database.startReadingSession("book", "chapter", 7, 2000L)
        database.addReadingTime(sessionId, 5000L, "chapter", 8, 7000L)
        assertTrue(database.readingSessions().single().activeMillis == 5000L)
        database.close()
    }
}
