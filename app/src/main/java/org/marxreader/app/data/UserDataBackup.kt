package org.marxreader.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

data class UserDataSnapshot(
    val progress: List<ReadingProgress>,
    val chapterProgress: List<ChapterReadingProgress>,
    val bookmarks: List<Bookmark>,
    val notes: List<Note>,
    val settings: ReaderSettings
)

object UserDataBackup {
    const val MAX_BACKUP_BYTES = 5 * 1024 * 1024

    fun toJson(snapshot: UserDataSnapshot): String = JSONObject().apply {
        put("schemaVersion", 1)
        put("appVersion", "0.3.1")
        put("exportedAt", System.currentTimeMillis())
        put("progress", JSONArray().apply { snapshot.progress.forEach { put(it.toJson()) } })
        put("chapterProgress", JSONArray().apply { snapshot.chapterProgress.forEach { put(it.toJson()) } })
        put("bookmarks", JSONArray().apply { snapshot.bookmarks.forEach { put(it.toJson()) } })
        put("notes", JSONArray().apply { snapshot.notes.forEach { put(it.toJson()) } })
        put("settings", snapshot.settings.toJson())
    }.toString(2)

    fun fromJson(json: String): UserDataSnapshot {
        require(json.toByteArray().size <= MAX_BACKUP_BYTES) { "备份文件超过 5 MB 限制" }
        val root = JSONObject(json)
        require(root.optInt("schemaVersion", -1) == 1) { "不支持的备份文件版本" }
        return UserDataSnapshot(
            progress = root.optJSONArray("progress").objects().map {
                ReadingProgress(it.required("bookId"), it.required("chapterId"), it.safeIndex(), it.optLong("updatedAt"))
            },
            chapterProgress = root.optJSONArray("chapterProgress").objects().map {
                ChapterReadingProgress(it.required("bookId"), it.required("chapterId"), it.safeIndex(), it.optLong("updatedAt"))
            },
            bookmarks = root.optJSONArray("bookmarks").objects().mapIndexed { index, it ->
                Bookmark(index.toLong(), it.required("bookId"), it.required("chapterId"), it.safeIndex(),
                    it.required("excerpt").take(180), it.optLong("createdAt"))
            },
            notes = root.optJSONArray("notes").objects().mapIndexed { index, it ->
                Note(index.toLong(), it.required("bookId"), it.required("chapterId"), it.safeIndex(),
                    it.required("excerpt").take(180), it.required("text"), it.optLong("updatedAt"))
            },
            settings = root.optJSONObject("settings")?.toSettings() ?: ReaderSettings()
        )
    }

    fun toMarkdown(snapshot: UserDataSnapshot, catalog: LibraryCatalog): String = buildString {
        appendLine("# 马列原典笔记")
        appendLine()
        appendLine("导出时间：${Instant.now()}")
        appendLine()
        snapshot.notes.forEach { note ->
            val book = catalog.book(note.bookId)
            val chapter = book?.chapters?.firstOrNull { it.id == note.chapterId }
            appendLine("## ${escapeMarkdown(book?.displayTitle ?: note.bookId)}")
            appendLine()
            appendLine("**章节：** ${escapeMarkdown(chapter?.title ?: note.chapterId)}")
            appendLine()
            appendLine("> ${note.excerpt.replace("\n", " ").replace("\r", " ")}")
            appendLine()
            appendLine(note.text)
            appendLine()
            appendLine("_更新时间：${Instant.ofEpochMilli(note.updatedAt)}_")
            appendLine()
        }
    }

    private fun ReadingProgress.toJson() = JSONObject().apply {
        put("bookId", bookId); put("chapterId", chapterId); put("paragraphIndex", paragraphIndex); put("updatedAt", updatedAt)
    }
    private fun ChapterReadingProgress.toJson() = JSONObject().apply {
        put("bookId", bookId); put("chapterId", chapterId); put("paragraphIndex", paragraphIndex); put("updatedAt", updatedAt)
    }
    private fun Bookmark.toJson() = JSONObject().apply {
        put("bookId", bookId); put("chapterId", chapterId); put("paragraphIndex", paragraphIndex); put("excerpt", excerpt); put("createdAt", createdAt)
    }
    private fun Note.toJson() = JSONObject().apply {
        put("bookId", bookId); put("chapterId", chapterId); put("paragraphIndex", paragraphIndex); put("excerpt", excerpt); put("text", text); put("updatedAt", updatedAt)
    }
    private fun ReaderSettings.toJson() = JSONObject().apply {
        put("fontSize", fontSize); put("lineHeight", lineHeight); put("horizontalPadding", horizontalPadding)
        put("theme", theme.name); put("mode", mode.name); put("keepScreenOn", keepScreenOn); put("firstLineIndent", firstLineIndent)
    }
    private fun JSONObject.toSettings() = ReaderSettings(
        fontSize = optDouble("fontSize", 20.0).toFloat().coerceIn(15f, 32f),
        lineHeight = optDouble("lineHeight", 1.75).toFloat().coerceIn(1.3f, 2.2f),
        horizontalPadding = optInt("horizontalPadding", 22).coerceIn(12, 42),
        theme = runCatching { ReaderTheme.valueOf(optString("theme", "PAPER")) }.getOrDefault(ReaderTheme.PAPER),
        mode = runCatching { ReadingMode.valueOf(optString("mode", "PAGE")) }.getOrDefault(ReadingMode.PAGE),
        keepScreenOn = optBoolean("keepScreenOn", false),
        firstLineIndent = optBoolean("firstLineIndent", true)
    )
    private fun JSONObject.required(key: String) = getString(key).trim().also { require(it.isNotEmpty()) { "$key 不能为空" } }
    private fun JSONObject.safeIndex() = optInt("paragraphIndex", 0).coerceAtLeast(0)
    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
    private fun escapeMarkdown(value: String) = value.replace("#", "\\#").replace("*", "\\*")
}
