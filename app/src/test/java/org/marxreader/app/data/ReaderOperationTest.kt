package org.marxreader.app.data

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ReaderOperationTest {
    @Test
    fun cancellationEscapesWithoutBeingReportedAsAnOperationFailure() {
        val cancellation = CancellationException("new query")
        var failureReported = false
        try {
            readerOperation<Unit> { throw cancellation }.onFailure { failureReported = true }
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
        assertFalse(failureReported)
    }

    @Test
    fun storageFailureIsAvailableForUserFeedback() {
        val error = IllegalStateException("storage unavailable")
        assertSame(error, readerOperation<Unit> { throw error }.exceptionOrNull())
    }
}
