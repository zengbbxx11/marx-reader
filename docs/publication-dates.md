# 源站目录顺序与发表时间资料

作者作品列表只使用源站作者目录的原有顺序。不提供其他排序方式，已移除时间排序按钮、起止年份和未注明日期筛选。标题搜索和作品分类保留，筛选结果保持源站相对顺序。

`tools/library_source_order.json` 保存从源站作者目录缓存提取的链接次序，重复链接取第一次出现的位置。打包工具将它转换为每位作者的 `sourceBookIds`，应用按该列表读取作品，日期和标题均不参与排列。新收录作品时需要同步复核此目录快照。

《共产主义原理》的已有全集版本映射到恩格斯目录中同名作品的位置。《资本论》三卷的作者均为马克思，只在马克思作品目录中按源站位置展示。恩格斯整理出版第二、三卷的贡献保留在作品说明中，不作为共同作者归属。全部作品保留，正文、章节和注释不受影响。

## 数据含义

- `publicationDate`：源站明确给出的发表时间，保留 `YYYY`、`YYYY-MM`、`YYYY-MM-DD` 的原有精度。
- `publicationDateEnd`：源站明确标注的发表年份范围的结束年份，仅作资料展示。
- `publicationDateBasis`、`publicationDateSourceUrl`：原始出版说明及其中文马克思主义文库出处。
- `publicationDateNote`：采用新历日期、分期发表起始日期等必要说明。

原有 `year` 及 `yearType` 保留，用于展示写作、版本、译本等原始信息；不会被当作发表时间的替代值。书目详情的来源区域显示两者及其依据。

俄罗斯旧历、新历并列时采用括注的新历，处理跨月、跨年和同月多个日期。分期发表采用第一次所列日期；只有年份或月份时不在界面编造具体月日。这些日期不影响列表顺序。

## 来源与覆盖范围

所有实际采用的内容均来自 https://www.marxists.org/chinese/，本轮未使用站外资料或搜索结果。优先使用已经随书库保存的源站正文出版说明，不从标题中的年份、历史事件、写作日期、别篇文章的尾注或译本出版日期猜测原作发表时间。

已为162部作品提供可追溯的发表日期；其余488部保留“发表时间未注明”。这表示书库尚没有经明确依据核实的发表日期，并不表示这些作品在历史上没有发表过。含不同文章的合集不会从内部任意文章推断整个合集的出版时间。

《资本论》三卷和《共产党宣言》按源站明确的出版说明单独复核，记录在 `tools/library_publication_overrides.json`。其余出版说明由 `tools/library_publication_dates.py` 保守解析，打包时自动执行，避免重建书库丢失日期。

## 维护及验证

```text
python tools/update_publication_dates.py
python tools/update_publication_dates.py --write
python -m unittest discover -s tools/tests
gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest
```

更新工具默认只扫描；`--write` 同步书目目录和正文资源的日期元数据。重复执行新增修改量应为0。报告保存在 `.generated/publication-date-report.json`，其中含每条日期的完整依据。

测试覆盖写作与发表日期不同、旧新历跨月跨年、部分日期、无效日期、原文不变，以及不同作者的独立源站次序、筛选后相对顺序、全库目录映射完整性。无连接设备时不声称已完成真机界面验证。

此前注释修复及日期资料核实均保留；本次仅更正列表顺序及相关控件。
