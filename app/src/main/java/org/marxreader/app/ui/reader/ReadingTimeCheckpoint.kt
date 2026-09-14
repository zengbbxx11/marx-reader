package org.marxreader.app.ui

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** A committed interval and its checkpoint advance must survive cancellation together. */
internal class ReadingTimeCheckpoint(private var recordedAt: Long) {
    suspend fun recordUntil(now: Long, write: suspend (Long) -> Unit) {
        val elapsed = now - recordedAt
        if (elapsed <= 0) return
        withContext(NonCancellable) {
            write(elapsed)
            recordedAt = now
        }
    }
}
