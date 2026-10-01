# 2026-10-01 源站内容核查

本文前半部分保留修复前的核查记录；本轮已完成的修复与验证见文末。

本轮核查对象是实际随应用发布的 `app/src/main/assets/library/`，未重建或改写正式书库。源站为 Marxists Internet Archive 中文页面。

## 范围与结果

- 扫描 650 部收录条目、884 个章节；保存 901 个不同 URL 的实时页面快照。
- 876 章在忽略排版空白、应用仓库既有导航/结构清理与明确乱码订正后，正文文本与重新提取的源页一致。
- 7 章有文本差异：4 章为标题重复/合并差异，3 章含残留导航文字。
- 《人民英雄们永垂不朽》是短文本，低于构建器提取阈值；已另外通过 DOM 按段顺序核验，所有本地段落可在源页找到。该项不计为完整正文等同。
- 独立 DOM 检查产生 8 个章节的未匹配块候选，主要是源页目录、既有 PUA 订正或分开的标题，不能直接视为缺文。
- 29 章的源页包含 96 处图片引用。此数字包括重复公式、图表、扫描件和可能的装饰，不能当作 96 个确定内容错误。
- 既有结构/质量检查仍为 ERROR=0、REVIEW=1477；脚注范围检查通过：8343 条脚注、8379 个引用。2 条 SOURCE_MISSING 提示仍未补齐原注。

## 已确认问题

### 1. 《资本论》第二卷存在被图片承载的公式缺失

源页：https://www.marxists.org/chinese/marx-engels/24/003.htm

本地位置：`capital-v2-zh` / `chapter-003-5b25d39805` / `content[9]`（第 10 段）。源页在“或者简单地说，就是”之后有 `003-3.jpg`，在“Ｇ―Ｗ是表现为”之后有 `003-2.jpg`。已下载并查看两张原图，分别是 W 分支为 A/Pm、G—W 分支为 A/Pm 的公式。

本地对应片段为：“或者简单地说，就是。因此，从内容来看，Ｇ―Ｗ是表现为；就是说……”——图片承载的信息已被跳过，不是一般排版差异。正文 JSON 仅保留字符串，现有阅读数据模型没有图片内容字段。

第二卷第一、二、三、四、二十章共有 49 处图片引用、13 个不同图片 URL；其中已查看的两个公式确认缺失，其余需逐图确定应转写、保留图表或采用其他呈现方式。不能使用盲目 OCR 或猜测补字。

### 2. 一条书目的题名误用了格式入口名称

本地书目：`lenin-work-1d0fc116fd84`，题名“网页版”，包含 10 个章节。

来源：https://www.marxists.org/chinese/lenin/selectedworks/selectedWorks-index.htm

源页 title 为“列宁选集·目录”，正文标题为“列宁选集”。“网页版”是格式入口标签，不是作品题名；应据实际收录范围重新命名，并避免暗示四卷全部收录。当前收录是凡例、编者的话、第一卷说明及该页已链接的部分文献和第一卷注释。

### 3. 三篇正文开头残留网站导航

以下文本前缀 `-> 毛泽东` 或 `-> 斯大林` 来自网站导航，被并入题名段落；不是原文。

| 作品 | 源页 |
| --- | --- |
| 毛泽东主席接见卡博、巴卢库谈话记录（1967年2月3日） | https://www.marxists.org/chinese/maozedong/mia-chinese-mao-19670203.htm |
| 毛泽东主席接见老挝人民党代表团谈话记录（1967年11月） | https://www.marxists.org/chinese/maozedong/mia-chinese-mao-196711.htm |
| 马克思主义与语言学问题 | https://www.marxists.org/chinese/stalin/mia-chinese-stalin-1950.htm |

## 其他文字差异

《资本论》第三卷“Ⅱ．交易所”首段合并了原网页章节标题；《德国维护帝国宪法的运动》导言、《社会民主党在民主革命中的两种策略》序言存在重复标题；《怎么办？》序言采用章节标题展示源页“序 言”。这些差异均涉及标题呈现，本轮未发现对应正文内容缺失。

## 源页图片候选清单

下表用于后续逐图复核；数量为网页引用次数，不是唯一图片数，也不是确认缺失数。

| 本地作品 | 章节 | 图片引用次数 | 源页 |
| --- | --- | ---: | --- |
| 资本论·第二卷 | 第一章 货币资本的循环 | 23 | https://www.marxists.org/chinese/marx-engels/24/003.htm |
| 资本论·第二卷 | 第二章 生产资本的循环 | 12 | https://www.marxists.org/chinese/marx-engels/24/004.htm |
| 资本论·第二卷 | 第三章 商品资本的循环 | 4 | https://www.marxists.org/chinese/marx-engels/24/005.htm |
| 资本论·第二卷 | 第四章 循环过程的三个公式 | 6 | https://www.marxists.org/chinese/marx-engels/24/006.htm |
| 资本论·第二卷 | 第二十章 简单再生产 | 4 | https://www.marxists.org/chinese/marx-engels/24/022.htm |
| 资本论·第三卷 | 第四十九章 关于生产过程的分析 | 1 | https://www.marxists.org/chinese/marx-engels/25/050.htm |
| 英国工人阶级状况 | 大城市 | 5 | https://www.marxists.org/chinese/engels/1844-1845/05.htm |
| 英国工人阶级状况 | 对英国工人阶级状况的补充评述 | 1 | https://www.marxists.org/chinese/engels/1844-1845/15.htm |
| 共产主义在德国的迅速进展 | 共产主义在德国的迅速进展 | 2 | https://www.marxists.org/chinese/engels/mia-chinese-engels-184502-04.htm |
| 从巴黎到伯尔尼 | 从巴黎到伯尔尼 | 3 | https://www.marxists.org/chinese/engels/mia-chinese-engels-18470626b.htm |
| 网页版 | 俄国资本主义的发展(节选) | 5 | https://www.marxists.org/chinese/lenin/selectedworks/selectedWorks-1895_1899.htm |
| 被剥削劳动人民权利宣言 | 被剥削劳动人民权利宣言 | 1 | https://www.marxists.org/chinese/lenin/mia-chinese-lenin-19180103.htm |
| 无政府主义和社会主义 | 无政府主义和社会主义 | 2 | https://www.marxists.org/chinese/lenin/mia-chinese-lenin-1901.htm |
| 共产党宣言（1920年陈望道译本） | 共产党宣言（1920年陈望道译本） | 4 | https://www.marxists.org/chinese/marx/mia-chinese-marx-184002-cwd.htm |
| 致达赖喇嘛 | 致达赖喇嘛 | 1 | https://www.marxists.org/chinese/maozedong/mia-chinese-mao-19540410.htm |
| 致野坂参三 | 致野坂参三 | 2 | https://www.marxists.org/chinese/maozedong/mia-chinese-mao-19450528.htm |
| 共产党在德国的要求 | 共产党在德国的要求 | 1 | https://www.marxists.org/chinese/marx/mia-chinese-marx-184803.htm |
| 马克思致昂利・奥里奥尔（1876年10月21日） | 马克思致昂利·奥里奥尔（1876年10月21日） | 1 | https://www.marxists.org/chinese/marx/mia-chinese-marx-letter-18761021.htm |
| 马克思致莫里斯・拉沙特尔（1874年11月12日） | 马克思致莫里斯·拉沙特尔（1874年11月12日） | 1 | https://www.marxists.org/chinese/marx/mia-chinese-marx-letter-18741112.htm |
| 马克思致莫里斯・拉沙特尔（1872年1月30日） | 马克思致莫里斯·拉沙特尔（1872年1月30日） | 2 | https://www.marxists.org/chinese/marx/mia-chinese-marx-letter-18720130.htm |
| 马克思主编《新莱茵报》中译版 （文字版） | 第4号（1848年6月4日） | 1 | https://www.marxists.org/chinese/marx/neuen-rhein-ischen-zeitung/004.htm |
| 马克思主编《新莱茵报》中译版 （文字版） | 第20号（1848年6月20日） | 3 | https://www.marxists.org/chinese/marx/neuen-rhein-ischen-zeitung/020.htm |
| 马克思主编《新莱茵报》中译版 （文字版） | 第23号（1848年6月28日） | 2 | https://www.marxists.org/chinese/marx/neuen-rhein-ischen-zeitung/023.htm |
| 马克思主编《新莱茵报》中译版 （文字版） | 第301号（1849年5月19日） | 2 | https://www.marxists.org/chinese/marx/neuen-rhein-ischen-zeitung/301.htm |
| 革命的西班牙 | 革命的西班牙 | 1 | https://www.marxists.org/chinese/marx/mia-chinese-marx-185408-11.htm |
| 马克思致昂利・奥里奥尔（1876年5月4日） | 马克思致昂利·奥里奥尔（1876年5月4日） | 1 | https://www.marxists.org/chinese/marx/mia-chinese-marx-letter-18760504.htm |
| 马克思致莫里斯・拉沙特尔（1875年9月27日） | 马克思致莫里斯·拉沙特尔（1875年9月27日） | 2 | https://www.marxists.org/chinese/marx/mia-chinese-marx-letter-18750927.htm |
| 马克思致莫里斯・拉沙特尔（1872年11月14日） | 马克思致莫里斯·拉沙特尔（1872年11月14日） | 2 | https://www.marxists.org/chinese/marx/mia-chinese-marx-letter-18721114.htm |
| 十月变革〔原始版本〕（1917年10月24日和25日） | 十月变革〔原始版本〕（1917年10月24日和25日） | 1 | https://www.marxists.org/chinese/stalin/mia-chinese-stalin-19171024-25.htm |

## 方法、证据与复现

核查工具：`tools/audit_source_content.py`。章节来源根据已存章节 ID 中的 URL SHA-1 片段与书目源页链接对应，不按相邻标题猜测来源。

正文重提取复用了现有构建器，因此可能共享其遗漏；另行增加 DOM 文本块和图片检查，正是为识别这种盲点。TEXT_MATCH 只代表处理后的字符串一致，不代表公式、表格布局、图示及所有注释的语义关联已完整校核，也不代表源网站文本本身没有错误。

证据位于 `.generated/source-audit-20261001/`：`report.json` 为总报告；每本作品有独立 JSON；`snapshots/` 保存原始响应字节、URL、时间、HTTP 状态与 SHA-256。两张已查看的公式原图为 `003-2.jpg`、`003-3.jpg`。缓存中快照会复用，需要重新抓取时使用新的 `--output` 目录。

```bash
/workspace/toolchains/python/bin/python tools/audit_source_content.py
```

本轮仅新增审计工具与报告，正式文库和 Android 核心代码未修改。后续应优先处理公式，再处理题名和导航残留；段落修改必须复核目录与脚注偏移，并考虑阅读进度、书签和笔记定位。

## 同日修复结果

- 《资本论》第二卷第一、二、三、四、二十章的 49 处图片位置已补入可点击的 `〔图式N〕` 引用，13 张原始 JPEG 存入 `library/illustrations/capital-v2/`。全部图片已逐张查看；没有使用 OCR、猜测补字或回译。源图保留分支、加号、循环弧线和上下标的原始表现。辅助说明仅用于识别图式与无障碍阅读，不是公式正文。
- 点击图式在现有只读注释面板查看本地原图，兼容分页和连续滚动；不引入网络请求。图像随阅读内容指纹一同计算，脚注门禁和 APK 检查均验证图片存在及原始 SHA-256。
- “网页版”改为“列宁选集（网页版节选）”，说明实际收录范围，并同步更新目录根节点和轻量书目。
- 三处已确认的 `-> 毛泽东` / `-> 斯大林` 导航前缀已移除。
- 五本受影响作品的书籍、章节与目录节点 ID、段落数量均保留；既有脚注按插入位置重映射。针对旧批注选区跨越新增图式标记的情况增加了重定位及重复摘录测试；批注原摘录保持原样。
- 修复工具默认 dry-run，校验源页文本及全部引用后才写入；重跑为零变更。新增构建器处理保留图式入口，未复核的第二十四卷新图片会触发明确错误，避免再次静默跳过。
- 修复后针对五本作品的 36 个章节重新比对，处理后文字均与对应源页一致。证据保存于 `.generated/source-audit-after-repair-20261001/`。

全库结构检查保持 `ERROR=0`；新增作品简介使 `REVIEW` 从 1477 降为 1476。脚注为 8392 条、8428 个可点击引用（新增的 49 条为源页图式）。原先两条缺文说明仍未补齐。源页其他作品的图片、扫描件和图表继续保留在候选清单中，本次不宣称已完成全库所有非文字内容的修复。
