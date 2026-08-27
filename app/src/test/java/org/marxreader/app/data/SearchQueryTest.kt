package org.marxreader.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchQueryTest {
    @Test
    fun buildsPrefixQueryForMultipleTerms() {
        assertEquals("\"剩余价值\"* AND \"资本\"*", buildFtsMatchQuery("  剩余价值   资本 "))
    }

    @Test
    fun removesFtsControlCharacters() {
        assertEquals("\"劳动价值\"*", buildFtsMatchQuery("\"劳动*价值\""))
        assertNull(buildFtsMatchQuery("  \"***  "))
    }

    @Test
    fun scopesTermsToTitlesOrBody() {
        assertEquals(
            "(title:\"资本\"* OR chapter_title:\"资本\"*)",
            buildFtsMatchQuery("资本", SearchScope.TITLES)
        )
        assertEquals("content:\"资本\"*", buildFtsMatchQuery("资本", SearchScope.BODY))
    }
}
