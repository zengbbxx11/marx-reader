package org.marxreader.app.ui

import org.junit.Assert.*
import org.junit.Test

class ReaderHitTestingTest {
    @Test fun nearSuperscriptAtPageEdgeIsStillAnAnnotationTarget() {
        assertNotNull(readerHitDistance(355f, 32f, 342f, 20f, 350f, 40f, 8f))
        assertEquals(0f, readerHitDistance(347f, 28f, 342f, 20f, 350f, 40f, 8f)!!, 0f)
    }

    @Test fun ordinaryTextOutsideTheHitSlopIsNotHijacked() {
        assertNull(readerHitDistance(330f, 28f, 342f, 20f, 350f, 40f, 8f))
        assertNull(readerHitDistance(347f, 60f, 342f, 20f, 350f, 40f, 8f))
    }

    @Test fun directHitWinsOverExpandedAreaOfAnAdjacentMarker() {
        val adjacent = readerHitDistance(114f, 25f, 100f, 20f, 108f, 40f, 8f)!!
        val direct = readerHitDistance(114f, 25f, 112f, 20f, 120f, 40f, 8f)!!
        assertTrue(direct < adjacent)
    }
}
