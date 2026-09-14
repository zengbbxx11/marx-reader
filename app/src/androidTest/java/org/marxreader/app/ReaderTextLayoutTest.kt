package org.marxreader.app

import android.view.View
import android.widget.TextView
import android.view.MotionEvent
import android.os.SystemClock
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.marxreader.app.data.*
import org.marxreader.app.ui.*
import org.marxreader.app.ui.reader.ParagraphLineMap
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class ReaderTextLayoutTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun manifestoPagesFitAndEveryFootnoteReferenceRemainsReachable() {
        val book = runBlocking { LibraryRepository(context).loadBook("marx-work-6523f0586337") }!!
        var testedReferences = 0
        instrumentation.runOnMainSync {
            for (size in listOf(20f, 48f, 72f)) for (font in ReaderFont.entries) {
                book.chapters.indices.forEach { chapter ->
                    val pages = paginateChapter(book, chapter, 720, 1000, size, 1.75f, .72f,
                        font, ReaderFontWeight.REGULAR, true)
                    pages.forEach { page ->
                        val view = ReaderSelectableTextView(context).apply { lockPageScroll = true }
                        view.applyReaderTextStyle(size, size * 1.75f, font,
                            ReaderFontWeight.REGULAR, false, android.graphics.Color.BLACK)
                        view.setText(page.selectionOverlayText(size, Color.Red), TextView.BufferType.SPANNABLE)
                        view.interactiveRanges = page.footnotes.map { ReaderInteractiveRange(it.start, it.end) }
                        view.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
                        view.layout(0, 0, 720, 1000)
                        assertTrue("${page.chapterId} font=$size/$font height=${view.layout.height}", view.layout.height <= 1000)
                        view.scrollTo(0, 100)
                        assertEquals(0, view.scrollY)
                        page.footnotes.forEach { reference ->
                            val line = view.layout.getLineForOffset(reference.start)
                            val x = view.layout.getPrimaryHorizontal(reference.start) + 1f
                            val y = (view.layout.getLineTop(line) + view.layout.getLineBottom(line)) / 2f
                            assertEquals(reference.start, view.interactiveOffsetAt(x, y))
                            testedReferences++
                        }
                    }
                }
            }
        }
        assertTrue(testedReferences > 0)
    }

    @Test fun tappingBesideASmallMarkerUsesNoteInsteadOfOrdinaryTextOffset() {
        instrumentation.runOnMainSync {
            val view = ReaderSelectableTextView(context).apply {
                // A parent supplies this in production; native selection requires it.
                layoutParams = android.view.ViewGroup.LayoutParams(720, 200)
                lockPageScroll = true
                text = "正文正文正文正文[1]正文正文正文正文"
                interactiveRanges = listOf(ReaderInteractiveRange(8, 11))
            }
            view.applyReaderTextStyle(24f, 42f, ReaderFont.SERIF, ReaderFontWeight.REGULAR,
                false, android.graphics.Color.BLACK)
            view.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 720, 200)
            val x = view.layout.getPrimaryHorizontal(11) + 2f
            val y = view.layout.getLineBottom(0) / 2f
            var tappedOffset = -1
            view.onTextTap = { offset, _, _ -> tappedOffset = offset }
            val time = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0)
            val up = MotionEvent.obtain(time, time + 50, MotionEvent.ACTION_UP, x + 1f, y, 0)
            view.onTouchEvent(down)
            view.onTouchEvent(up)
            down.recycle()
            up.recycle()
            assertEquals(8, tappedOffset)
            assertEquals(0, view.scrollY)
        }
    }

    @Test fun renderedPagesFitTheSameHeightUsedForPagination() {
        val template = context.assets.open("library/catalog.json").bufferedReader().use {
            parseCatalog(it.readText()).books.first()
        }
        val book = template.copy(
            chapters = listOf(Chapter("layout", "阅读排版测试", 1,
                List(20) { "这是用于检验中文正文分页和段落间距的一段长文本。".repeat(8) })),
            toc = emptyList()
        )
        instrumentation.runOnMainSync {
            for (size in listOf(20f, 32f, 48f)) for (spacing in listOf(.42f, 1f)) {
                val pages = paginateChapter(book, 0, 360, 640, size, 1.75f, spacing,
                    ReaderFont.SERIF, ReaderFontWeight.REGULAR, true)
                assertTrue(pages.size > 1)
                pages.forEachIndexed { index, page ->
                    val view = ReaderSelectableTextView(context)
                    view.applyReaderTextStyle(size, size * 1.75f, ReaderFont.SERIF,
                        ReaderFontWeight.REGULAR, false, android.graphics.Color.BLACK)
                    view.setText(page.selectionOverlayText(size, Color.Red), TextView.BufferType.SPANNABLE)
                    view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    view.layout(0, 0, 360, view.measuredHeight)
                    assertTrue("page=$index font=$size spacing=$spacing height=${view.layout.height}",
                        view.layout.height <= 640)
                }
            }
        }
    }

    @Test fun nativeLineMapKeepsTheSelectedSourceVisibleAfterFontChange() {
        instrumentation.runOnMainSync {
            val source = "长段落的阅读位置应当能够在字号变化后恢复。".repeat(80)
            val view = ReaderSelectableTextView(context)
            view.text = "　　$source"
            fun lines(fontSize: Float): ParagraphLineMap {
                view.applyReaderTextStyle(fontSize, fontSize * 1.75f, ReaderFont.SERIF,
                    ReaderFontWeight.REGULAR, false, android.graphics.Color.BLACK)
                view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                view.layout(0, 0, 360, view.measuredHeight)
                return ParagraphLineMap(List(view.layout.lineCount) { view.layout.getLineStart(it) },
                    List(view.layout.lineCount) { view.layout.getLineTop(it) }, 2, source.length)
            }
            val original = lines(20f)
            val savedCharacter = original.sourceAt(600)
            assertTrue(savedCharacter > 0)
            val enlarged = lines(32f)
            val top = enlarged.topFor(savedCharacter)
            assertEquals(view.layout.getLineTop(view.layout.getLineForOffset(savedCharacter + 2)), top)
            assertTrue(enlarged.sourceAt(top) <= savedCharacter)
        }
    }
}
