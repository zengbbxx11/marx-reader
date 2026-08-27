package org.marxreader.app.data

import org.json.JSONArray
import org.json.JSONObject

enum class Language { ZH, EN }
enum class RightsStatus { PUBLIC_DOMAIN, CC_BY_SA, PERMISSION_REQUIRED, UNKNOWN }
enum class TocNodeType { VOLUME, PART, CHAPTER, SECTION, PREFACE, APPENDIX }

data class Author(
    val id: String,
    val nameZh: String,
    val nameEn: String,
    val years: String,
    val description: String,
    val color: Long
)

data class Chapter(
    val id: String,
    val title: String,
    val level: Int,
    val paragraphs: List<String>,
    val footnotes: List<Footnote> = emptyList(),
    val declaredParagraphCount: Int = paragraphs.size
)

data class FootnoteReference(
    val paragraphIndex: Int,
    val start: Int,
    val end: Int
)

data class Footnote(
    val id: String,
    val marker: String,
    val content: List<String>,
    val references: List<FootnoteReference>
) {
    val displayContent: String get() = content.joinToString("\n\n")
}

data class TocNode(
    val id: String,
    val parentId: String?,
    val title: String,
    val type: TocNodeType,
    val order: Int,
    val chapterId: String?,
    val paragraphIndex: Int = 0,
    val counterpartKey: String? = null,
    val level: Int = 1
)

data class Book(
    val id: String,
    val authorIds: List<String>,
    val seriesId: String?,
    val titleZh: String,
    val titleEn: String,
    val language: Language,
    val category: String,
    val year: String,
    val sourceUrl: String,
    val sourceCredit: String,
    val translator: String,
    val rights: RightsStatus,
    val description: String,
    val chapters: List<Chapter>,
    val toc: List<TocNode>
) {
    val displayTitle: String get() = if (language == Language.ZH) titleZh else titleEn
    val counterpartKey: String get() = seriesId ?: id.substringBeforeLast("-")
    val paragraphCount: Int get() = chapters.sumOf { it.declaredParagraphCount }
    val hasContent: Boolean get() = chapters.any { it.paragraphs.isNotEmpty() }

    fun breadcrumb(chapterId: String, paragraphIndex: Int = 0): List<TocNode> {
        val candidates = toc.filter { it.chapterId == chapterId && it.paragraphIndex <= paragraphIndex }
        val leaf = candidates.maxByOrNull { it.paragraphIndex }
            ?: toc.firstOrNull { it.chapterId == chapterId }
            ?: return emptyList()
        val byId = toc.associateBy { it.id }
        return generateSequence(leaf) { node -> node.parentId?.let(byId::get) }.toList().asReversed()
    }
}

data class LibraryCatalog(
    val authors: List<Author>,
    val books: List<Book>
) {
    fun author(id: String) = authors.firstOrNull { it.id == id }
    fun book(id: String) = books.firstOrNull { it.id == id }
    fun booksForAuthor(id: String) = books.filter { id in it.authorIds }
}

data class ReadingProgress(
    val bookId: String,
    val chapterId: String,
    val paragraphIndex: Int,
    val updatedAt: Long
)

data class ChapterReadingProgress(
    val bookId: String,
    val chapterId: String,
    val paragraphIndex: Int,
    val updatedAt: Long
)

data class Bookmark(
    val id: Long,
    val bookId: String,
    val chapterId: String,
    val paragraphIndex: Int,
    val excerpt: String,
    val createdAt: Long
)

data class Note(
    val id: Long,
    val bookId: String,
    val chapterId: String,
    val paragraphIndex: Int,
    val excerpt: String,
    val text: String,
    val updatedAt: Long
)

data class SearchHit(
    val bookId: String,
    val chapterId: String,
    val paragraphIndex: Int,
    val title: String,
    val chapterTitle: String,
    val excerpt: String
)

enum class SearchScope { ALL, TITLES, BODY }

fun parseCatalog(json: String): LibraryCatalog {
    val root = JSONObject(json)
    require(root.optInt("schemaVersion", 1) in 1..2) { "不支持的内容包版本" }

    val authors = root.optJSONArray("authors").orEmpty().mapObjects { value ->
        Author(
            id = value.requireString("id"),
            nameZh = value.requireString("nameZh"),
            nameEn = value.requireString("nameEn"),
            years = value.optString("years"),
            description = value.optString("description"),
            color = value.optString("color", "7A2024").removePrefix("#").toLong(16)
        )
    }

    val books = root.optJSONArray("books").orEmpty().mapObjects { value ->
        val rights = runCatching {
            RightsStatus.valueOf(value.optString("rights", "UNKNOWN"))
        }.getOrDefault(RightsStatus.UNKNOWN)
        val chapters = value.optJSONArray("chapters").orEmpty().mapObjects { chapter ->
            val paragraphs = chapter.optJSONArray("content").orEmpty().mapStrings()
            val footnotes = chapter.optJSONArray("footnotes").orEmpty().mapObjects { footnote ->
                Footnote(
                    id = footnote.requireString("id"),
                    marker = footnote.requireString("marker"),
                    content = footnote.optJSONArray("content").orEmpty().mapStrings(),
                    references = footnote.optJSONArray("references").orEmpty().mapObjects { reference ->
                        FootnoteReference(
                            paragraphIndex = reference.optInt("paragraphIndex", -1),
                            start = reference.optInt("start", -1),
                            end = reference.optInt("end", -1)
                        )
                    }.filter { reference ->
                        reference.paragraphIndex in paragraphs.indices &&
                            reference.start >= 0 &&
                            reference.end > reference.start &&
                            reference.end <= paragraphs[reference.paragraphIndex].length
                    }
                )
            }.filter { it.content.isNotEmpty() && it.references.isNotEmpty() }
            Chapter(
                id = chapter.requireString("id"),
                title = chapter.requireString("title"),
                level = chapter.optInt("level", 1).coerceIn(1, 4),
                paragraphs = paragraphs,
                footnotes = footnotes,
                declaredParagraphCount = chapter.optInt("paragraphCount", paragraphs.size)
            )
        }
        require(rights in setOf(RightsStatus.PUBLIC_DOMAIN, RightsStatus.CC_BY_SA) ||
            chapters.all { it.paragraphs.isEmpty() }) {
            "${value.optString("id")}: 未获再分发许可的作品不能携带正文"
        }
        Book(
            id = value.requireString("id"),
            authorIds = value.optJSONArray("authorIds").orEmpty().mapStrings(),
            seriesId = value.optionalString("seriesId"),
            titleZh = value.requireString("titleZh"),
            titleEn = value.requireString("titleEn"),
            language = Language.valueOf(value.requireString("language").uppercase()),
            category = value.optString("category", "著作"),
            year = value.optString("year"),
            sourceUrl = value.requireString("sourceUrl").also {
                require(it.startsWith("https://www.marxists.org/")) { "只接受经审核的 MIA 来源" }
            },
            sourceCredit = value.optString("sourceCredit", "Marxists Internet Archive"),
            translator = value.optString("translator"),
            rights = rights,
            description = value.optString("description"),
            chapters = chapters,
            toc = value.optJSONArray("toc").orEmpty().mapObjects { node ->
                TocNode(
                    id = node.requireString("id"),
                    parentId = node.optionalString("parentId"),
                    title = node.requireString("title"),
                    type = runCatching { TocNodeType.valueOf(node.optString("type", "CHAPTER")) }
                        .getOrDefault(TocNodeType.CHAPTER),
                    order = node.optInt("order"),
                    chapterId = node.optionalString("chapterId"),
                    paragraphIndex = node.optInt("paragraphIndex", 0),
                    counterpartKey = node.optionalString("counterpartKey"),
                    level = node.optInt("level", 1).coerceIn(1, 4)
                )
            }.ifEmpty {
                chapters.mapIndexed { index, chapter ->
                    TocNode("toc-${chapter.id}", null, chapter.title, TocNodeType.CHAPTER, index, chapter.id)
                }
            }
        )
    }
    return LibraryCatalog(authors, books)
}

private fun JSONObject.requireString(key: String): String =
    getString(key).trim().also { require(it.isNotEmpty()) { "$key 不能为空" } }

private fun JSONObject.optionalString(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }

private fun JSONArray?.orEmpty(): JSONArray = this ?: JSONArray()

private inline fun <T> JSONArray.mapObjects(block: (JSONObject) -> T): List<T> =
    (0 until length()).map { block(getJSONObject(it)) }

private fun JSONArray.mapStrings(): List<String> =
    (0 until length()).map { getString(it).trim() }.filter { it.isNotEmpty() }
