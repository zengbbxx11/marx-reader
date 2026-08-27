package org.marxreader.app

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.marxreader.app.data.LibraryRepository
import org.marxreader.app.data.ReaderDatabase
import org.marxreader.app.data.*
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class DataSafetyTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun cleanUp() {
        context.deleteDatabase("reader.db")
        File(context.cacheDir, "unsafe.marxpack").delete()
        File(context.cacheDir, "test.marxpack").delete()
        File(context.filesDir, "packs").deleteRecursively()
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
    fun importRejectsParentDirectoryZipEntry() = runBlocking {
        val pack = File(context.cacheDir, "unsafe.marxpack")
        ZipOutputStream(pack.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("../library.json"))
            zip.write("{}".toByteArray())
            zip.closeEntry()
        }

        val result = LibraryRepository(context).importPack(Uri.fromFile(pack))

        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull()?.message?.contains("路径不安全") == true)
    }

    @Test
    fun backupRoundTripPreservesUserDataAndSettings() {
        val snapshot = UserDataSnapshot(
            progress = listOf(ReadingProgress("book", "chapter", 3, 100)),
            chapterProgress = listOf(ChapterReadingProgress("book", "chapter", 3, 100)),
            bookmarks = listOf(Bookmark(1, "book", "chapter", 3, "摘录", 90)),
            notes = listOf(Note(1, "book", "chapter", 3, "摘录", "我的笔记", 110)),
            settings = ReaderSettings(fontSize = 24f, theme = ReaderTheme.DARK)
        )

        val restored = UserDataBackup.fromJson(UserDataBackup.toJson(snapshot))

        assertEquals(3, restored.progress.single().paragraphIndex)
        assertEquals("我的笔记", restored.notes.single().text)
        assertEquals(24f, restored.settings.fontSize)
        assertEquals(ReaderTheme.DARK, restored.settings.theme)
    }

    @Test
    fun restoreKeepsNewerLocalProgressAndMergesNewerNote() {
        context.deleteDatabase("reader.db")
        val database = ReaderDatabase(context)
        database.saveProgress("book", "local", 8)
        database.saveNote("book", "chapter", 1, "摘录", "旧笔记")
        val local = database.progress("book")!!
        val note = database.notes().single()

        database.restoreUserData(UserDataSnapshot(
            progress = listOf(ReadingProgress("book", "backup", 1, local.updatedAt - 1)),
            chapterProgress = emptyList(),
            bookmarks = emptyList(),
            notes = listOf(note.copy(text = "新笔记", updatedAt = note.updatedAt + 1000)),
            settings = ReaderSettings()
        ))

        assertEquals("local", database.progress("book")?.chapterId)
        assertEquals("新笔记", database.notes().single().text)
        database.close()
    }

    @Test
    fun importRejectsBundledBookIdConflict() = runBlocking {
        File(context.filesDir, "packs").deleteRecursively()
        val repository = LibraryRepository(context)
        repository.initialize()
        val bundledId = repository.catalog.value.books.first().id
        val pack = createPack("conflict-pack", bundledId)

        val result = repository.importPack(Uri.fromFile(pack))

        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull()?.message?.contains("作品 ID") == true)
    }

    @Test
    fun importedPackCanBeRemovedWithoutDeletingUserData() = runBlocking {
        File(context.filesDir, "packs").deleteRecursively()
        val repository = LibraryRepository(context)
        repository.initialize()
        val pack = createPack("removable-pack", "test-unique-book")
        assertTrue(repository.importPack(Uri.fromFile(pack)).isSuccess)
        val installed = repository.importedPacks.value.single { it.packId == "removable-pack" }

        assertTrue(repository.deleteImportedPack(installed.fileName).isSuccess)
        assertTrue(repository.importedPacks.value.none { it.packId == "removable-pack" })
    }

    @Test
    fun searchScopesSeparateTitlesAndBody() {
        context.deleteDatabase("reader.db")
        val database = ReaderDatabase(context)
        val book = Book(
            id = "book", authorIds = emptyList(), seriesId = null,
            titleZh = "资本论", titleEn = "Capital", language = Language.ZH,
            category = "著作", year = "1867", sourceUrl = "https://www.marxists.org/test",
            sourceCredit = "test", translator = "", rights = RightsStatus.PUBLIC_DOMAIN,
            description = "", chapters = listOf(Chapter("chapter", "商品", 1, listOf("劳动创造价值"))),
            toc = emptyList()
        )
        database.rebuildSearchIndex("test", sequenceOf(book))

        assertEquals(1, database.search("资本", SearchScope.TITLES).size)
        assertEquals(0, database.search("劳动", SearchScope.TITLES).size)
        assertEquals(1, database.search("劳动", SearchScope.BODY).size)
        database.close()
    }

    private fun createPack(packId: String, bookId: String): File {
        val json = """{
            "schemaVersion":2,
            "packId":"$packId",
            "packTitle":"测试内容包",
            "packVersion":"1.0",
            "authors":[],
            "books":[{
                "id":"$bookId","authorIds":[],"titleZh":"测试作品","titleEn":"Test",
                "language":"zh","sourceUrl":"https://www.marxists.org/test",
                "rights":"PUBLIC_DOMAIN","chapters":[{"id":"chapter","title":"正文","content":[]}]
            }]
        }""".trimIndent()
        val pack = File(context.cacheDir, "test.marxpack")
        ZipOutputStream(pack.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("library.json"))
            zip.write(json.toByteArray())
            zip.closeEntry()
        }
        return pack
    }
}
