package org.marxreader.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.marxreader.app.data.*
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

@RunWith(AndroidJUnit4::class)
class OptimizationRegressionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun finalPositionIsReadableWhileDiskWriterIsBlocked() = runBlocking {
        context.deleteDatabase("reader.db")
        ReaderDatabase(context).use { blocker ->
            val db = blocker.writableDatabase
            val repository = LibraryRepository(context)
            db.beginTransaction()
            try {
                repeat(100) { repository.saveProgress("book", "chapter", it, it) }
                assertEquals(99, repository.progress("book")?.paragraphIndex)
                assertEquals(99, repository.progress("book")?.characterOffset)
            } finally {
                db.endTransaction()
            }
            repository.saveProgressNow("book", "chapter", 100, 7)
            assertEquals(100, blocker.progress("book")?.paragraphIndex)
            assertEquals(7, blocker.progress("book")?.characterOffset)
            repository.clearProgress()
            assertNull(repository.progress("book"))
        }
    }

    @Test fun timingOutFlushDoesNotWaitForBlockedDatabaseOperation() = runBlocking {
        context.deleteDatabase("reader.db")
        ReaderDatabase(context).use { blocker ->
            val db = blocker.writableDatabase
            val repository = LibraryRepository(context)
            db.beginTransaction()
            try {
                val started = android.os.SystemClock.elapsedRealtime()
                val result = kotlinx.coroutines.withTimeoutOrNull(100) {
                    repository.saveProgressNow("book", "chapter", 1)
                    true
                }
                assertNull(result)
                assertTrue(android.os.SystemClock.elapsedRealtime() - started < 1500)
                assertEquals(1, repository.progress("book")?.paragraphIndex)
            } finally {
                db.endTransaction()
            }
            repository.saveProgressNow("book", "chapter", 2)
            assertEquals(2, blocker.progress("book")?.paragraphIndex)
        }
    }

    @Test fun dismissingOldErrorCannotDiscardNewError() = runBlocking {
        context.deleteDatabase("reader.db")
        ReaderDatabase(context).use { database ->
            database.writableDatabase.execSQL("""
                CREATE TRIGGER fail_progress BEFORE INSERT ON progress
                BEGIN SELECT RAISE(ABORT, 'simulated failure'); END
            """.trimIndent())
            val repository = LibraryRepository(context)
            assertTrue(runCatching { repository.saveProgressNow("book", "chapter", 1) }.isFailure)
            val first = requireNotNull(repository.writeError.value)
            assertTrue(runCatching { repository.saveProgressNow("book", "chapter", 2) }.isFailure)
            val second = requireNotNull(repository.writeError.value)
            assertTrue(second.occurrence > first.occurrence)
            repository.dismissWriteError(first)
            assertEquals(second, repository.writeError.value)
            repository.dismissWriteError(second)
            assertNull(repository.writeError.value)
        }
    }

    @Test fun databaseStatisticsMatchLegacyCalculationAcrossTimeZones() {
        context.deleteDatabase("reader.db")
        val previousZone = TimeZone.getDefault()
        try {
            ReaderDatabase(context).use { database ->
                listOf("UTC", "Asia/Shanghai", "America/New_York").forEach { zoneName ->
                    TimeZone.setDefault(TimeZone.getTimeZone(zoneName))
                    val zone = ZoneId.systemDefault()
                    database.clearProgress()
                    val start = LocalDate.of(2026, 3, 7).atTime(23, 59)
                        .atZone(zone).toInstant().toEpochMilli()
                    repeat(250) { index ->
                        val session = database.startReadingSession("book-${index % 4}", "c", 0,
                            start + index * 30_000L)
                        database.addReadingTime(session, 59_999, "c", 1,
                            start + index * 30_000L + 180_000)
                    }
                    database.saveProgress("finished", "c", 0, completed = true)
                    val now = start + 172_800_000L
                    val expected = calculateReadingStatistics(database.readingSessions(), 1, now, zone)
                    assertEquals(expected, database.readingStatistics(now))
                }
            }
        } finally {
            TimeZone.setDefault(previousZone)
        }
    }

    @Test fun equalBookTotalsRetainMostRecentSessionOrder() {
        context.deleteDatabase("reader.db")
        ReaderDatabase(context).use { database ->
            listOf("a", "b", "c").forEach { book ->
                val id = database.startReadingSession(book, "c", 0, 1000)
                database.addReadingTime(id, 1000, "c", 1, 2000)
            }
            assertEquals(
                calculateReadingStatistics(database.readingSessions(), 0, 3000),
                database.readingStatistics(3000)
            )
        }
    }

    @Test fun malformedPreferenceTypesUseDefaults() {
        val storage = context.getSharedPreferences("reader_settings", Context.MODE_PRIVATE)
        val previous = ReaderPreferences(context).settings.value
        try {
            storage.edit().clear().putString("font_size", "bad")
                .putFloat("line_height", Float.NaN).putInt("theme", 42)
                .putString("keep_screen_on", "bad").putInt("search_history", 3).commit()
            val preferences = ReaderPreferences(context)
            assertEquals(ReaderSettings(), preferences.settings.value)
            assertTrue(preferences.searchHistory.value.isEmpty())
        } finally {
            storage.edit().clear().commit()
            ReaderPreferences(context).update(previous)
        }
    }
}
