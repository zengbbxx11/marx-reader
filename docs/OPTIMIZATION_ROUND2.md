# Android 项目第二轮优化与雷电回归报告

日期：2026-09-14。项目：MarxReader（org.marxreader.app）。

本轮沿用 Kotlin、Compose、ViewModel、Repository 和 SQLiteOpenHelper 架构。用户最终确认只使用当前雷电 Android 14，不安装其他系统镜像。未调整主题、颜色、字体、布局、动画、导航入口或翻页操作。阅读页仅修复重新分页时的字符定位漂移。

## 本轮修改

| 优先级 | 问题与原因 | 修改文件 | 修改内容及范围 |
| --- | --- | --- | --- |
| P1 | 计时写入完成后遇到协程取消，检查点可能未推进，清理阶段重复累计 | app/src/main/java/org/marxreader/app/ui/reader/ReadingSessionTracker.kt；ReadingTimeCheckpoint.kt | 将数据库写入和检查点推进放入同一不可取消区间；暂停时记录截止时间，每次恢复使用独立会话状态。只影响计时准确性。 |
| P1 | 退出时先异步保存、再等待保存，会重复写同一个位置 | app/src/main/java/org/marxreader/app/data/LibraryRepository.kt；app/src/main/java/org/marxreader/app/ui/reader/ReaderViewModel.kt | 返回并复用应用持有的 Deferred；退出等待已有提交，保留 300 毫秒等待上限和书架刷新。数据库阻塞时仍可先读到最新内存位置。 |
| P1 | 统计的每日、分书、完成数查询可能读到不同提交状态 | app/src/main/java/org/marxreader/app/data/ReaderDatabase.kt | 使用一个事务读取全部统计聚合；保留原有跨午夜、时区和排序算法。 |
| P1 | Android FTS4 对引号、前缀和增强查询语法的支持与原查询不符，造成标题/正文漏查 | app/src/main/java/org/marxreader/app/data/ReaderDatabase.kt | 使用安全的 Unicode 词元、标准前缀与隐式 AND；标题多词条件通过 SQL 行号交集组合，保留标题/章节跨列匹配。搜索界面未改。 |
| P1 | 竖屏第 3 页 → 横屏 → 竖屏后退到第 2 页 | app/src/main/java/org/marxreader/app/ui/reader/ReadingScreen.kt | 恢复时保留原始段落和字符锚点，不用新页面页首覆盖；用户真正翻页后才更新锚点。排版算法和视觉资源未改。 |
| P2 | 仅以目录计算指纹，正文更新而目录不变时可能复用旧索引 | app/build.gradle.kts；LibraryRepository.kt | 构建阶段计算所有作品 JSON 的 SHA-256，生成小型指纹 asset。启动只读取该指纹，不遍历全部正文；旧缓存会在首次搜索时重新建立。 |
| P2 | 万条会话统计重复扫描耗时明显 | LibraryRepository.kt | 只缓存一份统计结果，以数据版本、进度版本、日期和时区失效；互斥保护并发读取。未增加持久化表或大规模刷新机制。 |

## 验证结果

- JVM 单元测试：54/54 通过。
- Android 14 完整设备测试：30/30 通过，最终一次耗时 32.261 秒。
- Debug APK、AndroidTest APK 构建通过。
- Release APK 经 R8 混淆构建通过；本轮设备测试使用 Debug APK，未把 Release 构建通过等同于 Release 设备全量验证。
- Android Lint：0 错误、1 条现有警告（MonochromeLauncherIcon）。为保持现有图标视觉，本轮未更改图标。
- git diff --check 通过。
- 原有 res、作品 JSON、Manifest 没有最终差异。

新增 ReadingTimeCheckpointTest、SecondRoundRegressionTest 和 ReaderRotationTest；扩展 DataSafetyTest 与 SearchQueryTest；ReaderTextLayoutTest 补充生产环境由父容器提供的 LayoutParams，消除未挂载测试视图点击时的原生 TextView 空指针。

旧版上复现了重复写入和统计不一致；首轮 26 项设备测试有 4 项失败（3 项搜索、1 项测试视图初始化）。修复后全部通过。压力测试后来改用应用实际共享的 SQLiteOpenHelper 连接池，避免额外连接池在高负载下触发 SQLite 忙超时，保留并发结果一致性与写线程成功断言。

## 雷电实测范围

独立实例 MarxReader-QA-20260914，index 1，ADB emulator-5556；Android 14 / API 34，x86_64，1080×1920，480 dpi，2 核/4096 MB。用户原有 index 0 实例未用于安装、清理或测试。

| 场景 | 结果及证据 |
| --- | --- |
| 全新安装 | 最终 Debug APK 卸载后重新安装成功，650 部作品书库正常打开。 |
| 覆盖安装 | 旧 APK 覆盖安装新 APK 成功；首次启动前数据库字节完全一致。 |
| 数据库迁移 | v1 升级创建章节进度表；v3 升级保留旧阅读位置及笔记并补全字段。未宣称所有历史版本迁移组合均已测。 |
| 前后台 | 第 3 页退到后台再返回，位置保持；后台 5 秒采样，累计 active_millis 不再增长。 |
| 旋转 | 修复后竖屏第 3 页、横屏第 4/528 页、恢复竖屏第 3/381 页；源字符定位保留。 |
| Activity 重建 | 专项测试中 scenario.recreate 后仍保持第 3 页。 |
| 进程回收 | 后台 am kill 后确认 PID 消失，重新打开恢复第 3 页。不是仅用 force-stop 代替进程回收。 |
| 截图 | 六组同数据快照截图，应用区域逐像素差异全部为 0。 |
| Android 12、13、15 | 按用户确认列为待测，不下载或虚构相应雷电镜像。 |

## 性能和 UI 证据

万条会话统计：旧版样本 261–363 ms；最终样本 243–316 ms。主机负载会影响模拟器读数，不能据此宣称首次统计稳定提升固定百分比。缓存命中时连续 20 次读取总计 5 ms；结果随写入/清空等操作正确失效。

启动样本有波动，本轮没有证据证明稳定的冷启动提升，也未为追求单次数字改变初始化或 UI 加载逻辑。首次大章分页仍有计算成本，本轮保持现有分页视觉与缓存策略。

截图包括书库、作者作品、阅读正文、阅读排版面板、书架和设置。比较分辨率 1080×1920，仅排除顶部 72 像素系统状态栏（时钟等系统内容）；每张其余 1,995,840 像素全部一致。报告保留原始图片，不通过裁改图片掩盖差异。

## 产物与复查

- 测试 APK：app/build/outputs/apk/debug/app-debug.apk
- 对比页面：.generated/qa-20260914/screenshot-report.html
- 像素结果：.generated/qa-20260914/screenshot-comparison.json
- 完整设备结果：.generated/qa-20260914/final-instrumentation.txt
- 旋转专项：.generated/qa-20260914/rotation-instrumentation.txt
- 生命周期结果：.generated/qa-20260914/lifecycle-results.json
- 性能数据：.generated/qa-20260914/final-metrics.txt
- 正文指纹失效验证：.generated/qa-20260914/fingerprint-check.json
- 构建记录：.generated/qa-20260914/release-lint-build.txt
- Lint：app/build/reports/lint-results-debug.html

.generated 为本地验证产物目录，不加入源代码提交。测试后已清理独立实例中的测试夹具数据，保留最新 APK 在书库首页。代码改动保留在工作区，未创建提交。

下一阶段需要实机/其他系统镜像的项目：Android 12、13、15 的系统差异，厂商后台策略，真实 ARM 设备的内存/功耗和性能。这些不在当前 Android 14 雷电测试结论内。
