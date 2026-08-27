package org.marxreader.app.ui

import android.graphics.Typeface
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

data class ReaderPage(
    val chapterIndex: Int,
    val chapterId: String,
    val chapterTitle: String,
    val paragraphIndex: Int,
    val paragraphEndIndex: Int,
    val text: String,
    val emphasis: List<PageEmphasis>,
    val footnotes: List<PageFootnote>
)

private data class TextRange(val start: Int, val end: Int, val level: Int)
private data class FootnoteRange(val start: Int, val end: Int, val footnote: Footnote)

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
    firstLineIndent: Boolean
): List<ReaderPage> = paginateChapters(
    book,
    book.chapters.indices,
    widthPx,
    heightPx,
    fontSizePx,
    lineHeightMultiplier,
    firstLineIndent
)

fun paginateChapter(
    book: Book,
    chapterIndex: Int,
    widthPx: Int,
    heightPx: Int,
    fontSizePx: Float,
    lineHeightMultiplier: Float,
    firstLineIndent: Boolean
): List<ReaderPage> = paginateChapters(
    book,
    listOf(chapterIndex),
    widthPx,
    heightPx,
    fontSizePx,
    lineHeightMultiplier,
    firstLineIndent
)

private fun paginateChapters(
    book: Book,
    chapterIndices: Iterable<Int>,
    widthPx: Int,
    heightPx: Int,
    fontSizePx: Float,
    lineHeightMultiplier: Float,
    firstLineIndent: Boolean
): List<ReaderPage> {
    if (widthPx <= 0 || heightPx <= 0) return emptyList()
    val pages = mutableListOf<ReaderPage>()
    val sectionNodes = book.toc.filter { it.type == TocNodeType.SECTION }

    chapterIndices.forEach chapterLoop@ { chapterIndex ->
        val chapter = book.chapters.getOrNull(chapterIndex) ?: return@chapterLoop
        val text = StringBuilder()
        val paragraphStarts = mutableListOf<Int>()
        val paragraphContentStarts = mutableListOf<Int>()
        val ranges = mutableListOf<TextRange>()
        val footnoteRanges = mutableListOf<FootnoteRange>()

        val titleStart = text.length
        text.append(chapter.title)
        ranges += TextRange(titleStart, text.length, 0)
        text.append("\n\n")

        val sectionsByParagraph = sectionNodes
            .filter { it.chapterId == chapter.id }
            .groupBy { it.paragraphIndex }
        chapter.paragraphs.forEachIndexed { paragraphIndex, paragraph ->
            paragraphStarts += text.length
            val sectionNode = sectionsByParagraph[paragraphIndex]?.maxByOrNull { it.level }
            if (firstLineIndent && sectionNode == null && shouldIndentParagraph(paragraph)) {
                text.append("　　")
            }
            paragraphContentStarts += text.length
            val start = text.length
            text.append(paragraph)
            sectionNode?.let { node ->
                ranges += TextRange(start, text.length, node.level.coerceIn(2, 4))
            }
            if (paragraphIndex != chapter.paragraphs.lastIndex) text.append("\n\n")
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
        if (text.isEmpty()) return@chapterLoop

        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            textSize = fontSizePx
            typeface = Typeface.SERIF
        }
        val styled = SpannableString(text.toString())
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
        }
        val naturalLineHeight = paint.fontMetrics.run { descent - ascent }
        val desiredLineHeight = fontSizePx * lineHeightMultiplier
        val layout = StaticLayout.Builder.obtain(styled, 0, styled.length, paint, widthPx)
            .setIncludePad(false)
            .setLineSpacing(max(0f, desiredLineHeight - naturalLineHeight), 1f)
            .build()

        var startLine = 0
        while (startLine < layout.lineCount) {
            val top = layout.getLineTop(startLine)
            var endLine = startLine
            while (
                endLine + 1 < layout.lineCount &&
                layout.getLineBottom(endLine + 1) - top <= heightPx
            ) {
                endLine++
            }
            val startChar = layout.getLineStart(startLine)
            var endChar = layout.getLineEnd(endLine).coerceAtLeast(startChar + 1)
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
                    endChar = layout.getLineEnd(endLine).coerceAtLeast(startChar + 1)
                }
            }
            val paragraphIndex = paragraphStarts.indexOfLast { it <= startChar }.coerceAtLeast(0)
            val paragraphEndIndex = paragraphStarts.indexOfLast { it < endChar }.coerceAtLeast(paragraphIndex)
            val pageRanges = ranges.mapNotNull { range ->
                val overlapStart = max(range.start, startChar)
                val overlapEnd = minOf(range.end, endChar)
                if (overlapStart < overlapEnd) {
                    PageEmphasis(overlapStart - startChar, overlapEnd - startChar, range.level)
                } else null
            }
            val pageFootnotes = footnoteRanges.mapNotNull { range ->
                if (range.start >= startChar && range.end <= endChar) {
                    PageFootnote(range.start - startChar, range.end - startChar, range.footnote)
                } else null
            }
            pages += ReaderPage(
                chapterIndex = chapterIndex,
                chapterId = chapter.id,
                chapterTitle = chapter.title,
                paragraphIndex = paragraphIndex,
                paragraphEndIndex = paragraphEndIndex,
                text = styled.substring(startChar, endChar).trimEnd(),
                emphasis = pageRanges,
                footnotes = pageFootnotes
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

fun List<ReaderPage>.pageFor(chapterId: String?, paragraphIndex: Int): Int {
    if (isEmpty()) return 0
    val targetChapter = chapterId ?: first().chapterId
    return indexOfFirst {
        it.chapterId == targetChapter && paragraphIndex in it.paragraphIndex..it.paragraphEndIndex
    }.takeIf { it >= 0 } ?: indexOfFirst { it.chapterId == targetChapter }.coerceAtLeast(0)
}
