# 离线内容包格式

`.marxpack` 是普通 ZIP 文件，根目录必须包含 UTF-8 编码的 `library.json`。

```json
{
  "schemaVersion": 2,
  "packId": "example-pack",
  "packTitle": "示例内容包",
  "packVersion": "1.0.0",
  "authors": [],
  "books": []
}
```

`packId` 必须在不同版本之间保持稳定，应用据此识别更新；`packTitle` 和
`packVersion` 会显示在内容包管理页面。更新包可以替换相同 `packId` 的旧版本，
但不得与内置书库或其他内容包使用相同的作品 ID。缺少这些元数据的旧内容包仍可
导入，并会显示为“旧版内容包”。

`books` 中的作品格式：

```json
{
  "id": "stable-book-id",
  "authorIds": ["marx"],
  "seriesId": "capital",
  "titleZh": "标题",
  "titleEn": "Title",
  "language": "zh",
  "year": "1867",
  "sourceUrl": "https://www.marxists.org/...",
  "sourceCredit": "Marxists Internet Archive",
  "translator": "译者或空字符串",
  "rights": "PUBLIC_DOMAIN",
  "description": "简介",
  "chapters": [
    {
      "id": "stable-chapter-id",
      "title": "第一章",
      "level": 1,
      "content": ["第一段", "第二段"]
    }
  ],
  "toc": [
    {
      "id": "toc-stable-book-id-root",
      "parentId": null,
      "title": "第一卷",
      "type": "VOLUME",
      "order": 0,
      "chapterId": null,
      "paragraphIndex": 0,
      "counterpartKey": "root"
    },
    {
      "id": "toc-stable-book-id-chapter-1",
      "parentId": "toc-stable-book-id-root",
      "title": "第一章",
      "type": "CHAPTER",
      "order": 0,
      "chapterId": "stable-chapter-id",
      "paragraphIndex": 0,
      "counterpartKey": "chapter-1"
    }
  ]
}
```

`toc` 采用稳定节点 ID，可使用 `VOLUME`、`PART`、`CHAPTER`、`SECTION`、`PREFACE`、`APPENDIX`。`parentId` 组成目录树，`chapterId` 与 `paragraphIndex` 决定点击后的正文位置。当前内置内容以“卷—篇—章”为准，不会根据普通正文段落自动猜测“节”。省略 `toc` 时，阅读器会为旧内容包自动生成平铺章节目录。

当前应用定位为纯中文阅读器，导入包中的所有作品都必须使用 `"language": "zh"`。

允许的 `rights`：`PUBLIC_DOMAIN`、`CC_BY_SA`、`PERMISSION_REQUIRED`、`UNKNOWN`。只有前两种状态可携带正文。导入器会拒绝路径穿越、超大文件、错误结构和未知版权状态下携带正文的内容包。
