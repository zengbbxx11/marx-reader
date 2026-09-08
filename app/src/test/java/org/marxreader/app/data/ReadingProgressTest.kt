package org.marxreader.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingProgressTest {
    @Test
    fun cachedOffsetsMatchReferenceForEveryCharacterAndEmptyChapters() {
        val chapters = listOf(
            Chapter("empty", "空章", 1, emptyList()),
            Chapter("one", "一", 1, listOf("abcd", "", "123456789")),
            Chapter("two", "二", 1, listOf("正文测试", "abc"))
        )
        val book = bookOf(*chapters.toTypedArray())
        chapters.forEachIndexed { chapterIndex, chapter ->
            chapter.paragraphs.forEachIndexed { paragraph, text ->
                (0..text.length).forEach { offset ->
                    val expected = chapters.take(chapterIndex).sumOf { it.paragraphs.sumOf(String::length) } +
                        chapter.paragraphs.take(paragraph).sumOf(String::length) + offset
                    assertEquals(expected.toLong(), book.readingProgress(
                        ReaderPosition(book.id, chapter.id, paragraph, updatedAt = 0L, characterOffset = offset)
                    ).absoluteCharacterOffset)
                }
            }
        }
    }

    @Test
    fun loadingBodyAndCopyingBookDoNotReuseStaleMetadataCounts() {
        val metadata = Chapter("chapter", "章节", 1, emptyList(), declaredParagraphCharacterCounts = listOf(100))
        val original = bookOf(metadata)
        assertEquals(100, original.characterCount)
        val loaded = original.copy(chapters = listOf(metadata.copy(paragraphs = listOf("abc", "defgh"))))
        assertEquals(8, loaded.characterCount)
        assertEquals(5, loaded.chapters.first().characterOffset(1, 2))
        assertEquals(100, original.characterCount)
    }

    @Test
    fun progressIsWeightedByActualCharacterCounts() {
        val book = bookOf(
            Chapter("short", "短章", 1, listOf("1234567890")),
            Chapter("long", "长章", 1, listOf("a".repeat(90)))
        )

        val value = book.readingProgress(
            ReaderPosition(book.id, "long", 0, updatedAt = 1L, characterOffset = 40)
        )

        assertEquals(50L, value.absoluteCharacterOffset)
        assertEquals(100L, value.totalCharacterCount)
        assertEquals(50f, value.percent, 0.001f)
        assertFalse(value.completed)
    }

    @Test
    fun catalogMetadataCanCalculateProgressWithoutLoadingBody() {
        val summaryChapter = Chapter(
            id = "chapter",
            title = "章节",
            level = 1,
            paragraphs = emptyList(),
            declaredParagraphCount = 3,
            declaredParagraphCharacterCounts = listOf(10, 20, 30)
        )
        val book = bookOf(summaryChapter)

        val value = book.readingProgress(
            ReaderPosition(book.id, "chapter", 1, updatedAt = 1L, characterOffset = 5)
        )

        assertEquals(15L, value.absoluteCharacterOffset)
        assertEquals(25f, value.percent, 0.001f)
    }

    @Test
    fun completionIsExplicitAndNeverReportedEarly() {
        val book = bookOf(Chapter("chapter", "章节", 1, listOf("1234567890")))

        val almostDone = book.readingProgress(
            ReaderPosition(book.id, "chapter", 0, updatedAt = 1L, characterOffset = 9)
        )
        val done = book.readingProgress(
            ReaderPosition(book.id, "chapter", 0, updatedAt = 2L, characterOffset = 10, completed = true)
        )

        assertEquals(90f, almostDone.percent, 0.001f)
        assertFalse(almostDone.completed)
        assertEquals("100%", done.displayPercent)
        assertTrue(done.completed)
    }

    private fun bookOf(vararg chapters: Chapter) = Book(
        id = "book",
        authorIds = listOf("author"),
        seriesId = null,
        titleZh = "作品",
        titleEn = "Book",
        language = Language.ZH,
        category = "著作",
        year = "",
        yearType = "",
        yearBasis = "",
        yearEvidenceUrl = "",
        yearNote = "",
        sourceUrl = "https://www.marxists.org/",
        sourceCredit = "MIA",
        translator = "",
        translatorBasis = "",
        translatorEvidenceUrl = "",
        translationYear = "",
        editionNote = "",
        rights = RightsStatus.PUBLIC_DOMAIN,
        description = "",
        chapters = chapters.toList(),
        toc = emptyList()
    )
}
