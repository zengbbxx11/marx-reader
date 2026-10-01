package org.marxreader.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceIllustrationTest {
    private fun json(asset: String) = """
        {"schemaVersion":2,"authors":[],"books":[{
          "id":"b","authorIds":[],"titleZh":"书","titleEn":"Book","language":"zh","rights":"PUBLIC_DOMAIN",
          "sourceUrl":"https://www.marxists.org/chinese/marx-engels/24/003.htm",
          "chapters":[{"id":"c","title":"章","content":["〔图式1〕"],"footnotes":[{
            "id":"img","marker":"〔图式1〕","content":["源页原图"],"imageAsset":"$asset",
            "references":[{"paragraphIndex":0,"start":0,"end":5}]
          }]}]
        }]}
    """.trimIndent()

    @Test
    fun sourceImagePathSurvivesLazyBookParsing() {
        val book = parseCatalog(json("library/illustrations/capital-v2/003-2.jpg")).book("b")!!
        assertEquals("library/illustrations/capital-v2/003-2.jpg", book.chapters.first().footnotes.first().imageAsset)
    }

    @Test(expected = IllegalArgumentException::class)
    fun illustrationCannotReadOutsideOfflineAssetDirectory() {
        parseCatalog(json("library/illustrations/../../private.jpg"))
    }
}
