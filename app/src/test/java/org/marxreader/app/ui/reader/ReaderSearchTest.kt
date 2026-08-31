package org.marxreader.app.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test
import org.marxreader.app.data.*
import org.marxreader.app.ui.PageParagraphRange
import org.marxreader.app.ui.ReaderPage

class ReaderSearchTest {
    private val book = Book(
        id = "b", authorIds = emptyList(), seriesId = null, titleZh = "书", titleEn = "",
        language = Language.ZH, category = "", year = "", yearType = "", yearBasis = "",
        yearEvidenceUrl = "", yearNote = "", sourceUrl = "https://www.marxists.org/a",
        sourceCredit = "", translator = "", translatorBasis = "", translatorEvidenceUrl = "",
        translationYear = "", editionNote = "", rights = RightsStatus.PUBLIC_DOMAIN,
        description = "", toc = emptyList(), chapters = listOf(
            Chapter("c1", "一", 1, listOf("劳动劳动，以及劳动。")),
            Chapter("c2", "二", 1, listOf("这里也有劳动。"))
        )
    )

    @Test fun returnsEveryExactOccurrenceInCurrentChapter() {
        val matches = findReaderMatches(book, "c1", "劳动", ReaderSearchScope.CHAPTER)
        assertEquals(listOf(0, 2, 7), matches.map { it.start })
    }

    @Test fun bookScopeIncludesOtherChapters() {
        val matches = findReaderMatches(book, "c1", "劳动", ReaderSearchScope.BOOK)
        assertEquals(listOf("c1", "c1", "c1", "c2"), matches.map { it.chapterId })
    }

    @Test fun pageHighlightTranslatesSourceOffsetsAcrossPageBoundary() {
        val page = ReaderPage(
            chapterIndex = 0, chapterId = "c1", chapterTitle = "一",
            paragraphIndex = 0, paragraphEndIndex = 0, text = "动，以及劳",
            emphasis = emptyList(), footnotes = emptyList(),
            paragraphRanges = listOf(PageParagraphRange(0, 0, 5, 3))
        )
        val match = ReaderSearchMatch("c1", "一", 0, 7, 9, "劳动")
        assertEquals(PageSearchRange(4, 5, true), page.searchRanges(listOf(match), match).single())
    }
}
