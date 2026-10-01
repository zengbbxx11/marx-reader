package org.marxreader.app.data

import java.security.MessageDigest

private val sourceImageMarker = Regex("〔(?:图式|原表|附件)\\d+〕")

private val sourceTableBoundary = Regex("\u2002\n?")
private val sourceTableBooks = setOf("capital-v1-zh", "capital-v3-zh", "engels-work-12d43c9362e2", "lenin-work-9b5e398ffc38", "capital-v2-zh", "lenin-work-5409317c986a", "marx-work-a100a1458ef3")

data class TextSelection(val start: Int, val end: Int, val text: String)

enum class NoteAnchorStatus { EXACT, RELOCATED, FALLBACK }

data class ResolvedNoteAnchor(
    val note: Note,
    val paragraphIndex: Int,
    val start: Int,
    val end: Int,
    val status: NoteAnchorStatus
)

fun paragraphHash(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray())
    .joinToString("") { "%02x".format(it) }

fun noteForSelection(
    bookId: String,
    chapterId: String,
    paragraphIndex: Int,
    paragraph: String,
    selection: TextSelection,
    text: String,
    color: HighlightColor = HighlightColor.YELLOW,
    tags: List<String> = emptyList(),
    pinned: Boolean = false,
    id: Long = 0,
    createdAt: Long = System.currentTimeMillis(),
    updatedAt: Long = createdAt
): Note = Note(
    id = id,
    bookId = bookId,
    chapterId = chapterId,
    paragraphIndex = paragraphIndex,
    excerpt = selection.text,
    text = text.trim(),
    updatedAt = updatedAt,
    selectionStart = selection.start,
    selectionEnd = selection.end,
    paragraphHash = paragraphHash(paragraph),
    anchorPrefix = paragraph.substring((selection.start - 32).coerceAtLeast(0), selection.start),
    anchorSuffix = paragraph.substring(selection.end, (selection.end + 32).coerceAtMost(paragraph.length)),
    createdAt = createdAt,
    anchorState = "EXACT",
    kind = if (text.isBlank()) NoteKind.HIGHLIGHT else NoteKind.ANNOTATION,
    color = color,
    tags = normalizeNoteTags(tags),
    pinned = pinned
)

fun resolveNoteAnchor(note: Note, paragraphs: List<String>): ResolvedNoteAnchor {
    val original = paragraphs.getOrNull(note.paragraphIndex)
    if (original != null) {
        val start = note.selectionStart.coerceIn(0, original.length)
        val end = note.selectionEnd.coerceIn(start, original.length)
        val rangeMatches = end > start && original.substring(start, end) == note.excerpt
        val hashMatches = note.paragraphHash.isNotBlank() && paragraphHash(original) == note.paragraphHash
        if (rangeMatches || hashMatches) {
            val resolvedEnd = end
            return ResolvedNoteAnchor(note, note.paragraphIndex, start, resolvedEnd, NoteAnchorStatus.EXACT)
        }
    }

    data class Candidate(val paragraphIndex: Int, val start: Int, val end: Int, val score: Int)
    val candidates = buildList {
        paragraphs.forEachIndexed { paragraphIndex, paragraph ->
            var from = 0
            while (note.excerpt.isNotEmpty()) {
                val start = paragraph.indexOf(note.excerpt, from)
                if (start < 0) break
                val prefix = paragraph.substring((start - note.anchorPrefix.length).coerceAtLeast(0), start)
                val suffixStart = start + note.excerpt.length
                val suffix = paragraph.substring(suffixStart, (suffixStart + note.anchorSuffix.length).coerceAtMost(paragraph.length))
                val score = commonSuffix(prefix, note.anchorPrefix) + commonPrefix(suffix, note.anchorSuffix) -
                    kotlin.math.abs(paragraphIndex - note.paragraphIndex)
                add(Candidate(paragraphIndex, start, start + note.excerpt.length, score))
                from = start + note.excerpt.length.coerceAtLeast(1)
            }
            // Bind genuine source-character insertions to both paragraph hashes.
            // Old quotations may span a restored digit or punctuation mark.
            val restoredRanges = sourceRestorationRanges(paragraphHash(paragraph), note.paragraphHash)
            if (restoredRanges.isNotEmpty() && note.excerpt.isNotEmpty()) {
                val ignored = BooleanArray(paragraph.length)
                restoredRanges.forEach { range -> range.forEach { ignored[it] = true } }
                val positions = paragraph.indices.filter { !ignored[it] }
                val plain = positions.joinToString("") { paragraph[it].toString() }
                var searchFrom = 0
                while (true) {
                    val start = plain.indexOf(note.excerpt, searchFrom)
                    if (start < 0) break
                    val end = start + note.excerpt.length
                    val prefix = plain.substring((start - note.anchorPrefix.length).coerceAtLeast(0), start)
                    val suffix = plain.substring(end, (end + note.anchorSuffix.length).coerceAtMost(plain.length))
                    val score = commonSuffix(prefix, note.anchorPrefix) + commonPrefix(suffix, note.anchorSuffix) -
                        kotlin.math.abs(paragraphIndex - note.paragraphIndex)
                    add(Candidate(paragraphIndex, positions[start], positions[end - 1] + 1, score))
                    searchFrom = end
                }
            }
            // Source restoration inserts read-only image markers and table boundaries.
            // Old selections spanning an insertion must still map to the same words.
            val hasTableFormatting = note.bookId in sourceTableBooks && sourceTableBoundary.containsMatchIn(paragraph)
            if (!sourceImageMarker.containsMatchIn(note.excerpt) && !sourceTableBoundary.containsMatchIn(note.excerpt) &&
                (sourceImageMarker.containsMatchIn(paragraph) || hasTableFormatting) && note.excerpt.isNotEmpty()) {
                val ignored = BooleanArray(paragraph.length)
                sourceImageMarker.findAll(paragraph).forEach { match -> match.range.forEach { ignored[it] = true } }
                if (hasTableFormatting) sourceTableBoundary.findAll(paragraph).forEach { match ->
                    match.range.forEach { ignored[it] = true }
                }
                val positions = paragraph.indices.filter { !ignored[it] }
                val plain = positions.joinToString("") { paragraph[it].toString() }
                var searchFrom = 0
                while (true) {
                    val start = plain.indexOf(note.excerpt, searchFrom)
                    if (start < 0) break
                    val end = start + note.excerpt.length
                    val prefix = plain.substring((start - note.anchorPrefix.length).coerceAtLeast(0), start)
                    val suffix = plain.substring(end, (end + note.anchorSuffix.length).coerceAtMost(plain.length))
                    val score = commonSuffix(prefix, note.anchorPrefix) + commonPrefix(suffix, note.anchorSuffix) -
                        kotlin.math.abs(paragraphIndex - note.paragraphIndex)
                    add(Candidate(paragraphIndex, positions[start], positions[end - 1] + 1, score))
                    searchFrom = end
                }
            }
        }
    }
    val best = candidates.maxByOrNull { it.score }
    if (best != null) return ResolvedNoteAnchor(
        note, best.paragraphIndex, best.start, best.end, NoteAnchorStatus.RELOCATED
    )

    val fallbackIndex = note.paragraphIndex.coerceIn(0, paragraphs.lastIndex.coerceAtLeast(0))
    val fallback = paragraphs.getOrNull(fallbackIndex).orEmpty()
    return ResolvedNoteAnchor(
        note, fallbackIndex, 0, fallback.length.coerceAtMost(note.excerpt.length), NoteAnchorStatus.FALLBACK
    )
}

private fun commonPrefix(left: String, right: String): Int =
    left.zip(right).takeWhile { it.first == it.second }.size

private fun commonSuffix(left: String, right: String): Int =
    commonPrefix(left.reversed(), right.reversed())
