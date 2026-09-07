package org.marxreader.app.data

import kotlinx.coroutines.CancellationException

/** Cancellation belongs to the caller's lifecycle, never to a user-facing error. */
inline fun <T> readerOperation(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}
