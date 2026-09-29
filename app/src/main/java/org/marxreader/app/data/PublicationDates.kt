package org.marxreader.app.data

import java.time.LocalDate

/** Partial source dates retain their precision; an unknown date never borrows a writing year. */
data class PublicationDate(val year: Int, val month: Int?, val day: Int?) : Comparable<PublicationDate> {
    private val orderingValue get() = year * 10000 + (month ?: 1) * 100 + (day ?: 1)
    override fun compareTo(other: PublicationDate) = orderingValue.compareTo(other.orderingValue)
    val label get() = "${year}年" + (month?.let { "${it}月" } ?: "") + (day?.let { "${it}日" } ?: "")

    companion object {
        fun parse(value: String): PublicationDate? {
            if (!Regex("\\d{4}(-\\d{2})?(-\\d{2})?").matches(value)) return null
            val parts = value.split('-').map { it.toInt() }
            val year = parts[0]
            val month = parts.getOrNull(1)
            val day = parts.getOrNull(2)
            if (year !in 1..9999) return null
            return runCatching { LocalDate.of(year, month ?: 1, day ?: 1); PublicationDate(year, month, day) }.getOrNull()
        }
    }
}

val Book.published: PublicationDate? get() = PublicationDate.parse(publicationDate)
val Book.publicationLabel: String get() {
    val start = published ?: return "发表时间未注明"
    val end = PublicationDate.parse(publicationDateEnd)
    return "发表：${start.label}" + (end?.let { "—${it.label}" } ?: "")
}
