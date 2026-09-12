package org.marxreader.app.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ReadingStatisticsTest {
    private val utc = ZoneId.of("UTC")

    @Test fun tagsAreSplitTrimmedAndDeduplicated() {
        assertEquals(
            listOf("政治经济学", "劳动", "Capital"),
            normalizeNoteTags(listOf(" 政治经济学，劳动 ", "劳动; Capital", "capital"))
        )
    }

    @Test fun sessionCrossingMidnightIsSplitAcrossBothDays() {
        val day = LocalDate.of(2026, 8, 28)
        val start = day.atTime(23, 59).atZone(utc).toInstant().toEpochMilli()
        val end = day.plusDays(1).atTime(0, 1).atZone(utc).toInstant().toEpochMilli()
        val session = ReadingSession(
            1, "book", start, end, 120_000,
            "c1", 0, "c1", 1
        )
        val result = calculateReadingStatistics(
            listOf(session), 1,
            nowMillis = day.plusDays(1).atTime(12, 0).atZone(utc).toInstant().toEpochMilli(),
            zoneId = utc
        )
        assertEquals(60_000, result.daily[result.daily.lastIndex - 1].activeMillis)
        assertEquals(60_000, result.todayMillis)
        assertEquals(2, result.currentStreakDays)
        assertEquals(1, result.completedBooks)
    }

    @Test fun streakUsesYesterdayWhenTodayHasNoReadingYet() {
        val today = LocalDate.of(2026, 8, 29)
        val sessions = (1L..3L).map { offset ->
            val start = today.minusDays(offset).atTime(12, 0).atZone(utc).toInstant().toEpochMilli()
            ReadingSession(offset, "book", start, start + 60_000, 60_000, "c", 0, "c", 0)
        }
        val result = calculateReadingStatistics(
            sessions, 0, today.atTime(12, 0).atZone(utc).toInstant().toEpochMilli(), utc
        )
        assertEquals(3, result.currentStreakDays)
        assertEquals(3, result.activeDays)
    }

    @Test fun daylightSavingDaysPreserveExactTotalAndMidnightRounding() {
        val zone = ZoneId.of("America/New_York")
        listOf(LocalDate.of(2026, 3, 8), LocalDate.of(2026, 11, 1)).forEach { day ->
            val start = day.minusDays(1).atTime(23, 59).atZone(zone).toInstant().toEpochMilli()
            val end = day.plusDays(1).atTime(0, 1).atZone(zone).toInstant().toEpochMilli()
            val allocation = distributeReadingTime(start, end, 123_457L, zone)
            assertEquals(3, allocation.size)
            assertEquals(123_457L, allocation.values.sum())
            val session = ReadingSession(1, "book", start, end, 123_457, "c", 0, "c", 1)
            val expected = calculateReadingStatistics(listOf(session), 0, end, zone)
            val actual = buildReadingStatistics(allocation,
                listOf(BookReadingStat("book", 123_457, 1)), 123_457, 0, end, zone)
            assertEquals(expected, actual)
        }
    }
}
