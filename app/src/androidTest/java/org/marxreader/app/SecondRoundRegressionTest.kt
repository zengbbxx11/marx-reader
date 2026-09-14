package org.marxreader.app

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.marxreader.app.data.*
import org.marxreader.app.ui.reader.ReaderViewModel
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class SecondRoundRegressionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun exitFlushAwaitsExactlyOneWriteAndRefreshesShelf() = runBlocking {
        context.deleteDatabase("reader.db")
        ReaderDatabase(context).use { database ->
            database.writableDatabase.execSQL("CREATE TABLE write_count(value INTEGER)")
            database.writableDatabase.execSQL("""
                CREATE TRIGGER count_progress AFTER INSERT ON progress
                BEGIN INSERT INTO write_count VALUES(1); END
            """.trimIndent())
            val repository = LibraryRepository(context)
            val store = ViewModelStore()
            try {
                withContext(Dispatchers.Main) {
                    val model = ViewModelProvider(store, ReaderViewModel.factory(repository, "book"))
                        .get(ReaderViewModel::class.java)
                    val position = ReaderPosition("book", "chapter", 5, 0, 7)
                    model.savePosition(position, immediate = true)
                    model.persistPositionNow(position)
                    model.persistPositionNow(position)
                }
                val count = database.readableDatabase.rawQuery(
                    "SELECT COUNT(*) FROM write_count", null
                ).use { it.moveToFirst(); it.getInt(0) }
                assertEquals(1, count)
                assertEquals(1L, repository.dataRevision.value)
                assertEquals(0L, repository.progressRevision.value)
                assertEquals(7, database.progress("book")?.characterOffset)
            } finally {
                withContext(Dispatchers.Main) { store.clear() }
            }
        }
    }

    @Test fun statisticsCacheInvalidatesAfterCompletedProgressAndClear() = runBlocking {
        context.deleteDatabase("reader.db")
        val repository = LibraryRepository(context)
        val initial = repository.readingStatistics()
        assertSame(initial, repository.readingStatistics())
        repository.saveProgressNow("book", "chapter", 1, completed = true)
        assertEquals(1, repository.readingStatistics().completedBooks)
        repository.clearProgress()
        assertEquals(0, repository.readingStatistics().completedBooks)
    }

    @Test fun statisticsStayConsistentWithConcurrentWrites() {
        context.deleteDatabase("reader.db")
        ReaderDatabase(context).use { database ->
            val now = LocalDate.now().atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val db = database.writableDatabase
            db.beginTransaction()
            try {
                repeat(10_000) {
                    val id = database.startReadingSession("book", "chapter", 0, now - 60_000)
                    database.addReadingTime(id, 1000, "chapter", 1, now)
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            val durations = (0 until 6).map {
                val start = SystemClock.elapsedRealtime()
                val stats = database.readingStatistics(now)
                assertEquals(10_000_000L, stats.totalMillis)
                SystemClock.elapsedRealtime() - start
            }
            Log.i("MarxReaderQA", "statistics_10000_ms=$durations")
            runBlocking {
                val repository = LibraryRepository(context)
                repository.readingStatistics()
                val start = SystemClock.elapsedRealtime()
                repeat(20) { repository.readingStatistics() }
                Log.i("MarxReaderQA", "statistics_10000_cached_20_reads_ms=" +
                    (SystemClock.elapsedRealtime() - start))
            }
            val running = AtomicBoolean(true)
            val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            val writer = thread {
                try {
                    // Match the application's single SQLiteOpenHelper/connection pool.
                    // Separate helpers compete through SQLite's busy timeout instead.
                    while (running.get()) {
                        val id = database.startReadingSession("writer", "chapter", 0, now - 60_000)
                        database.addReadingTime(id, 1000, "chapter", 1, now)
                        Thread.sleep(1)
                    }
                } catch (error: Throwable) { failure.set(error) }
            }
            try {
                repeat(12) {
                    val stats = database.readingStatistics(now)
                    assertEquals(stats.totalMillis, stats.todayMillis)
                    assertEquals(stats.totalMillis, stats.lastSevenDaysMillis)
                }
            } finally {
                running.set(false)
                writer.join(10_000)
            }
            assertFalse(writer.isAlive)
            failure.get()?.let { throw AssertionError("Concurrent writer failed", it) }
        }
    }
}
