package org.marxreader.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchQueryTest {
    @Test fun combinesTermsWithoutDependingOnEnhancedFtsSyntax() {
        assertEquals(listOf("剩余价值* 资本*"), buildFtsMatchQueries("  剩余价值   资本 "))
    }

    @Test fun removesFtsControlCharacters() {
        assertEquals(listOf("劳动价值*"), buildFtsMatchQueries("\"劳动*价值\""))
        assertTrue(buildFtsMatchQueries("  \"***  ").isEmpty())
        assertEquals(listOf("OR* NEAR* 资本*"), buildFtsMatchQueries("(OR NEAR:资本)"))
    }

    @Test fun scopesTermsToTitlesOrBody() {
        assertEquals(listOf("title:资本* OR chapter_title:资本*",
            "title:商品* OR chapter_title:商品*"),
            buildFtsMatchQueries("资本 商品", SearchScope.TITLES))
        assertEquals(listOf("content:资本* content:商品*"),
            buildFtsMatchQueries("资本 商品", SearchScope.BODY))
    }
}
