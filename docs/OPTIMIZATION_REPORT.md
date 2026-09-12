# Android 底层优化实施报告

日期：2026-09-12。以用户确认的审查计划实施；保留原有未提交修改，无 commit/push。
修改前源码基线：`.generated/optimization-baseline-20260912/src`。
本报告补充并更新 OPTIMIZATION_HANDOFF.md 中的待办状态。

## 实施结果

| 文件（app/src/main/java/org/marxreader/app 下） | 原因与实际修改 |
|---|---|
| data/LibraryRepository.kt | 修复复用持久化索引误报；错误序号同步发布，清除提示时匹配具体事件；最新进度先登记到内存，应用级异步队列持有写入，调用方可取消等待而不等待阻塞 SQLite；索引使用独立 IO 执行；正文加载互斥去重并受字符预算限制。 |
| data/ReaderDatabase.kt | FTS 每 200 段短事务构建暂存索引，成功后事务内原子替换并更新指纹，失败保留原索引。统计逐行读取必要列，每书总时长、会话数与已读数量交由 SQL 汇总，保持同分排序。 |
| data/ReadingStatistics.kt | 共用原有跨日分摊、舍入及连续阅读天数计算。没有将跨午夜会话粗略归到开始日期。 |
| data/ReaderMemoryPolicy.kt | 新增设备缓存预算：低内存或未知 60 万字符，中档 90 万，高档 120 万；是权重预算，不是实测堆字节数。 |
| ReaderApplication.kt | 收到适用内存/后台回调时收缩已初始化的正文缓存，不触发磁盘 IO 或提前初始化。 |
| ui/reader/ChapterPageCache.kt | 注入设备预算，保留最多 3 个条目。 |
| ui/reader/ReadingScreen.kt | 分页缓存和预取共用预算；派生进度跟踪 State 对象，避免以滚动位置值反复重建；错误提示按事件确认。 |
| data/ReaderPreferences.kt | 错误类型、NaN、无穷值和越界配置回退到现有默认值；现有预设和全部合法边界不变。 |
| ui/settings/SettingsScreen.kt | 系统应用信息 Intent 失败时使用已有 Snackbar 机制兜底；正常入口和布局不变。 |
| app/proguard-rules.pro | 移除无效的平台 org.json keep 规则。 |

退出保存最终保留原有 300ms 等待策略与 ReaderViewModel 实现。关键变化在 Repository：
等待的对象属于应用级作用域，取消 await 不会等待不可取消的 SQLite 调用。
慢存储时仍可能消耗等待预算，未将其声称为零卡顿；进程异常终止前尚未落盘的任务仍无持久化保证。
没有引入新生产依赖、升级框架、调整资源或改动数据库版本号。
FTS 暂存表属于可重建索引，构建时会临时占用额外磁盘空间。

## 验证

- 单元测试：52 项，0 失败、0 错误、0 跳过（原 47 项，新增 5 项）。
- debug 构建、release/R8 构建、androidTest 编译和打包、lintDebug：已通过。
- lint：0 errors、1 warning，原有 monochrome 图标警告未改动。
- 主机 SQLite 3.53.1：从源码提取 SQL 验证 FTS4 原子替换/回滚、聚合排序，通过；不等同 Android SQLite 实机验证。
- 7 个资源文件、651 个 assets 文件和 Manifest 与修改前基线逐字节一致。
- debug/release APK 各包含 650 本书，无 INTERNET 权限。
- APK 16KB ZIP 对齐与 arm64-v8a/x86_64 原生库 LOAD 段对齐检查通过。
- git diff --check 通过。

新增设备回归覆盖：索引复用、空/失败构建回滚、Repository 重建、磁盘写锁下最新进度读取、
超时等待、保存顺序、旧错误确认、跨时区统计等价、同分排序和错误偏好类型。
修正既有 v1 迁移测试缺少旧表的夹具，保留原断言与生产迁移代码。

## 尚未验证

connectedDebugAndroidTest 已尝试，失败原因为 No connected devices。
当前无连接设备且项目 SDK 未安装模拟器。Android 12/13/14/15 的安装运行、
旋转/前后台/强杀恢复、截图对比、堆内存和启动/帧率采样尚未完成。
没有提供缺乏设备测量依据的性能提升百分比。

统计每日分摊仍需扫描有效会话，优化消除了整表会话对象常驻列表，没有宣称时间复杂度变为常数。
设备回归完成前，不应把本次结果描述为“所有 Android 版本验证通过”。

## 复验命令

设置 JAVA_HOME 为 D:/App/.toolchains/jdk 后：

```text
gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest assembleRelease lintDebug --console=plain
gradlew.bat connectedDebugAndroidTest --console=plain
python tools/verify_apk.py
```

设备测试使用专用测试环境；现有数据库测试会重建测试应用的 reader.db。
