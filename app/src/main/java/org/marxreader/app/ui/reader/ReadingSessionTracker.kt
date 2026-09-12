package org.marxreader.app.ui

import android.os.SystemClock
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.*
import org.marxreader.app.data.LibraryRepository
import org.marxreader.app.data.ReaderPosition

@Composable
internal fun TrackReadingSession(
    repository: LibraryRepository,
    bookId: String,
    position: ReaderPosition
) {
    val latestPosition = rememberUpdatedState(position)
    val activity = LocalActivity.current ?: return
    val lifecycleOwner = activity as? LifecycleOwner ?: return
    val tracker = remember(repository, bookId, activity) {
        ForegroundReadingTracker(repository, bookId) { latestPosition.value }
    }
    DisposableEffect(lifecycleOwner, tracker) {
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) = tracker.resume()
            override fun onPause(owner: LifecycleOwner) = tracker.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) tracker.resume()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            tracker.close()
        }
    }
}

private class ForegroundReadingTracker(
    private val repository: LibraryRepository,
    private val bookId: String,
    private val position: () -> ReaderPosition
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var trackingJob: Job? = null

    fun resume() {
        if (trackingJob?.isActive == true) return
        trackingJob = scope.launch {
            try {
                val start = position()
                val sessionId = repository.startReadingSession(
                    bookId, start.chapterId, start.paragraphIndex
                )
                var lastTick = SystemClock.elapsedRealtime()
                try {
                    while (isActive) {
                        delay(15_000)
                        val now = SystemClock.elapsedRealtime()
                        record(sessionId, now - lastTick)
                        lastTick = now
                    }
                } finally {
                    withContext(NonCancellable) {
                        val now = SystemClock.elapsedRealtime()
                        record(sessionId, now - lastTick)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Reading statistics may fail (disk full, db closed); losing a session beats crashing the reader.
            }
        }
    }

    fun pause() {
        trackingJob?.cancel()
        trackingJob = null
    }

    fun close() {
        pause()
        scope.cancel()
    }

    private suspend fun record(sessionId: Long, elapsedMillis: Long) {
        if (elapsedMillis <= 0) return
        val current = position()
        repository.addReadingTime(
            sessionId = sessionId,
            activeMillis = elapsedMillis,
            chapterId = current.chapterId,
            paragraphIndex = current.paragraphIndex
        )
    }
}
