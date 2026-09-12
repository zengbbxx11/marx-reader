package org.marxreader.app.ui.reader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.marxreader.app.data.Book
import org.marxreader.app.data.ReaderFont
import org.marxreader.app.data.ReaderFontWeight
import org.marxreader.app.ui.ReaderPage
import org.marxreader.app.ui.paginateChapter

internal data class ReaderLayoutSpec(
    val width: Int,
    val height: Int,
    val fontSize: Float,
    val lineHeight: Float,
    val paragraphSpacing: Float,
    val font: ReaderFont,
    val weight: ReaderFontWeight,
    val indent: Boolean
)

internal data class ChapterLayoutResult(val chapterId: String, val spec: ReaderLayoutSpec, val pages: List<ReaderPage>)

/** Owned by one open book. Notes and theme colors never invalidate text layout. */
internal class ChapterPageCache(maxWeightChars: Int) {
    private data class Key(val chapter: Int, val layout: ReaderLayoutSpec)
    private val cache = WeightedReaderCache<Key, List<ReaderPage>>(3, maxWeightChars)

    suspend fun pages(book: Book, chapter: Int, layout: ReaderLayoutSpec): List<ReaderPage> {
        val key = Key(chapter, layout)
        cache[key]?.let { return it }
        return withContext(Dispatchers.Default) {
            val context = currentCoroutineContext()
            val pages = paginateChapter(
                book, chapter, layout.width, layout.height, layout.fontSize,
                layout.lineHeight, layout.paragraphSpacing, layout.font, layout.weight, layout.indent,
                checkActive = { context.ensureActive() }
            )
            context.ensureActive()
            cache.put(key, pages, pages.sumOf { it.text.length })
            pages
        }
    }
}
