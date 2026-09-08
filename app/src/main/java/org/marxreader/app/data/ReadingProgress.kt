package org.marxreader.app.data

data class ReadingProgressValue(
    val absoluteCharacterOffset: Long,
    val totalCharacterCount: Long,
    val fraction: Float,
    val percent: Float,
    val completed: Boolean
) {
    val displayPercent: String
        get() = if (completed) "100%" else "%.1f%%".format(percent.coerceIn(0f, 99.9f))
}

fun Book.readingProgress(position: ReaderPosition?): ReadingProgressValue {
    val total = characterCount.toLong().coerceAtLeast(0L)
    if (position == null || position.bookId != id || chapters.isEmpty() || total == 0L) {
        return ReadingProgressValue(0L, total, 0f, 0f, false)
    }
    val chapterIndex = chapterIndex(position.chapterId)
    val beforeChapter = charactersBeforeChapter(chapterIndex).toLong()
    val insideChapter = chapters[chapterIndex]
        .characterOffset(position.paragraphIndex, position.characterOffset)
        .toLong()
    val absolute = (beforeChapter + insideChapter).coerceIn(0L, total)
    val isCompleted = position.completed || absolute >= total
    val fraction = if (isCompleted) 1f else absolute.toFloat() / total.toFloat()
    return ReadingProgressValue(
        absoluteCharacterOffset = if (isCompleted) total else absolute,
        totalCharacterCount = total,
        fraction = fraction.coerceIn(0f, 1f),
        percent = (fraction * 100f).coerceIn(0f, 100f),
        completed = isCompleted
    )
}
