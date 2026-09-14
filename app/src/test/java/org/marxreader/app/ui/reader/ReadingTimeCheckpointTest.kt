package org.marxreader.app.ui

import kotlinx.coroutines.*
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingTimeCheckpointTest {
    @Test fun cancellationAfterCommitDoesNotRecordTheIntervalTwice() = runBlocking {
        withTimeout(5_000) {
            val checkpoint = ReadingTimeCheckpoint(0)
            val committed = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val writes = mutableListOf<Long>()
            val job = launch {
                checkpoint.recordUntil(15_000) { elapsed ->
                    withContext(Dispatchers.Default) {
                        writes += elapsed
                        committed.complete(Unit)
                        finish.await()
                    }
                }
            }
            committed.await()
            job.cancel()
            finish.complete(Unit)
            job.join()
            checkpoint.recordUntil(16_000) { writes += it }
            assertEquals(listOf(15_000L, 1_000L), writes)
        }
    }

    @Test fun failedWriteDoesNotAdvanceCheckpoint() = runBlocking {
        val checkpoint = ReadingTimeCheckpoint(1_000)
        runCatching { checkpoint.recordUntil(2_000) { error("disk failure") } }
        val writes = mutableListOf<Long>()
        checkpoint.recordUntil(3_000) { writes += it }
        checkpoint.recordUntil(3_000) { writes += it }
        checkpoint.recordUntil(2_000) { writes += it }
        assertEquals(listOf(2_000L), writes)
    }
}
