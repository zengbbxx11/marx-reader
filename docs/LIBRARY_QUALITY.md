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

当前硬性检查包括重复 ID/来源、断裂或越界目录、空正文、异常短正文、正文中的私有抓取标记或 HTML、重复章节、纯数值或表格行伪标题，以及 `UNKNOWN`/`PERMISSION_REQUIRED` 状态下意外携带正文。

## 2026-08-28 基线

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
