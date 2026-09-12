package org.marxreader.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.marxreader.app.data.LibraryRepository

/** The revision split: throttled saves must not reload library screens; immediate saves must. */
@RunWith(AndroidJUnit4::class)
class ProgressRevisionTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun cleanUp() { context.deleteDatabase("reader.db") }

    @Test
    fun progressOnlyWriteTicksProgressRevisionAlone() = runBlocking {
        context.deleteDatabase("reader.db")
        val repository = LibraryRepository(context)
        val dataBefore = repository.dataRevision.value
        val progressBefore = repository.progressRevision.value

        repository.saveProgressNow("book", "chapter", 1, progressOnly = true)

        assertEquals("节流保存不得触发书库层刷新", dataBefore, repository.dataRevision.value)
        assertEquals("节流保存应推进 progressRevision", progressBefore + 1, repository.progressRevision.value)
    }

    @Test
    fun immediateWriteBumpsDataRevision() = runBlocking {
        context.deleteDatabase("reader.db")
        val repository = LibraryRepository(context)
        val dataBefore = repository.dataRevision.value
        val progressBefore = repository.progressRevision.value

        repository.saveProgressNow("book", "chapter", 2, progressOnly = false)

        assertEquals("立即保存应推进 dataRevision", dataBefore + 1, repository.dataRevision.value)
        assertTrue("立即保存同时不应丢失写入", repository.progress(bookId = "book")?.paragraphIndex == 2)
    }

    @Test
    fun sessionWriteKeepsBumpingDataRevision() = runBlocking {
        context.deleteDatabase("reader.db")
        val repository = LibraryRepository(context)
        val dataBefore = repository.dataRevision.value

        val sessionId = repository.startReadingSession("book", "chapter", 0)
        repository.addReadingTime(sessionId, 15_000, "chapter", 1)

        assertEquals("会话写入保持 dataRevision（书架统计在退出阅读后需刷新）",
            dataBefore + 1, repository.dataRevision.value)
    }
}
