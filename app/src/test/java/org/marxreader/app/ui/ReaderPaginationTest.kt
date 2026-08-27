package org.marxreader.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPaginationTest {
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
}
