package org.marxreader.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Guards against malformed catalog data: parentId cycles and duplicate toc ids. */
class CatalogSafetyTest {

    @Test(timeout = 5_000)
    fun breadcrumbWalksParentChainOnValidToc() {
        val book = parseCatalog(catalogJson(toc = """
            {"id":"v1","title":"卷一","type":"VOLUME","order":0},
            {"id":"n1","title":"第一章","type":"CHAPTER","order":1,"chapterId":"c1","parentId":"v1"}
        """)).book("b1")!!

        val breadcrumb = book.breadcrumb("c1")

        assertEquals(listOf("v1", "n1"), breadcrumb.map(TocNode::id))
    }

    @Test(timeout = 5_000)
    fun breadcrumbTerminatesOnParentIdCycle() {
        val book = parseCatalog(catalogJson(toc = """
            {"id":"x1","title":"甲","type":"CHAPTER","order":0,"chapterId":"c1","parentId":"x2"},
            {"id":"x2","title":"乙","type":"CHAPTER","order":1,"chapterId":"c1","parentId":"x1"}
        """)).book("b1")!!

        val breadcrumb = book.breadcrumb("c1")

        assertEquals(setOf("x1", "x2"), breadcrumb.map(TocNode::id).toSet())
    }

    @Test(timeout = 5_000)
    fun parseCatalogDropsDuplicateTocIdsKeepingFirst() {
        val book = parseCatalog(catalogJson(toc = """
            {"id":"d1","title":"重复","type":"CHAPTER","order":0,"chapterId":"c1"},
            {"id":"d1","title":"重复二","type":"CHAPTER","order":1,"chapterId":"c1"},
            {"id":"d2","title":"第二节","type":"SECTION","order":2,"chapterId":"c1","parentId":"d1"}
        """)).book("b1")!!

        assertEquals(listOf("d1", "d2"), book.toc.map(TocNode::id))
        assertEquals("重复", book.toc.first().title)
    }
}

private fun catalogJson(toc: String): String = """
    {
      "schemaVersion": 2,
      "authors": [{"id":"a1","nameZh":"作者","nameEn":"Author","years":"","color":"7A2024"}],
      "books": [{
        "id":"b1","authorIds":["a1"],
        "titleZh":"书名","titleEn":"Title",
        "language":"ZH","rights":"PUBLIC_DOMAIN",
        "year":"","yearType":"","yearBasis":"",
        "sourceUrl":"https://www.marxists.org/example",
        "chapters":[{"id":"c1","title":"第一章","level":1,"content":["第一段。","第二段。"]}],
        "toc":[$toc]
      }]
    }
""".trimIndent()
