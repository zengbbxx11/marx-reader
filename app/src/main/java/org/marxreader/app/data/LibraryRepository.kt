package org.marxreader.app.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import org.json.JSONObject

data class SearchIndexState(
    val building: Boolean = false,
    val current: Int = 0,
    val total: Int = 0,
    val error: String? = null
)

data class ImportedPack(
    val fileName: String,
    val packId: String,
    val title: String,
    val version: String,
    val bookIds: List<String>,
    val sizeBytes: Long,
    val importedAt: Long
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryRepository(private val context: Context) {
    private val database = ReaderDatabase(context)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val mutableCatalog = MutableStateFlow(LibraryCatalog(emptyList(), emptyList()))
    private val loadedBooks = object : LinkedHashMap<String, Book>(6, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Book>?): Boolean = size > 4
    }
    private val importedBookFiles = mutableMapOf<String, File>()
    private val mutableSearchIndexState = MutableStateFlow(SearchIndexState())
    private val searchIndexMutex = Mutex()
    private val mutableImportedPacks = MutableStateFlow<List<ImportedPack>>(emptyList())
    @Volatile private var catalogFingerprint = ""
    @Volatile private var searchReadyFor = ""
    val catalog = mutableCatalog.asStateFlow()
    val searchIndexState = mutableSearchIndexState.asStateFlow()
    val importedPacks = mutableImportedPacks.asStateFlow()

    suspend fun initialize() = withContext(Dispatchers.IO) {
        reload()
    }

    suspend fun reload() = withContext(Dispatchers.IO) {
        val bundledJson = context.assets.open("library/catalog.json").bufferedReader().use { it.readText() }
        val bundled = parseCatalog(bundledJson)
        val packsDir = File(context.filesDir, "packs").apply { mkdirs() }
        val importedCatalogs = mutableListOf<LibraryCatalog>()
        val packItems = mutableListOf<ImportedPack>()
        val fingerprints = mutableListOf(bundledJson)
        synchronized(importedBookFiles) { importedBookFiles.clear() }
        packsDir.listFiles { file -> file.extension == "json" }?.sortedBy { it.name }?.forEach { file ->
            runCatching {
                val json = file.readText(Charsets.UTF_8)
                val imported = parseCatalog(json)
                importedCatalogs += imported
                fingerprints += json
                imported.books.forEach { synchronized(importedBookFiles) { importedBookFiles[it.id] = file } }
                packItems += packMetadata(file, json, imported)
            }.onFailure {
                packItems += ImportedPack(
                    file.name, "invalid-${file.nameWithoutExtension}", "损坏的内容包", "无法读取",
                    emptyList(), file.length(), file.lastModified()
                )
            }
        }

        val catalogs = listOf(bundled) + importedCatalogs
        val authors = catalogs.flatMap { it.authors }.associateBy { it.id }.values.toList()
        val books = catalogs.flatMap { catalog ->
            catalog.books.map { book ->
                if (synchronized(importedBookFiles) { importedBookFiles.containsKey(book.id) }) {
                    book.copy(chapters = book.chapters.map { it.copy(paragraphs = emptyList()) })
                } else book
            }
        }.associateBy { it.id }.values.toList()
        val merged = LibraryCatalog(authors, books.sortedBy { it.displayTitle })
        synchronized(loadedBooks) { loadedBooks.clear() }
        mutableCatalog.value = merged
        mutableImportedPacks.value = packItems.sortedBy { it.title }
        catalogFingerprint = sha256(fingerprints.joinToString("\n"))
    }

    suspend fun loadBook(bookId: String): Book? = withContext(Dispatchers.IO) {
        synchronized(loadedBooks) { loadedBooks[bookId] } ?: readBook(bookId)?.also {
            synchronized(loadedBooks) { loadedBooks[bookId] = it }
        }
    }

    suspend fun importPack(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val input = context.contentResolver.openInputStream(uri) ?: error("无法读取内容包")
            val bytes = input.use { stream ->
                ZipInputStream(stream.buffered()).use { zip ->
                    var libraryBytes: ByteArray? = null
                    var entries = 0
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        entries++
                        require(entries <= 16) { "内容包文件过多" }
                        require(!entry.name.contains("..") && !entry.name.startsWith("/") && !entry.name.contains("\\")) {
                            "内容包路径不安全"
                        }
                        if (entry.name == "library.json") {
                            libraryBytes = zip.readBytesLimited(50 * 1024 * 1024)
                        }
                        zip.closeEntry()
                    }
                    libraryBytes ?: error("内容包缺少 library.json")
                }
            }
            val json = bytes.toString(Charsets.UTF_8)
            val parsed = parseCatalog(json)
            require(parsed.books.isNotEmpty()) { "内容包没有作品" }
            require(parsed.books.all { it.language == Language.ZH }) { "当前版本只支持中文内容包" }
            val root = JSONObject(json)
            val packId = root.optString("packId").trim().ifEmpty { "legacy-${sha256(json).take(16)}" }
            val existing = mutableImportedPacks.value.firstOrNull { it.packId == packId }
            val existingIds = existing?.bookIds.orEmpty().toSet()
            val conflicts = parsed.books.map { it.id }.toSet()
                .intersect(mutableCatalog.value.books.map { it.id }.toSet() - existingIds)
            require(conflicts.isEmpty()) {
                "作品 ID 与现有书库冲突：${conflicts.take(5).joinToString("、")}${if (conflicts.size > 5) "…" else ""}"
            }
            val packsDir = File(context.filesDir, "packs").apply { mkdirs() }
            val destination = File(packsDir, "${sha256(packId)}.json")
            val temporary = File(packsDir, ".${destination.name}.tmp")
            temporary.writeText(json, Charsets.UTF_8)
            try {
                runCatching {
                    Files.move(
                        temporary.toPath(), destination.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
                    )
                }.getOrElse {
                    Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                temporary.delete()
            }
            existing?.takeIf { it.fileName != destination.name }?.let {
                File(packsDir, it.fileName).takeIf(File::isFile)?.delete()
            }
            reload()
            if (existing == null) "已导入 ${parsed.books.size} 部作品" else "已更新 ${parsed.books.size} 部作品"
        }
    }

    suspend fun deleteImportedPack(fileName: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val pack = mutableImportedPacks.value.firstOrNull { it.fileName == fileName } ?: error("内容包不存在")
            val packsDir = File(context.filesDir, "packs").canonicalFile
            val target = File(packsDir, fileName).canonicalFile
            require(target.parentFile == packsDir && target.isFile) { "内容包路径无效" }
            require(target.delete()) { "无法删除内容包" }
            reload()
            "已删除“${pack.title}”"
        }
    }

    fun progress(bookId: String) = database.progress(bookId)
    fun allProgress() = database.allProgress()
    fun chapterProgress(bookId: String) = database.chapterProgress(bookId)
    fun saveProgress(bookId: String, chapterId: String, paragraphIndex: Int) {
        ioScope.launch { database.saveProgress(bookId, chapterId, paragraphIndex) }
    }
    fun toggleBookmark(bookId: String, chapterId: String, paragraphIndex: Int, excerpt: String) {
        ioScope.launch { database.toggleBookmark(bookId, chapterId, paragraphIndex, excerpt) }
    }
    fun bookmarks() = database.bookmarks()
    fun saveNote(bookId: String, chapterId: String, paragraphIndex: Int, excerpt: String, text: String) {
        ioScope.launch { database.saveNote(bookId, chapterId, paragraphIndex, excerpt, text) }
    }
    fun notes() = database.notes()
    suspend fun deleteBookmark(id: Long) = withContext(Dispatchers.IO) { database.deleteBookmark(id) }
    suspend fun updateNote(id: Long, text: String) = withContext(Dispatchers.IO) { database.updateNote(id, text) }
    suspend fun deleteNote(id: Long) = withContext(Dispatchers.IO) { database.deleteNote(id) }
    suspend fun deleteProgress(bookId: String) = withContext(Dispatchers.IO) { database.deleteProgress(bookId) }
    suspend fun clearProgress() = withContext(Dispatchers.IO) { database.clearProgress() }
    suspend fun clearBookmarks() = withContext(Dispatchers.IO) { database.clearBookmarks() }
    suspend fun clearNotes() = withContext(Dispatchers.IO) { database.clearNotes() }

    suspend fun exportBackup(uri: Uri, settings: ReaderSettings) = withContext(Dispatchers.IO) {
        val snapshot = userDataSnapshot(settings)
        context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
            it.write(UserDataBackup.toJson(snapshot))
        } ?: error("无法写入备份文件")
    }

    suspend fun exportMarkdown(uri: Uri) = withContext(Dispatchers.IO) {
        val snapshot = userDataSnapshot(ReaderSettings())
        context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
            it.write(UserDataBackup.toMarkdown(snapshot, mutableCatalog.value))
        } ?: error("无法写入笔记文件")
    }

    suspend fun restoreBackup(uri: Uri): ReaderSettings = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytesLimited(UserDataBackup.MAX_BACKUP_BYTES)
        } ?: error("无法读取备份文件")
        val snapshot = UserDataBackup.fromJson(bytes.toString(Charsets.UTF_8))
        database.restoreUserData(snapshot)
        snapshot.settings
    }
    suspend fun search(
        query: String,
        scope: SearchScope = SearchScope.ALL,
        authorId: String? = null
    ): List<SearchHit> = withContext(Dispatchers.IO) {
        searchIndexMutex.withLock {
            if (searchReadyFor != catalogFingerprint) {
                val books = mutableCatalog.value.books
                val targetFingerprint = catalogFingerprint
                mutableSearchIndexState.value = SearchIndexState(building = true, total = books.size)
                try {
                    database.rebuildSearchIndex(targetFingerprint, sequence {
                        books.forEachIndexed { index, metadata ->
                            readBook(metadata.id)?.let { yield(it) }
                            mutableSearchIndexState.value = SearchIndexState(
                                building = true,
                                current = index + 1,
                                total = books.size
                            )
                        }
                    })
                    searchReadyFor = targetFingerprint
                    mutableSearchIndexState.value = SearchIndexState(current = books.size, total = books.size)
                } catch (error: Throwable) {
                    mutableSearchIndexState.value = SearchIndexState(
                        total = books.size,
                        error = error.message ?: "搜索索引建立失败"
                    )
                    throw error
                }
            }
        }
        val bookIds = authorId?.let { id -> mutableCatalog.value.booksForAuthor(id).map { it.id }.toSet() }
        database.search(query, scope, bookIds)
    }

    private fun readBook(bookId: String): Book? {
        val imported = synchronized(importedBookFiles) { importedBookFiles[bookId] }
        val json = if (imported != null) {
            imported.readText(Charsets.UTF_8)
        } else {
            runCatching {
                context.assets.open("library/books/$bookId.json").bufferedReader().use { it.readText() }
            }.getOrNull() ?: return null
        }
        return parseCatalog(json).book(bookId)
    }

    private fun userDataSnapshot(settings: ReaderSettings) = UserDataSnapshot(
        progress = database.allProgress().values.toList(),
        chapterProgress = database.allChapterProgress(),
        bookmarks = database.bookmarks(),
        notes = database.notes(),
        settings = settings
    )

    private fun packMetadata(file: File, json: String, catalog: LibraryCatalog): ImportedPack {
        val root = JSONObject(json)
        val fallbackId = "legacy-${sha256(json).take(16)}"
        return ImportedPack(
            fileName = file.name,
            packId = root.optString("packId").trim().ifEmpty { fallbackId },
            title = root.optString("packTitle").trim().ifEmpty { "旧版内容包" },
            version = root.optString("packVersion", root.optString("version", "1")).trim().ifEmpty { "1" },
            bookIds = catalog.books.map { it.id },
            sizeBytes = file.length(),
            importedAt = file.lastModified()
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun ZipInputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "内容包超过 50 MB 安全限制" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "备份文件超过 5 MB 限制" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
