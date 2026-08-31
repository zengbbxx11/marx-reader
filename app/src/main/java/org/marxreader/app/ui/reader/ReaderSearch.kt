package org.marxreader.app.ui.reader

import org.marxreader.app.data.Book
import org.marxreader.app.ui.ReaderPage
import kotlin.math.max
import kotlin.math.min

enum class ReaderSearchScope { CHAPTER, BOOK }

data class ReaderSearchMatch(
    val chapterId: String,
    val chapterTitle: String,
    val paragraphIndex: Int,
    val start: Int,
    val end: Int,
    val excerpt: String
)

data class ReaderSearchState(
    val query: String = "",
    val scope: ReaderSearchScope = ReaderSearchScope.CHAPTER,
    val matches: List<ReaderSearchMatch> = emptyList(),
    val selectedIndex: Int = -1,
    val searching: Boolean = false
) {
    val selectedMatch: ReaderSearchMatch?
        get() = matches.getOrNull(selectedIndex)
}

internal fun findReaderMatches(
    book: Book,
    currentChapterId: String,
    query: String,
    scope: ReaderSearchScope,
    limit: Int = 500
): List<ReaderSearchMatch> {
    val needle = query.trim()
    if (needle.length < 2 || limit <= 0) return emptyList()
    val chapters = if (scope == ReaderSearchScope.CHAPTER) {
        book.chapters.filter { it.id == currentChapterId }
    } else book.chapters
    return buildList {
        chapters.forEach { chapter ->
            chapter.paragraphs.forEachIndexed { paragraphIndex, paragraph ->
                var from = 0
                while (from <= paragraph.length - needle.length && size < limit) {
                    val start = paragraph.indexOf(needle, from, ignoreCase = true)
                    if (start < 0) break
                    val end = start + needle.length
                    val excerptStart = max(0, start - 32)
                    val excerptEnd = min(paragraph.length, end + 48)
                    add(ReaderSearchMatch(
                        chapterId = chapter.id,
                        chapterTitle = chapter.title,
                        paragraphIndex = paragraphIndex,
                        start = start,
                        end = end,
                        excerpt = buildString {
                            if (excerptStart > 0) append('…')
                            append(paragraph.substring(excerptStart, excerptEnd))
                            if (excerptEnd < paragraph.length) append('…')
                        }
                    ))
                    from = end.coerceAtLeast(start + 1)
                }
                if (size >= limit) return@buildList
            }
        }
    }
}

data class PageSearchRange(val start: Int, val end: Int, val selected: Boolean)

internal fun ReaderPage.searchRanges(
    matches: List<ReaderSearchMatch>,
    selectedMatch: ReaderSearchMatch?
): List<PageSearchRange> = paragraphRanges.flatMap { pageRange ->
    val sourceEnd = pageRange.sourceStart + pageRange.end - pageRange.start
    matches.asSequence()
        .filter { it.chapterId == chapterId && it.paragraphIndex == pageRange.paragraphIndex }
        .mapNotNull { match ->
            val overlapStart = max(match.start, pageRange.sourceStart)
            val overlapEnd = min(match.end, sourceEnd)
            if (overlapStart >= overlapEnd) null else PageSearchRange(
                start = pageRange.start + overlapStart - pageRange.sourceStart,
                end = pageRange.start + overlapEnd - pageRange.sourceStart,
                selected = match == selectedMatch
            )
        }.toList()
}
