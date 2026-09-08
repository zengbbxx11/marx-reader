package org.marxreader.app.ui.reader

/** Source characters survive changes in font size and reading mode; pixel positions do not. */
internal data class ParagraphLineMap(
    val starts: List<Int>,
    val tops: List<Int>,
    val indentLength: Int,
    val sourceLength: Int
) {
    fun sourceAt(y: Int): Int {
        if (starts.isEmpty()) return 0
        val line = floorIndex(tops, y)
        return (starts[line] - indentLength).coerceIn(0, sourceLength)
    }

    fun topFor(sourceOffset: Int): Int {
        if (starts.isEmpty()) return 0
        return tops[floorIndex(starts, sourceOffset.coerceIn(0, sourceLength) + indentLength)]
    }

    private fun floorIndex(values: List<Int>, value: Int): Int {
        val found = values.binarySearch(value)
        return (if (found >= 0) found else -found - 2).coerceIn(values.indices)
    }
}

/** Boundary slots make a swipe past a chapter use the same transition as a tap. */
internal data class ChapterPagerWindow(val pageCount: Int, val hasPrevious: Boolean, val hasNext: Boolean) {
    val firstPage: Int get() = if (hasPrevious) 1 else 0
    val slotCount: Int get() = (pageCount + firstPage + if (hasNext) 1 else 0).coerceAtLeast(1)
    fun contentPage(slot: Int): Int = (slot - firstPage).coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    fun chapterDelta(slot: Int): Int = when {
        hasPrevious && slot == 0 -> -1
        hasNext && slot == firstPage + pageCount -> 1
        else -> 0
    }
}

/** Entry and weight limits prevent an unusually long chapter from occupying the entire cache. */
internal class WeightedReaderCache<K, V>(private val maxEntries: Int, private val maxWeight: Int) {
    private val entries = LinkedHashMap<K, Pair<V, Int>>(4, .75f, true)
    private var weight = 0

    @Synchronized operator fun get(key: K): V? = entries[key]?.first

    @Synchronized fun put(key: K, value: V, valueWeight: Int) {
        entries.remove(key)?.let { weight -= it.second }
        if (valueWeight > maxWeight) return
        entries[key] = value to valueWeight
        weight += valueWeight
        while (entries.size > maxEntries || weight > maxWeight) {
            val oldest = entries.entries.iterator()
            weight -= oldest.next().value.second
            oldest.remove()
        }
    }
}
