# 文库质量与可信度门禁

文库质量检查分为自动门禁和人工复核两层。自动化只对能够确定的问题作出结论；需要版本学、翻译史或法律判断的信息必须明确保留为待复核状态，不能用程序猜测填充。

## 发布门禁

运行：

```shell
python tools/validate_library.py
python tools/audit_library_quality.py --json .generated/library-quality.json
python tools/audit_footnotes.py .generated/library-full-v2.json
```

本项目当前的默认报告专注正文、书目、版本和来源质量。权利依据是可选专项，只有显式传入
`--include-rights-review` 时才会计入报告，避免它淹没内容治理待办。

审计器同时支持拆分后的正式目录和生成阶段的单体 JSON：

```shell
python tools/audit_library_quality.py app/src/main/assets/library
python tools/audit_library_quality.py .generated/library-full-v2.json
```

严重级别：

- `ERROR`：确定的结构、正文、导航、引用或权利约束缺陷。正式版本必须为零。
- `REVIEW`：缺失或互相冲突的元数据，需要查看具体来源后人工判断。默认不阻止构建，但必须出现在报告中；使用 `--fail-on-review` 可以在专项治理阶段将其作为失败处理。

当前硬性检查包括重复 ID/来源、断裂或越界目录、空正文、异常短正文、正文中的私有抓取标记或 HTML、重复章节、纯数值或表格行伪标题、来源导航行、正文首段重复的结构标题行、私有使用区（PUA）字符，以及 `UNKNOWN`/`PERMISSION_REQUIRED` 状态下意外携带正文。

导航行、结构标题行与 PUA 字符的判定规则集中在 `tools/library_text_rules.py`，由质量门禁与 `tools/repair_library_text.py` 共同引用，避免两处规则漂移。`tools/repair_library_text.py` 默认只做 dry-run，加 `--apply` 才写入。源页字节帧错位的订正表另见 `tools/repair_source_shift.py`（同为 dry-run 默认）。

## 2026-08-28 基线

以下数字是当日快照，早于后续的书库重建与正文完整性清理，口径与最新基线不同，仅作历史记录保留；最新基线见「2026-09-10 正文完整性清理」。

正式内置文库包含：

- 5 位作者；
- 650 部作品或文本版本；
- 884 个章节；
- 62,227 个正文段落；
- 8,730,937 个正文字符（章节内段落换行计入统计）；
- 379 部带脚注的作品、7,386 条脚注、7,387 个可点击引用；
- 质量门禁 `ERROR=0`。

本轮从目录元数据中移除了 192 个确定由数值、公式或表格行误识别的节点，正文和段落位置均未改动。第一轮结构清理后的人工复核基线为：

- `MISSING_YEAR`：190 部；
- `TITLE_YEAR_CONFLICT`：125 部；
- `TRANSLATOR_UNRECORDED`：649 部；
- `DESCRIPTION_MISSING`：645 部；
- `RIGHTS_BASIS_UNRECORDED`：650 部（仅专项报告显示）。

同一作品可能同时命中多项，因此不能将这些数字相加解释为作品数量。`REVIEW` 也不等于已经确认错误；它表示当前元数据不足以支持确定结论。

## 2026-08-29 内容元数据校准

在不处理权利声明的前提下，保守校准器完成了 128 部作品的元数据变更：来源页日期纠正了从作者索引误继承的年份，为 5 部作品补入双重信号一致的年份，并录入 2 条页面明确署名的译者信息。9 个题名年份具有特殊语义的作品通过显式修订表处理。

校准后默认内容报告为：

- `ERROR`：0；
- `TITLE_YEAR_CONFLICT`：0（原 125）；
- `MISSING_YEAR`：185（原 190）；
- `TRANSLATOR_UNRECORDED`：647（原 649）；
- `DESCRIPTION_MISSING`：645；
- 默认 `REVIEW` 合计：1,477。

数字下降不是唯一目标。185 个缺失年份和 647 个未记录译者继续保留，原因是当前缓存来源不足以支持可靠结论。

## 2026-09-10 正文完整性清理

第二轮正文核查发现三类此前未被门禁覆盖的缺陷，并完成清理：

- **来源导航行**：`上一篇 / 回目录 / 下一篇` 等组合型导航文本。原 `keep_text().is_navigation()` 只匹配单个词，组合行被当作正文保留。本次移除 149 处。
- **重复结构标题行**：旧版来源页把卷/篇/章标题作为普通文本行输出，而 App 已单独渲染 `chapter.title`（`ReadingScreen.kt`），导致正文首段重复。本次移除 196 处，并同步重映射 195 个 `section` 的 `paragraphIndex`。
- **私有使用区（PUA）字符**：GBK 用户区字节（如 `A1 40` 代替 `A1 A1`）按错误字符集解码后落入 U+E000–U+F8FF，表现为空白、拉丁字母或汉字损坏。本次按显式映射表还原 40 处（`　`、`ö`、`ä`、`志`、`米`）。

清理后正式内置文库为：

- 650 部作品或文本版本；
- 884 个章节、1,935 个 `section`；
- 56,140 个正文段落（原 56,485，减少 345 = 149 导航行 + 196 结构标题行）；
- 8,672,713 个正文字符；
- 382 部带脚注的作品、7,405 条脚注、7,406 个可点击引用；
- 质量门禁 `ERROR=0`，默认 `REVIEW=1,477`。

章节 ID、`section` ID 与 `sourceUrl` 全部保持不变，因此用户既有的阅读进度、书签与笔记不会失效。

新增门禁规则：

- `NAVIGATION_LINE`（`ERROR`）：正文出现来源导航行；
- `DUPLICATE_STRUCTURE_HEADING`（`ERROR`）：正文窗口内出现与本作品标题集或当前章节标题一致的结构行；
- `PRIVATE_USE_CHARACTER`（`ERROR`）：正文出现私有使用区（PUA）字符。

### 字节帧错位（Capital v3）：原判「不可逆」已推翻

同日复查推翻了此前对《资本论》第三卷 10 个 PUA 字符的判断。**它们不是信息丢失**，而是源页把某个汉字的首字节或次字节写成了 ASCII `?`（`0x3F`）；若被顶掉的是**首字节**，紧随其后的那个字节就成了「孤儿」，此后所有 GB 双字节两两错位，解出语义不通的汉字并落进私有区——看着像乱码，信息其实还在。

取证方法：下载源页**原始字节** → 用书库自身干净正文建立**字频模型**，判别每个 `0x3F` 究竟是源站合法的半角问号，还是顶掉了汉字 → 逐个错位点确认**存活的另一半字节**以锁定丢失字 → 用本卷权威英文译本核对语义。

对《资本论》第三卷全部 55 个源页扫描后，确认仅 **3 处**错位、真正丢失的汉字 **5 个**：

| 源页 | 字节偏移 | 丢失字 | 幸存字节 | 英文版佐证 |
| --- | --- | --- | --- | --- |
| 043 | 19185 | 特 | 次字节 `D8` | “increased from 20 to 30 qrs” |
| 043 | 19210 | 是 | 首字节 `CA` | “only half as large, or £18 instead of £36” |
| 044 | 57147 | 来 | 次字节 `B4` | “they had thereby turned the land-owning aristocracy into paupers” |
| 044 | 57254 | 呢 | 首字节 `C4` | “How did this occur? Very simply.” |
| 044 | 72241 | 与 | 次字节 `EB` | “agree with the general price of production regulated by A” |

订正表固化在 `tools/repair_source_shift.py`（默认 dry-run，`--apply` 才写入）。被 `?` 顶掉的字节之外，其余正文与源页逐字一致，**未使用回译**。此后全库不再有 PUA 字符，`PUA_UNRESOLVED` 清单已清空——任何 PUA 字符一律计为 `ERROR`。

## 元数据判定原则

### 年份

`year` 应描述当前收录文本版本最有用、最可核验的年代。一般作品优先记录写作完成或首次发表年份；标题明确说明特定译本或版本时，可以记录该版本年份，并在标题、译者或说明中保留版本信息。证据字段包括：

- `yearType`：`WRITTEN`、`PUBLISHED`、`TRANSLATION_PUBLICATION`、`EDITION` 或 `SOURCE_DATE`；
- `yearBasis`：简短说明采用该年份的原因；
- `yearEvidenceUrl`：能够复核这一判断的页面；
- `yearNote`：解释题名中的历史年份、版本年份与记录年份之间的差异。

索引页中作者生卒年、选集卷次覆盖年代和相邻作品年份都不能作为当前作品年份。自动采集仅接受标题中的唯一年份，或距离链接很近且唯一的年份；证据含糊时留空。标题出现多个年份、标题年份与元数据不同等情况必须查看来源页面，不做批量替换。

### 译者与简介

空译者字段表示“尚未记录”，不表示“无译者”或“作者自译”。只有来源页面、纸本版本信息或可靠目录记录明确署名时才填写译者；同时使用 `translatorBasis` 和 `translatorEvidenceUrl` 保存依据，必要时用 `translationYear`、`editionNote` 区分译本。简介应描述作品主题和版本范围，不能把未经出处支持的评价写成事实。

## 保守校准流程

先执行 dry-run 查看预计变更：

```shell
python tools/remediate_library_metadata.py
```

确认后写入拆分资源并同步重建 `catalog.json` 元数据：

```shell
python tools/remediate_library_metadata.py --write
```

自动校准只在来源页面头部明确日期与另一项本地信号一致时执行。特殊作品和明确译者署名维护在 `tools/library_metadata_overrides.json`，不把不可审查的例外硬编码进采集器。重新构建正文时会保留已有作品的这些人工字段。

### 来源与权利

每个文本版本必须保留可访问的 `sourceUrl` 和来源标注。`rights` 判断针对的是当前中文文本版本，而不只是原著：译文、校订、序言、脚注和编者材料需要分别考虑。推荐使用 `rightsBasis` 记录来源页面的许可声明、公有领域判断依据或核验说明。

没有可核验依据时应使用 `UNKNOWN`；`UNKNOWN` 和 `PERMISSION_REQUIRED` 条目不得携带正文。质量审计只能检查状态与正文是否自洽，不能替代法律核验。

## 人工复核流程

1. 运行审计器并生成完整 JSON 报告。
2. 按 `code` 分批处理；优先处理来源和年份，其次处理译者、版本与简介。
3. 打开作品的 `sourceUrl`，记录支持修改的页面信息；不能核实则保持空值或未知状态。
4. 修改后重新运行质量审计、脚注审计和文库校验。
5. 在提交说明中列出已核验作品、采用的证据和仍未解决的复核项数量。

新增内容不得通过降低规则、删除报告或填入无依据的默认值来消除复核项。规则误报应以最小复现样本和回归测试修正规则。
