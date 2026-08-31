package org.marxreader.app.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToLong

fun normalizeNoteTags(values: Iterable<String>): List<String> = values
    .flatMap { it.split(',', '，', ';', '；') }
    .map { it.trim().replace(Regex("\\s+"), " ").take(24) }
    .filter { it.isNotEmpty() }
    .distinctBy { it.lowercase() }
    .take(12)

fun calculateReadingStatistics(
    sessions: List<ReadingSession>,
    completedBooks: Int,
    nowMillis: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault()
): ReadingStatistics {
    val today = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
    val dailyMap = mutableMapOf<LocalDate, Long>()
    sessions.filter { it.activeMillis > 0 }.forEach { session ->
        distributeSessionAcrossDays(session, zoneId).forEach { (date, millis) ->
            dailyMap[date] = dailyMap.getOrDefault(date, 0L) + millis
        }
    }
    val daily = (6 downTo 0).map { offset ->
        val date = today.minusDays(offset.toLong())
        DailyReadingStat(date.toEpochDay(), dailyMap[date] ?: 0L)
    }
    val activeDates = dailyMap.filterValues { it > 0 }.keys
    var streak = 0
    var cursor = today
    if (cursor !in activeDates) cursor = cursor.minusDays(1)
    while (cursor in activeDates) {
        streak++
        cursor = cursor.minusDays(1)
    }
    val books = sessions.groupBy { it.bookId }.map { (bookId, values) ->
        BookReadingStat(bookId, values.sumOf { it.activeMillis }, values.size)
    }.filter { it.activeMillis > 0 }.sortedByDescending { it.activeMillis }
    return ReadingStatistics(
        todayMillis = dailyMap[today] ?: 0L,
        lastSevenDaysMillis = daily.sumOf { it.activeMillis },
        totalMillis = sessions.sumOf { it.activeMillis },
        currentStreakDays = streak,
        activeDays = activeDates.size,
        completedBooks = completedBooks,
        daily = daily,
        books = books
    )
}

private fun distributeSessionAcrossDays(
    session: ReadingSession,
    zoneId: ZoneId
): Map<LocalDate, Long> {
    val start = session.startedAt
    val end = session.endedAt.coerceAtLeast(start + 1)
    val wallMillis = end - start
    val allocations = linkedMapOf<LocalDate, Long>()
    var cursor = start
    while (cursor < end) {
        val zoned = Instant.ofEpochMilli(cursor).atZone(zoneId)
        val date = zoned.toLocalDate()
        val nextDay = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val segmentEnd = minOf(end, nextDay)
        val share = session.activeMillis.toDouble() * (segmentEnd - cursor).toDouble() / wallMillis
        allocations[date] = allocations.getOrDefault(date, 0L) + share.roundToLong()
        cursor = segmentEnd
    }
    val difference = session.activeMillis - allocations.values.sum()
    if (difference != 0L && allocations.isNotEmpty()) {
        val last = allocations.keys.last()
        allocations[last] = allocations.getValue(last) + difference
    }
    return allocations
}
