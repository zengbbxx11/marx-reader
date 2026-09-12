# MarxReader 优化任务交接文档（批次 C / D + 最终验证）

> 2026-09-12 后续实施状态：用户已确认审查计划，本轮代码改动与验证以 [OPTIMIZATION_REPORT.md](OPTIMIZATION_REPORT.md) 为准。下文待办和历史实现说明保留供参考，部分已由本轮修复更新；设备回归仍待连接设备。


> 交接日期：2026-09-12。本文档自包含，执行前请完整阅读「全局规则」。
> 项目：D:\App，MarxReader「马列原典」离线阅读器，Kotlin 2.0 + Jetpack Compose，单 `:app` 模块，无网络无第三方运行时依赖。

## 0. 全局规则（必须遵守）

1. **禁止修改任何 UI 风格、页面布局、交互逻辑、视觉效果**。所有改动仅限底层。
2. **禁止 git commit / push**，只改代码。
3. **禁止超范围重构**：不做包目录迁移、不改 JSON 数据结构、不引入新生产依赖。
4. **若本文档描述与实际代码不一致，以当前代码为准**，不要强行按文档改；拿不准就跳过并记录原因。
5. 每完成一个编号项就跑一次测试（命令见第 4 节），通过再继续。
6. 新增代码注释用英文（与代码库现有风格一致）。

## 1. 已完成的工作（不要重做）

批次 A（崩溃防护）与批次 B（可靠性）已完成并通过 47 项单元测试，**改动尚未提交 git**。已改文件：
- `app/src/main/java/org/marxreader/app/ui/reader/ReadingSessionTracker.kt`（协程异常兜底）
- `app/src/main/java/org/marxreader/app/data/LibraryRepository.kt`（resolveNotes 降级、readBook 坏文件降级、FTS 跳过坏书、saveProgressNow、WriteError(带序号)、progressRevision 拆分、write(progressTick) 分流）
- `app/src/main/java/org/marxreader/app/ui/reader/ReadingScreen.kt`（!! 消除、TOC 查询 readerOperation 包裹+combine 收集、退出落库 runBlocking+withTimeoutOrNull(300)、恢复等待 withTimeoutOrNull(2_000)、去组合层 revision 收集）
- `app/src/main/java/org/marxreader/app/ui/ReaderApp.kt`（!! 消除、3 处进度读取 readerOperation 包裹）
- `app/src/main/java/org/marxreader/app/ui/search/SearchScreen.kt`（!! 消除）
- `app/src/main/java/org/marxreader/app/ui/ReaderPagination.kt`（幻影行护栏 startChar>=styled.length break、endChar coerceIn）
- `app/src/main/java/org/marxreader/app/data/Models.kt`（breadcrumb 防环、toc distinctBy）
- `app/src/main/java/org/marxreader/app/ui/components/TocTree.kt`（三处遍历防环）
- `app/src/main/java/org/marxreader/app/ui/reader/ReaderViewModel.kt`（persistPositionNow、progressOnly 传递、搜索 checkActive）
- `app/src/main/java/org/marxreader/app/ui/reader/ReaderSearch.kt`（checkActive 参数，默认值 `{}`）
- `app/build.gradle.kts`（新增 `testImplementation("org.json:json:20240303")`，仅测试 classpath，勿删）
- 新增测试：`app/src/test/java/org/marxreader/app/data/CatalogSafetyTest.kt`（3 项）、`app/src/androidTest/java/org/marxreader/app/ProgressRevisionTest.kt`（3 项，待模拟器运行）

## 2. 待执行：批次 C（性能，3 项）

### C-11 derivedStateOf 每帧重建修复

文件：`app/src/main/java/org/marxreader/app/ui/reader/ReadingScreen.kt`
找类似代码（行号可能有偏移，按代码找）：

```kotlin
val visibleParagraph = visibleSourcePosition.first
val readingProgressValue by remember(book, chapter.id, visibleSourcePosition, readingCompleted) {
    derivedStateOf {
        book.readingProgress(
            ReaderPosition(
                bookId = book.id,
                chapterId = chapter.id,
                paragraphIndex = visibleSourcePosition.first,
                characterOffset = visibleSourcePosition.second,
                ...
```

问题：`visibleSourcePosition`（一个 Pair）在滚动时几乎每帧都变，被用作 remember 的 key → `derivedStateOf` 对象连同 `ReaderPosition` 每帧重建。

修法：remember 的 key 去掉 `visibleSourcePosition`，只留 `(book, chapter.id, readingCompleted)`；derivedStateOf 块内部照旧读 `visibleSourcePosition.first/.second`（derived 块内的状态读取会被自动跟踪，语义不变）。
注意：`visibleParagraph` 这行若别处有使用则保留不动；本项只改 key。

### C-12 阅读统计 SQL 聚合

文件：`app/src/main/java/org/marxreader/app/data/ReaderDatabase.kt`（`readingSessions()` / `readingStatistics()`，约 489-511 行附近）+ `app/src/main/java/org/marxreader/app/data/ReadingStatistics.kt`

问题：`readingSessions()` 无 LIMIT 全表加载所有会话行，`readingStatistics()` 基于它计算；每次退出阅读都会插一行，随年月增长变慢。`ShelfViewModel` 在书架可见时每次 dataRevision 变化都重跑。

要求：
1. 先读 `ReadingStatistics.kt` 和 `app/src/test/java/org/marxreader/app/data/ReadingStatisticsTest.kt`，**弄清现有语义**（today/7天/总计、连续天数 streak 的日期边界按本地时区如何算、每本书统计字段）。
2. 把计算下推到 SQL：按天 `SUM(active_millis) GROUP BY`（streak/today/7d 用），按 `book_id GROUP BY`（每本书用），全部用 `?` 参数化，禁止字符串拼接用户输入。
3. **结果必须与现有实现语义完全一致**，以 `ReadingStatisticsTest` 为准绳。若纯函数层（ReadingStatistics.kt）签名需要跟着改，允许改，但测试断言内容不得删减。
4. 如 streak 逻辑无法完全下推，允许保留小结果集查询（按天聚合后的行数远小于全部行数），目标是消除无界全表行加载。

### C-13 大章节分页缓存按设备内存分档

文件：`app/src/main/java/org/marxreader/app/ui/reader/ChapterPageCache.kt`（`WeightedReaderCache<Key, List<ReaderPage>>(3, 600_000)`）+ `ReadingScreen.kt`（预取守卫 `characterCount <= 600_000`，约 172 行附近）

背景：权重上限 600K 字符 = 预取守卫值。超大章节（资本论长章 ~590K）会占满整个权重预算，把正在读的章节页挤出缓存；单章超过上限时直接不可缓存（`WeightedReaderCache.put` 中 `if (valueWeight > maxWeight) return`）。

要求（用户明确：**不要简单翻倍，按设备内存动态分档**）：
1. 内存估算依据：ReaderPage.text 为 UTF-16 String，约 2 字节/字符 + span/对象头开销；1.2M 字符 ≈ 2.4MB 文本 + 开销 ≈ 4-5MB 总占用（缓存满载时）。
2. 用 `ActivityManager`（`context.getSystemService(ActivityManager::class.java)`）分档：
   - `isLowRamDevice == true` 或 `memoryClass <= 128` → 600_000（维持现状）
   - `memoryClass >= 256` → 1_200_000
   - 其他 → 900_000
   - 取不到 ActivityManager 时默认 600_000。
3. `ChapterPageCache` 改为构造时接收权重上限（`ChapterPageCache(maxWeightChars: Int)`），`ReadingScreen` 里 `remember(book.id) { ChapterPageCache() }` 的调用点改为传入分档值（LocalContext 取 ActivityManager）。`maxEntries = 3` 不变。
4. 预取守卫的 `600_000` 改为同一个分档值（把分档逻辑提成一个小函数，两处共用）。
5. 不得改变预取行为本身（仍是预取下一章、delay(150) 等）。

## 3. 待执行：批次 D（杂项，2 项）

### D-14 删除无用 ProGuard 规则
文件：`app/proguard-rules.pro`，删除 `-keep class org.json.** { *; }` 一行（org.json 属于 Android 平台，不参与混淆，规则无效）。

### D-15 内存告警时收缩书缓存
1. `LibraryRepository.kt` 新增：
```kotlin
/** Drop all but the most recently used books when the system signals memory pressure. */
fun trimLoadedBooks(keep: Int = 1) {
    synchronized(loadedBooks) {
        val iterator = loadedBooks.entries.iterator()
        while (loadedBooks.size > keep && iterator.hasNext()) {
            iterator.next(); iterator.remove()
        }
    }
}
```
（`loadedBooks` 是 accessOrder 的 LinkedHashMap，iterator 顺序即最旧优先，与现有 `synchronized(loadedBooks)` 访问模式一致。）
2. `ReaderApplication.kt` 覆写 `onTrimMemory`：`level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW` 时调用 `repository.trimLoadedBooks(1)`。注意 repository 是 lazy 的——SQLiteOpenHelper 构造不打开数据库，提前构造无 IO，可接受。
3. 不要在 onTrimMemory 里做任何磁盘 IO。

## 4. 验证命令（每项改完都要跑）

```bash
# Windows Git Bash。JAVA_HOME 必须显式设置（环境变量里没有）：
export JAVA_HOME="D:/App/.toolchains/jdk"
cd /d/App

# 单元测试 + 构建（批次 C/D 每项之后）：
./gradlew testDebugUnitTest assembleDebug --console=plain

# 最终验证：需要已启动的模拟器/设备（androidTest 含 DataSafetyTest、ReaderDatabaseMigrationTest、
# ReaderDestinationLifetimeTest、ShelfNavigationTest 及新增 ProgressRevisionTest）：
./gradlew connectedDebugAndroidTest --console=plain
```

预期：单元测试 47 项全过（若 C-12 增改了测试以数量变化为准、0 失败）；connected 测试全过。

## 5. 明确不做（除非用户再次确认）

- **深色主题首帧闪白修复**（新增 `app/src/main/res/values-night/themes.xml`）：有视觉变化，用户尚未拍板，禁止执行。
- 包声明与目录规范化（15+ 文件声明 `package ...ui` 却在 `ui/reader/` 目录下）：用户已否决。
- 搜索历史/笔记本地加密：已评估为不必要，不做。
- `saveProgressNow` 的 onDispose 里 runBlocking 已实现并验证，勿再改动其超时策略。

## 6. 完成后交付物（给用户的汇报格式）

每个文件：修改原因 / 关键代码变化 / 测试结果；最终附全量测试输出摘要。不 git commit。
