package org.marxreader.app.ui.reader

import org.junit.Assert.*
import org.junit.Test

class ReaderContinuityTest {
    @Test fun longParagraphResumesAtVisibleLineInsteadOfParagraphStart() {
        val lines = ParagraphLineMap(listOf(0, 12, 25, 38), listOf(0, 30, 60, 90), 2, 50)
        assertEquals(23, lines.sourceAt(67))
        assertEquals(60, lines.topFor(23))
        assertEquals(60, lines.topFor(30))
        assertEquals(0, lines.sourceAt(-20))
        assertEquals(90, lines.topFor(500))
    }

    @Test fun changingFontKeepsSourceCharacterOnTheNewVisibleLine() {
        val old = ParagraphLineMap(listOf(0, 10, 20, 30), listOf(0, 20, 40, 60), 0, 40)
        val larger = ParagraphLineMap(listOf(0, 7, 14, 21, 28, 35), listOf(0, 30, 60, 90, 120, 150), 0, 40)
        val source = old.sourceAt(62)
        assertEquals(30, source)
        assertEquals(120, larger.topFor(source))
        assertTrue(larger.sourceAt(larger.topFor(source)) <= source)
    }

    @Test fun emptyParagraphHasSafeOrigin() {
        val lines = ParagraphLineMap(emptyList(), emptyList(), 0, 0)
        assertEquals(0, lines.sourceAt(10))
        assertEquals(0, lines.topFor(10))
    }

    @Test fun middleChapterSupportsBothSwipeBoundariesWithoutCountingThemAsPages() {
        val window = ChapterPagerWindow(3, true, true)
        assertEquals(5, window.slotCount)
        assertEquals(-1, window.chapterDelta(0))
        assertEquals(1, window.chapterDelta(4))
        assertEquals(listOf(0, 1, 2), (1..3).map(window::contentPage))
        assertEquals(listOf(0, 0, 0), (1..3).map(window::chapterDelta))
    }

    @Test fun bookBoundariesAndSinglePageChaptersNeverCreateInvalidChapterTargets() {
        val first = ChapterPagerWindow(1, false, true)
        assertEquals(0, first.chapterDelta(0))
        assertEquals(1, first.chapterDelta(1))
        val last = ChapterPagerWindow(1, true, false)
        assertEquals(-1, last.chapterDelta(0))
        assertEquals(0, last.chapterDelta(1))
        assertEquals(1, ChapterPagerWindow(1, false, false).slotCount)
        assertEquals(1, ChapterPagerWindow(0, false, false).slotCount)
    }

    @Test fun recentlyUsedChapterSurvivesCacheEviction() {
        val cache = WeightedReaderCache<String, String>(2, 100)
        cache.put("a", "first", 20)
        cache.put("b", "second", 20)
        assertEquals("first", cache["a"])
        cache.put("c", "third", 20)
        assertNull(cache["b"])
        assertEquals("first", cache["a"])
        assertEquals("third", cache["c"])
    }

    @Test fun memoryBudgetEvictsByWeightAndSkipsOversizedChapters() {
        val cache = WeightedReaderCache<String, String>(3, 50)
        cache.put("a", "first", 30)
        cache.put("b", "second", 30)
        assertNull(cache["a"])
        cache.put("huge", "huge", 100)
        assertNull(cache["huge"])
        assertEquals("second", cache["b"])
        cache.put("b", "replacement", 10)
        cache.put("c", "third", 40)
        assertEquals("replacement", cache["b"])
        assertEquals("third", cache["c"])
    }
}
