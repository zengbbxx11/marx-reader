package org.marxreader.app.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PublicationDatesTest {
    private fun book(id: String, date: String, end: String = ""): Book {
        val value = JSONObject().put("id", id).put("titleZh", id).put("titleEn", id)
            .put("language", "zh").put("sourceUrl", "https://www.marxists.org/chinese/test.htm")
            .put("year", "1848").put("yearType", "WRITTEN")
            .put("publicationDate", date).put("publicationDateEnd", end)
        return parseCatalog("{\"books\":[$value]}").books.single()
    }

    @Test fun unknownDatesNeverBorrowWritingYear() {
        assertEquals("发表时间未注明", book("unknown", "").publicationLabel)
    }

    @Test fun sourceDirectoryOrderOverridesDatesAndSurvivesFiltering() {
        val books = listOf(book("early", "1848"), book("unknown", ""), book("late", "1905"))
            .map { it.copy(authorIds = listOf("marx", "engels")) }
        val authors = listOf(
            Author("marx", "马克思", "Marx", "", "", 0, listOf("late", "unknown", "early")),
            Author("engels", "恩格斯", "Engels", "", "", 0, listOf("unknown", "early", "late"))
        )
        val catalog = LibraryCatalog(authors, books)
        assertEquals(listOf("late", "unknown", "early"), catalog.booksForAuthor("marx").map { it.id })
        assertEquals(listOf("unknown", "early", "late"), catalog.booksForAuthor("engels").map { it.id })
        assertEquals(listOf("late", "early"), catalog.booksForAuthor("marx").filter { it.publicationDate.isNotEmpty() }.map { it.id })
    }

    @Test fun partialPrecisionIsDisplayedHonestly() {
        assertEquals("发表：1905年10月", book("month", "1905-10").publicationLabel)
        assertEquals("发表：1901年—1902年", book("range", "1901", "1902").publicationLabel)
    }

    @Test fun invalidSourceDatesRemainUnknown() {
        for (date in listOf("1900-02-29", "1905-13", "1905-00", "1905-10-32", "1905年", "", "0000")) {
            assertNull(PublicationDate.parse(date))
        }
        assertNotNull(PublicationDate.parse("1904-02-29"))
    }
}
