package org.marxreader.app

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.marxreader.app.data.Book
import org.marxreader.app.data.Chapter
import org.marxreader.app.data.Language
import org.marxreader.app.data.LibraryRepository
import org.marxreader.app.data.ReaderDatabase
import org.marxreader.app.data.RightsStatus
import org.marxreader.app.data.SearchScope

@RunWith(AndroidJUnit4::class)
class DataSafetyTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun cleanUp() {
        context.deleteDatabase("reader.db")
    }

    @Test
    fun progressWriteRollsBackBothTablesWhenChapterWriteFails() {
        context.deleteDatabase("reader.db")
        ReaderDatabase(context).use { database ->
            database.saveProgress("book", "chapter", 2, 3)
            database.writableDatabase.execSQL("""
                CREATE TRIGGER fail_chapter_progress BEFORE INSERT ON chapter_progress
                BEGIN SELECT RAISE(ABORT, 'simulated write failure'); END
            """.trimIndent())
            val failed = runCatching { database.saveProgress("book", "chapter", 9, 15) }.isFailure
            assertTrue(failed)
            assertEquals(2, database.progress("book")?.paragraphIndex)
            assertEquals(3, database.progress("book")?.characterOffset)
            assertEquals(2, database.chapterProgress("book")["chapter"])
        }
    }

    @Test
    fun bookmarkToggleReportsTheActualStoredState() = runBlocking {
        context.deleteDatabase("reader.db")
        val repository = LibraryRepository(context)
        assertTrue(repository.toggleBookmark("book", "chapter", 2, "摘录"))
        assertEquals(1, repository.bookmarks().size)
        assertEquals(false, repository.toggleBookmark("book", "chapter", 2, "摘录"))
        assertTrue(repository.bookmarks().isEmpty())
    }

    @Test
    fun versionOneDatabaseCreatesChapterProgressDuringUpgrade() {
        context.deleteDatabase("reader.db")
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("reader.db"), null).use {
            it.version = 1
        }

        val database = ReaderDatabase(context)
        val exists = database.readableDatabase.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='chapter_progress'",
            null
        ).use { it.moveToFirst() }
        database.close()

        assertTrue(exists)
    }

    @Test
    fun repositoryLoadsOnlyAuditedBundledSources() = runBlocking {
        val repository = LibraryRepository(context)
        repository.initialize()

        assertTrue(repository.catalog.value.books.isNotEmpty())
        assertTrue(repository.catalog.value.books.all { it.sourceUrl.startsWith("https://www.marxists.org/") })
    }

    @Test
    fun searchScopesSeparateTitlesAndBody() {
        context.deleteDatabase("reader.db")
        val database = ReaderDatabase(context)
        val book = Book(
            id = "book", authorIds = emptyList(), seriesId = null,
            titleZh = "资本论", titleEn = "Capital", language = Language.ZH,
            category = "著作", year = "1867", yearType = "", yearBasis = "",
            yearEvidenceUrl = "", yearNote = "", sourceUrl = "https://www.marxists.org/test",
            sourceCredit = "test", translator = "", translatorBasis = "",
            translatorEvidenceUrl = "", translationYear = "", editionNote = "",
            rights = RightsStatus.PUBLIC_DOMAIN,
            description = "", chapters = listOf(Chapter("chapter", "商品", 1, listOf("劳动创造价值"))),
            toc = emptyList()
        )
        database.rebuildSearchIndex("test", sequenceOf(book))

        assertEquals(1, database.search("资本", SearchScope.TITLES).size)
        assertEquals(0, database.search("劳动", SearchScope.TITLES).size)
        assertEquals(1, database.search("劳动", SearchScope.BODY).size)
        database.close()
    }
}
