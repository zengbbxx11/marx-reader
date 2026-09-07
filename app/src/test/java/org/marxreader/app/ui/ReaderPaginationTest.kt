package org.marxreader.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.marxreader.app.data.*

class ReaderPaginationTest {
    @Test
    fun noteAcrossPagesDecoratesBothPagesWithoutChangingSourcePositions() {
        val original = listOf(
            page("a", 3, 3).copy(text = "abcdefghij", paragraphRanges = listOf(PageParagraphRange(3, 0, 10, 0))),
            page("a", 3, 3).copy(text = "klmnopqrst", paragraphRanges = listOf(PageParagraphRange(3, 0, 10, 10))),
            page("b", 3, 3).copy(text = "abcdefghij", paragraphRanges = listOf(PageParagraphRange(3, 0, 10, 0)))
        )
        val note = noteForSelection("book", "a", 3, "abcdefghijklmnopqrst",
            TextSelection(7, 14, "hijklmn"), "跨页批注", id = 42)
        val decorated = original.withNoteAnchors(listOf(ResolvedNoteAnchor(note, 3, 7, 14, NoteAnchorStatus.EXACT)))

        assertEquals(listOf(7 to 10), decorated[0].notes.map { it.start to it.end })
        assertEquals(listOf(0 to 4), decorated[1].notes.map { it.start to it.end })
        assertTrue(decorated[2].notes.isEmpty())
        assertEquals(original.map { it.paragraphRanges }, decorated.map { it.paragraphRanges })
        assertEquals(original.map { it.text }, decorated.map { it.text })
        assertEquals(original.pageFor("a", 3, 12), decorated.pageFor("a", 3, 12))
        assertTrue(original.all { it.notes.isEmpty() })
        assertTrue(decorated.withNoteAnchors(emptyList()).all { it.notes.isEmpty() })
    }

    private fun page(chapter: String, start: Int, end: Int) = ReaderPage(
        chapterIndex = if (chapter == "a") 0 else 1,
        chapterId = chapter,
        chapterTitle = chapter,
        paragraphIndex = start,
        paragraphEndIndex = end,
        text = "$chapter-$start-$end",
        emphasis = emptyList(),
        footnotes = emptyList()
    )

    @Test
    fun pageForFindsContainingParagraphAndFallsBackToChapterStart() {
        val pages = listOf(page("a", 0, 2), page("a", 3, 5), page("b", 0, 4))

        assertEquals(1, pages.pageFor("a", 4))
        assertEquals(2, pages.pageFor("b", 99))
        assertEquals(0, pages.pageFor("missing", 0))
    }

    @Test
    fun indentationSkipsHeadingsListsAndShortLines() {
        assertTrue(shouldIndentParagraph("这是一个长度足够并且应该按照普通正文进行首行缩进的测试段落。"))
        assertFalse(shouldIndentParagraph("第一章 商品"))
        assertFalse(shouldIndentParagraph("① 一个列表项目，其内容即使比较长也不应该进行首行缩进。"))
        assertFalse(shouldIndentParagraph("短句"))
    }

    @Test
    fun pageOffsetMapsToTheActualParagraphAndSourceOffset() {
        val target = page("a", 3, 4).copy(
            paragraphRanges = listOf(
                PageParagraphRange(paragraphIndex = 3, start = 0, end = 10, sourceStart = 4),
                PageParagraphRange(paragraphIndex = 4, start = 12, end = 24, sourceStart = 0)
            )
        )

        assertEquals(3 to 9, target.sourcePositionAt(5))
        assertEquals(4 to 3, target.sourcePositionAt(15))
        assertEquals(null, target.sourcePositionAt(11))
    }

    @Test
    fun sourceSelectionMapsExactRangeWithinOneParagraph() {
        val target = page("a", 3, 4).copy(
            text = "abcdefghijklmnopqrstuvwx",
            paragraphRanges = listOf(
                PageParagraphRange(paragraphIndex = 3, start = 0, end = 10, sourceStart = 4),
                PageParagraphRange(paragraphIndex = 4, start = 12, end = 24, sourceStart = 0)
            )
        )

        assertEquals(PageSourceSelection(3, 6, 12), target.sourceSelection(8, 2))
        assertEquals(PageSourceSelection(4, 2, 8), target.sourceSelection(14, 20))
    }

    @Test
    fun sourceSelectionRejectsParagraphSpanningRange() {
        val target = page("a", 3, 4).copy(
            text = "abcdefghijklmnopqrstuvwx",
            paragraphRanges = listOf(
                PageParagraphRange(paragraphIndex = 3, start = 0, end = 10, sourceStart = 4),
                PageParagraphRange(paragraphIndex = 4, start = 12, end = 24, sourceStart = 0)
            )
        )

        assertEquals(null, target.sourceSelection(8, 14))
    }

    @Test
    fun pageLookupUsesTheCharacterOffsetWhenAParagraphSpansPages() {
        val first = page("a", 3, 3).copy(
            paragraphRanges = listOf(PageParagraphRange(3, 0, 10, 0))
        )
        val second = page("a", 3, 3).copy(
            paragraphRanges = listOf(PageParagraphRange(3, 0, 10, 10))
        )

        assertEquals(0, listOf(first, second).pageFor("a", 3, 4))
        assertEquals(1, listOf(first, second).pageFor("a", 3, 14))
        assertEquals(3 to 10, second.firstSourcePosition())
    }

    @Test
    fun pageRangesKeepTheVisiblePartWhenAReferenceTouchesPageBoundary() {
        assertEquals(2 to 5, clipTextRangeToPage(8, 15, 6, 11))
        assertEquals(null, clipTextRangeToPage(2, 6, 6, 10))
    }

    @Test
    fun textTapAtTheExclusiveEndStillHitsTheLastCharacter() {
        assertTrue(textOffsetHitsRange(5, 2, 5))
        assertTrue(textOffsetHitsRange(3, 2, 5))
        assertFalse(textOffsetHitsRange(6, 2, 5))
        assertFalse(textOffsetHitsRange(5, 5, 5))
    }
}
