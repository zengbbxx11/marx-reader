package org.marxreader.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderMemoryPolicyTest {
    @Test fun lowMemoryAndUnknownDevicesKeepTheSmallBudget() {
        assertEquals(600_000, readerPageCacheBudget(null, false))
        assertEquals(600_000, readerPageCacheBudget(128, false))
        assertEquals(600_000, readerPageCacheBudget(512, true))
    }

    @Test fun largerBudgetsRespectMemoryClassBoundaries() {
        assertEquals(900_000, readerPageCacheBudget(129, false))
        assertEquals(900_000, readerPageCacheBudget(255, false))
        assertEquals(1_200_000, readerPageCacheBudget(256, false))
    }
}
