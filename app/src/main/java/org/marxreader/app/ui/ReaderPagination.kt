package org.marxreader.app.ui

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.SuperscriptSpan
import org.marxreader.app.data.Book
import org.marxreader.app.data.Footnote
import org.marxreader.app.data.Note
import org.marxreader.app.data.ResolvedNoteAnchor
import org.marxreader.app.data.ReaderFont
import org.marxreader.app.data.ReaderFontWeight
import org.marxreader.app.data.TocNodeType
import kotlin.math.max

data class PageEmphasis(
    val start: Int,
    val end: Int,
    val level: Int
)

data class PageFootnote(
    val start: Int,
    val end: Int,
    val footnote: Footnote
)

data class PageNote(val start: Int, val end: Int, val note: Note)
data class PageSpacing(val start: Int, val end: Int, val multiplier: Float)

data class PageParagraphRange(
    val paragraphIndex: Int,
    val start: Int,
    val end: Int,
    val sourceStart: Int
)

data class ReaderPage(
    val chapterIndex: Int,
    val chapterId: String,
    val chapterTitle: String,
    val paragraphIndex: Int,
    val paragraphEndIndex: Int,
    val text: String,
    val emphasis: List<PageEmphasis>,
    val footnotes: List<PageFootnote>,
    val notes: List<PageNote> = emptyList(),
    val paragraphRanges: List<PageParagraphRange> = emptyList(),
    val spacing: List<PageSpacing> = emptyList()
)

data class PageSourceSelection(
    val paragraphIndex: Int,
    val start: Int,
    val end: Int
)

/** Annotations decorate an existing layout; they never change page boundaries. */
internal fun List<ReaderPage>.withNoteAnchors(anchors: List<ResolvedNoteAnchor>): List<ReaderPage> {
    val byLocation = anchors.groupBy { it.note.chapterId to it.paragraphIndex }
    return map { page ->
        val notes = page.paragraphRanges.flatMap { range ->
            byLocation[page.chapterId to range.paragraphIndex].orEmpty().mapNotNull { anchor ->
                val start = maxOf(anchor.start, range.sourceStart)
                val end = minOf(anchor.end, range.sourceStart + range.end - range.start)
                if (start < end) PageNote(
                    range.start + start - range.sourceStart,
                    range.start + end - range.sourceStart,
                    anchor.note
                ) else null
            }
        }
        page.copy(notes = notes)
    }
}

fun ReaderPage.sourceSelection(pageStart: Int, pageEnd: Int): PageSourceSelection? {
    val start = minOf(pageStart, pageEnd).coerceIn(0, text.length)
    val end = maxOf(pageStart, pageEnd).coerceIn(0, text.length)
    if (end <= start) return null

    val startRange = paragraphRanges.firstOrNull { start in it.start until it.end } ?: return null
    val endRange = paragraphRanges.firstOrNull { (end - 1) in it.start until it.end } ?: return null
    if (startRange.paragraphIndex != endRange.paragraphIndex) return null

    val sourceStart = startRange.sourceStart + start - startRange.start
    val sourceEnd = endRange.sourceStart + end - endRange.start
    return if (sourceEnd > sourceStart) {
        PageSourceSelection(startRange.paragraphIndex, sourceStart, sourceEnd)
    } else null
}

internal fun clipTextRangeToPage(
    start: Int,
    end: Int,
    pageStart: Int,
    pageEnd: Int
): Pair<Int, Int>? {
    val overlapStart = maxOf(start, pageStart)
    val overlapEnd = minOf(end, pageEnd)
    return if (overlapStart < overlapEnd) {
        overlapStart - pageStart to overlapEnd - pageStart
    } else null
}

internal fun textOffsetHitsRange(offset: Int, start: Int, end: Int): Boolean =
    start < end && (offset in start until end || offset == end)

private data class TextRange(val start: Int, val end: Int, val level: Int)
private data class FootnoteRange(val start: Int, val end: Int, val footnote: Footnote)
private data class NoteRange(val start: Int, val end: Int, val note: Note)
private data class ParagraphRange(val paragraphIndex: Int, val start: Int, val end: Int)
private data class SpacingRange(val start: Int, val end: Int)

/**
 * Paginates using Android's actual text layout engine. Page boundaries therefore
 * follow the selected type size, line height and real screen dimensions rather
 * than a fixed character estimate.
 */
fun paginateBook(
    book: Book,
    widthPx: Int,
    heightPx: Int,
    fontSizePx: Float,
    lineHeightMultiplier: Float,
    paragraphSpacingMultiplier: Float = 0.72f,
    fontFamily: ReaderFont = ReaderFont.SERIF,
    fontWeight: ReaderFontWeight = ReaderFontWeight.REGULAR,
    firstLineIndent: Boolean,
    noteAnchors: List<ResolvedNoteAnchor> = emptyList()
): List<ReaderPage> = paginateChapters(
    book,
    book.chapters.indices,
    widthPx,
    heightPx,
    fontSizePx,
    lineHeightMultiplier,
    paragraphSpacingMultiplier,
    fontFamily,
    fontWeight,
    firstLineIndent,
    noteAnchors
)

fun paginateChapter(
    book: Book,
    chapterIndex: Int,
    widthPx: Int,
    heightPx: Int,
    fontSizePx: Float,
    lineHeightMultiplier: Float,
    paragraphSpacingMultiplier: Float = 0.72f,
    fontFamily: ReaderFont = ReaderFont.SERIF,
    fontWeight: ReaderFontWeight = ReaderFontWeight.REGULAR,
    firstLineIndent: Boolean,
    noteAnchors: List<ResolvedNoteAnchor> = emptyList(),
    checkActive: () -> Unit = {}
): List<ReaderPage> = paginateChapters(
    book,
    listOf(chapterIndex),
    widthPx,
    heightPx,
    fontSizePx,
    lineHeightMultiplier,
    paragraphSpacingMultiplier,
    fontFamily,
    fontWeight,
    firstLineIndent,
    noteAnchors,
    checkActive
)

@SuppressLint("WrongConstant")
private fun paginateChapters(
    book: Book,
    chapterIndices: Iterable<Int>,
    widthPx: Int,
    heightPx: Int,
    fontSizePx: Float,
    lineHeightMultiplier: Float,
    paragraphSpacingMultiplier: Float,
    fontFamily: ReaderFont,
    fontWeight: ReaderFontWeight,
    firstLineIndent: Boolean,
    noteAnchors: List<ResolvedNoteAnchor>,
    checkActive: () -> Unit = {}
): List<ReaderPage> {
    if (widthPx <= 0 || heightPx <= 0) return emptyList()
    val pages = mutableListOf<ReaderPage>()
    val sectionNodes = book.toc.filter { it.type == TocNodeType.SECTION }

    chapterIndices.forEach chapterLoop@ { chapterIndex ->
        checkActive()
        val chapter = book.chapters.getOrNull(chapterIndex) ?: return@chapterLoop
        val text = StringBuilder()
        val paragraphStarts = mutableListOf<Int>()
        val paragraphContentStarts = mutableListOf<Int>()
        val ranges = mutableListOf<TextRange>()
        val footnoteRanges = mutableListOf<FootnoteRange>()
        val noteRanges = mutableListOf<NoteRange>()
        val paragraphRanges = mutableListOf<ParagraphRange>()
        val spacingRanges = mutableListOf<SpacingRange>()

        val titleStart = text.length
        text.append(chapter.title)
        ranges += TextRange(titleStart, text.length, 0)
        val titleSpacingStart = text.length
        text.append("\n\n")
        spacingRanges += SpacingRange(titleSpacingStart, text.length)

        val sectionsByParagraph = sectionNodes
            .filter { it.chapterId == chapter.id }
            .groupBy { it.paragraphIndex }
        chapter.paragraphs.forEachIndexed { paragraphIndex, paragraph ->
            checkActive()
            paragraphStarts += text.length
            val sectionNode = sectionsByParagraph[paragraphIndex]?.maxByOrNull { it.level }
            if (firstLineIndent && sectionNode == null && shouldIndentParagraph(paragraph)) {
                text.append("　　")
            }
            paragraphContentStarts += text.length
            val start = text.length
            text.append(paragraph)
            paragraphRanges += ParagraphRange(paragraphIndex, start, text.length)
            sectionNode?.let { node ->
                ranges += TextRange(start, text.length, node.level.coerceIn(2, 4))
            }
            if (paragraphIndex != chapter.paragraphs.lastIndex) {
                val spacingStart = text.length
                text.append("\n\n")
                spacingRanges += SpacingRange(spacingStart, text.length)
            }
        }
        chapter.footnotes.forEach { footnote ->
            footnote.references.forEach { reference ->
                val paragraphStart = paragraphContentStarts.getOrNull(reference.paragraphIndex)
                    ?: return@forEach
                footnoteRanges += FootnoteRange(
                    start = paragraphStart + reference.start,
                    end = paragraphStart + reference.end,
                    footnote = footnote
                )
            }
        }
        noteAnchors.filter { it.note.chapterId == chapter.id }.forEach { anchor ->
            val paragraphStart = paragraphContentStarts.getOrNull(anchor.paragraphIndex)
                ?: return@forEach
            val paragraph = chapter.paragraphs[anchor.paragraphIndex]
            val start = anchor.start.coerceIn(0, paragraph.length)
            val end = anchor.end.coerceIn(start, paragraph.length)
            if (end > start) noteRanges += NoteRange(paragraphStart + start, paragraphStart + end, anchor.note)
        }
        if (text.isEmpty()) return@chapterLoop

        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            textSize = fontSizePx
            typeface = readerTypeface(fontFamily, fontWeight)
        }
        val styled = SpannableString(text.toString())
        spacingRanges.forEach { range ->
            styled.setSpan(
                RelativeSizeSpan(paragraphSpacingMultiplier.coerceIn(.25f, 1.5f)),
                range.start,
                range.end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        ranges.forEach { range ->
            val extraPx = when (range.level) {
                0 -> fontSizePx * .32f
                2 -> fontSizePx * .14f
                else -> fontSizePx * .07f
            }
            styled.setSpan(
                AbsoluteSizeSpan((fontSizePx + extraPx).toInt(), false),
                range.start,
                range.end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            styled.setSpan(
                StyleSpan(if (range.level <= 2) Typeface.BOLD else Typeface.BOLD_ITALIC),
                range.start,
                range.end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        footnoteRanges.forEach { range ->
            styled.setSpan(
                RelativeSizeSpan(.78f),
                range.start,
                range.end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            styled.setSpan(
                SuperscriptSpan(),
                range.start,
                range.end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            styled.setSpan(
                StyleSpan(Typeface.BOLD),
                range.start,
                range.end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        val naturalLineHeight = paint.fontMetrics.run { descent - ascent }
        val desiredLineHeight = fontSizePx * lineHeightMultiplier
        val layout = StaticLayout.Builder.obtain(styled, 0, styled.length, paint, widthPx)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .setLineSpacing(max(0f, desiredLineHeight - naturalLineHeight), 1f)
            .apply {
                // TextView enables fallback-font metrics by default. CJK fallback fonts
                // can be taller than the Latin font used by TextPaint.fontMetrics.
                if (Build.VERSION.SDK_INT >= 28) setUseLineSpacingFromFallbacks(true)
            }
            .build()

        var startLine = 0
        while (startLine < layout.lineCount) {
            checkActive()
            val top = layout.getLineTop(startLine)
            var endLine = startLine
            while (
                endLine + 1 < layout.lineCount &&
                layout.getLineBottom(endLine + 1) - top <= heightPx
            ) {
                endLine++
            }
            val startChar = layout.getLineStart(startLine)
            // A trailing newline can yield a phantom final line starting at text length.
            if (startChar >= styled.length) break
            var endChar = layout.getLineEnd(endLine).coerceIn(startChar + 1, styled.length)
            // Never leave half of a section heading at the foot of a page. If
            // the proposed boundary crosses a heading, move that heading to the
            // next page as one visual unit.
            val splitHeading = ranges.firstOrNull {
                it.start > startChar && it.start < endChar && it.end > endChar
            }
            if (splitHeading != null) {
                val headingLine = layout.getLineForOffset(splitHeading.start)
                if (headingLine > startLine) {
                    endLine = headingLine - 1
                    endChar = layout.getLineEnd(endLine).coerceIn(startChar + 1, styled.length)
                }
            }
            val paragraphIndex = paragraphStarts.indexOfLast { it <= startChar }.coerceAtLeast(0)
            val paragraphEndIndex = paragraphStarts.indexOfLast { it < endChar }.coerceAtLeast(paragraphIndex)
            val pageRanges = ranges.mapNotNull { range ->
                clipTextRangeToPage(range.start, range.end, startChar, endChar)?.let { (start, end) ->
                    PageEmphasis(start, end, range.level)
                }
            }
            val pageFootnotes = footnoteRanges.mapNotNull { range ->
                clipTextRangeToPage(range.start, range.end, startChar, endChar)?.let { (start, end) ->
                    PageFootnote(start, end, range.footnote)
                }
            }
            val pageNotes = noteRanges.mapNotNull { range ->
                clipTextRangeToPage(range.start, range.end, startChar, endChar)?.let { (start, end) ->
                    PageNote(start, end, range.note)
                }
            }
            val pageParagraphs = paragraphRanges.mapNotNull { range ->
                clipTextRangeToPage(range.start, range.end, startChar, endChar)?.let { (start, end) ->
                    PageParagraphRange(
                        paragraphIndex = range.paragraphIndex,
                        start = start,
                        end = end,
                        sourceStart = startChar + start - range.start
                    )
                }
            }
            pages += ReaderPage(
                chapterIndex = chapterIndex,
                chapterId = chapter.id,
                chapterTitle = chapter.title,
                paragraphIndex = paragraphIndex,
                paragraphEndIndex = paragraphEndIndex,
                text = styled.substring(startChar, endChar).trimEnd(),
                emphasis = pageRanges,
                footnotes = pageFootnotes,
                notes = pageNotes,
                paragraphRanges = pageParagraphs,
                spacing = spacingRanges.mapNotNull { range ->
                    clipTextRangeToPage(range.start, range.end, startChar, endChar)?.let { (start, end) ->
                        PageSpacing(start, end, paragraphSpacingMultiplier.coerceIn(.25f, 1.5f))
                    }
                }
            )
            startLine = endLine + 1
        }
    }
    return pages
}

/** Avoid indenting headings, lists, tables, signatures, formulas and short poetic lines. */
fun shouldIndentParagraph(text: String): Boolean {
    val value = text.trim()
    if (value.length < 16 || value.length > 0 && value.first() in "①②③④⑤⑥⑦⑧⑨⑩•·—※＊*") return false
    if (value.startsWith("注") && value.take(6).any(Char::isDigit)) return false
    if (Regex("^(第[一二三四五六七八九十百千万0-9]+[章节部卷篇]|[一二三四五六七八九十]+[、.]|[0-9]+[.)、])").containsMatchIn(value)) return false
    if (value.count { it == '│' || it == '|' || it == '\t' } >= 2) return false
    if (value.endsWith("年") || value.endsWith("日") || value.endsWith("者")) return false
    return true
}

fun List<ReaderPage>.pageFor(
    chapterId: String?,
    paragraphIndex: Int,
    characterOffset: Int = 0
): Int {
    if (isEmpty()) return 0
    val targetChapter = chapterId ?: first().chapterId
    val exactPage = indexOfFirst {
        it.chapterId == targetChapter && it.paragraphRanges.any { range ->
            val sourceEnd = range.sourceStart + (range.end - range.start)
            range.paragraphIndex == paragraphIndex &&
                characterOffset >= range.sourceStart && characterOffset < sourceEnd
        }
    }
    if (exactPage >= 0) return exactPage
    val containingPage = indexOfFirst {
        it.chapterId == targetChapter && paragraphIndex in it.paragraphIndex..it.paragraphEndIndex
    }
    return containingPage.takeIf { it >= 0 }
        ?: indexOfFirst { it.chapterId == targetChapter }.coerceAtLeast(0)
}

fun ReaderPage.sourcePositionAt(pageOffset: Int): Pair<Int, Int>? {
    val range = paragraphRanges.firstOrNull { pageOffset in it.start until it.end } ?: return null
    return range.paragraphIndex to (range.sourceStart + pageOffset - range.start)
}

fun ReaderPage.firstSourcePosition(): Pair<Int, Int> = paragraphRanges.firstOrNull()?.let {
    it.paragraphIndex to it.sourceStart
} ?: (paragraphIndex to 0)
