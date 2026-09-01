package org.marxreader.app.data

import java.security.MessageDigest

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

    data class Candidate(val paragraphIndex: Int, val start: Int, val score: Int)
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
                add(Candidate(paragraphIndex, start, score))
                from = start + note.excerpt.length.coerceAtLeast(1)
            }
        }
    }
    val best = candidates.maxByOrNull { it.score }
    if (best != null) return ResolvedNoteAnchor(
        note, best.paragraphIndex, best.start, best.start + note.excerpt.length, NoteAnchorStatus.RELOCATED
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
