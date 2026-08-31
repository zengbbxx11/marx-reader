# 马列原典

马恩列斯毛中文离线文库，面向手机长篇阅读的安卓原生纯中文阅读器。内置书库范围为马克思、恩格斯、列宁、斯大林和毛泽东，并将《资本论》三卷作为核心书系。当前内置书库包含 650 部著作、文章和书信，合计 884 个章节、2,276 个正文小节、约 873 万字。

项目主页：<https://github.com/zengbbxx11/marx-reader>

## 产品约束

- Kotlin + Jetpack Compose 原生界面，不使用 WebView。
- App 不声明 `INTERNET` 权限，安装和首次启动均不需要联网。
- 作者、书目、正文、目录、脚注和搜索索引均保存在本地。
- 阅读进度、书签、笔记和排版偏好仅保存在设备上。
- 只阅读随 App 发布的 Marxists Internet Archive 中文内容，不提供内容导入或数据导出。
- 不提供账号、云同步、跨设备实时同步、系统备份或设备迁移。
- 启动器图标使用 [MIA 官方图标目录](https://www.marxists.org/admin/icons/index.htm) 中的 `marx.ico` 资源（马克思肖像）。
- 每个版本保留来源 URL、版本、译者和版权状态。
- 内置书库只收录中文正文。
- 章节目录采用适合手机快速扫读的“卷—篇—章”连续单列排版，不从正文猜测目录层级。
- 默认按照实际屏幕尺寸和阅读排版横向分页，支持滑动及点击左右区域翻页；也可切换连续滚动。
- 目录支持“卷／篇—章—正文小标题”，点击小标题可精确跳转到对应页面。

## 构建

需要 JDK 17、Android SDK 35 和 Gradle 8.9。项目配置完成后运行：

```shell
gradlew.bat assembleDebug
```

APK 输出位置：`app/build/outputs/apk/debug/app-debug.apk`。

构建产物、本地 Android SDK 配置、缓存及签名密钥均不应提交到仓库。对外发布 APK 时，请使用自行保管的发布密钥签名，并通过 GitHub Releases 分发。

## 内置离线文库

内置数据使用 schema v2，位于 `app/src/main/assets/library/`：`catalog.json` 只保存书目、章节统计和分层目录，每本书的全文独立放在 `books/<book-id>.json`。应用启动只读取轻量目录，进入作品时才加载对应正文；全文搜索索引也延迟到首次搜索时在设备上建立。

更新五位作者的完整中文离线内容时，先安装 `tools/requirements.txt`，由 `tools/build_full_chinese_library.py` 生成并审核合并数据，再拆分资源：

```shell
python tools/build_full_chinese_library.py
python tools/audit_full_library.py
python tools/package_library_v2.py --source .generated/library-full.json
python tools/validate_library.py
python tools/audit_library_quality.py --json .generated/library-quality.json
```

对年份、译者与版本元数据执行保守校准（默认仅预览，加 `--write` 才写入）：

```bash
python tools/remediate_library_metadata.py
python tools/remediate_library_metadata.py --write
```

质量审计把确定的结构或内容缺陷标为 `ERROR`，把年份冲突、译者未记录、权利依据待补等不能可靠自动判断的问题标为 `REVIEW`。正式版本必须保持 `ERROR=0`；`REVIEW` 项不得用未经核验的猜测批量填充。规则、当前基线和人工复核流程见 [docs/LIBRARY_QUALITY.md](docs/LIBRARY_QUALITY.md)。

这些脚本只用于开发和发布阶段维护随 APK 分发的内置书库，不是面向用户的内容入口。安装后的 App 不读取外部正文文件，也不提供书库更新、替换或删除功能；书库内容随应用版本统一更新。

## 本地精确笔记

0.4.0 起，长按正文会按实际文字坐标选取所在句子；同一段可以保存多条笔记，已有笔记会在正文中高亮。笔记使用段落指纹、字符范围和前后文进行重定位，设计说明见 [docs/NOTES.md](docs/NOTES.md)。该能力完全离线，不包含账号、同步、外部导入或文件导出。

## 数据来源与权利

书目信息和经核验的文本主要整理自 [Marxists Internet Archive](https://www.marxists.org/)。每个作品版本必须独立记录来源 URL、译者和权利状态。标为 `UNKNOWN` 或 `PERMISSION_REQUIRED` 的版本不得把正文打包发布。

程序源代码依据 [Apache License 2.0](LICENSE) 开放。该软件许可证不自动覆盖仓库内的原著、译文、注释和其他文献内容；文献内容仍受各自适用的公有领域状态、开放许可或其他权利条件约束。详细说明见 [CONTENT_LICENSES.md](CONTENT_LICENSES.md)。如发现来源、署名或权利状态记录有误，请提交 Issue 并附上可核验的信息。

## 参与贡献

欢迎通过 Issue 报告程序问题、正文错漏、来源信息错误或无障碍体验问题，也欢迎提交 Pull Request。新增正文时必须同时提供可核验的来源、版本、译者和权利状态；不得提交无法确认再发布条件的正文。

提交前建议运行：

```shell
python tools/validate_library.py
python tools/audit_library_quality.py
python tools/audit_footnotes.py
gradlew.bat test assembleDebug
```

请勿提交账号令牌、API 密钥、签名证书、签名密码、`local.properties` 或本机构建工具链。
