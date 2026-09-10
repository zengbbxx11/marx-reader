# -*- coding: utf-8 -*-
"""修复存量书库文本：删除源站导航行与重复的结构标题行，映射已知的源站损坏字符。

背景见 .workbuddy/artifacts/content-integrity-audit-2026-09-10.md：

1. **源站字节损坏**：老页面把段首全角空格（GBK 标准为 ``A1 A1``）写坏成 ``A1 40``，
   解码后落在 Unicode 私有区（U+E4C6 等）。App 无字形，显示为空白/豆腐块。
   已逐字节确认属源站固有，重抓无法修复，只能在数据层按上下文映射回原字符。

2. **导航行残留**：源页面底部/顶部有"上一篇 回目录 下一篇"这类导航，
   ``keep_text()`` 的整词匹配拦不住组合行。

3. **结构标题行重复**：源页面把卷/篇/章标题作为普通文本行输出，
   而 App 已用 ``chapter.title`` 单独渲染标题，正文再出现一次造成视觉重复。

默认 dry-run（只统计不改动），加 ``--apply`` 才写盘。

用法::

    python tools/repair_library_text.py                       # dry-run
    python tools/repair_library_text.py --apply               # 写回
    python tools/repair_library_text.py --apply --source X --target X
"""

from __future__ import annotations

import argparse
import collections
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
# 复用打包脚本的标题清洗与分卷常量，保证判据与 package_library_v2 完全一致
from package_library_v2 import CAPITAL_PART_TITLES_ZH, PART_NAMES_ZH, clean_title

# 导航行/结构标题行/损坏字符映射的规则与门禁共用，见 library_text_rules
from library_text_rules import (
    HEAD_WINDOW,
    NAV_MARK_RE,
    NAV_RE,
    PUA_MAP,
    PUA_UNRESOLVED,
    STRUCT_MAX_LEN,
    STRUCT_RE,
    norm,
)


def replace_pua(value: str, stats: collections.Counter) -> str:
    """把已确认的源站损坏字符映射回原字符。"""
    if not isinstance(value, str):
        return value
    for broken, restored in PUA_MAP.items():
        if broken in value:
            stats["pua_replaced"] += value.count(broken)
            value = value.replace(broken, restored)
    return value


def build_title_set(book: dict) -> set[str]:
    """构造本书的标题集合（书名 + 篇名 + 章标题 + 小节标题），用于判定结构标题行。"""
    titles = {norm(book.get("titleZh", "")), norm(book.get("titleEn", ""))}
    series = book.get("seriesId") or book.get("id") or ""
    titles |= {norm(name) for name in CAPITAL_PART_TITLES_ZH.get(series, PART_NAMES_ZH)}
    for chapter in book.get("chapters") or []:
        titles.add(norm(clean_title(chapter.get("title", ""))))
        for section in chapter.get("sections") or []:
            titles.add(norm(clean_title(section.get("title", ""))))
    return titles


def repair_book(book: dict, stats: collections.Counter, samples: dict) -> None:
    """就地修复一本书。"""
    # 书籍级文本字段
    for field in ("titleZh", "titleEn", "description"):
        if field in book:
            book[field] = replace_pua(book[field], stats)

    titles = build_title_set(book)

    for chapter in book.get("chapters") or []:
        content = chapter.get("content") or []
        title_n = norm(chapter.get("title", ""))
        chapter["title"] = replace_pua(chapter.get("title", ""), stats)

        # —— 第一步：标记待删除段落 ——
        removed: set[int] = set()
        for index, paragraph in enumerate(content):
            if not isinstance(paragraph, str):
                continue
            text = paragraph.strip()
            if not text:
                continue
            if NAV_RE.match(text) or (NAV_MARK_RE.search(text) and len(text) < 50):
                removed.add(index)
                stats["nav_removed"] += 1
                samples.setdefault("nav", []).append(f"{book['id']}/{chapter['id']}#{index}: {text[:60]}")
                continue
            if (STRUCT_RE.match(text) and len(text) <= STRUCT_MAX_LEN
                    and (norm(text) in titles or norm(text) == title_n or index < HEAD_WINDOW)):
                removed.add(index)
                stats["struct_removed"] += 1
                samples.setdefault("struct", []).append(f"{book['id']}/{chapter['id']}#{index}: {text[:60]}")

        # —— 第二步：旧索引 → 新索引映射 ——
        mapping: dict[int, int | None] = {}
        cursor = 0
        for index in range(len(content)):
            if index in removed:
                mapping[index] = None
            else:
                mapping[index] = cursor
                cursor += 1

        # —— 第三步：重建 content（同时做 PUA 映射）——
        chapter["content"] = [
            replace_pua(content[index], stats)
            for index in range(len(content))
            if index not in removed
        ]

        # —— 第四步：同步 sections（锚点被删的整条移除，其余重映射）——
        new_sections = []
        for section in chapter.get("sections") or []:
            new_index = mapping.get(section.get("paragraphIndex"))
            if new_index is None:
                stats["section_removed"] += 1
                continue
            section["paragraphIndex"] = new_index
            section["title"] = replace_pua(section.get("title", ""), stats)
            new_sections.append(section)
        chapter["sections"] = new_sections

        # —— 第五步：同步 footnotes 的引用位置 ——
        for footnote in chapter.get("footnotes") or []:
            footnote["marker"] = replace_pua(footnote.get("marker", ""), stats)
            footnote["content"] = [
                replace_pua(value, stats) for value in (footnote.get("content") or [])
            ]
            new_references = []
            for reference in footnote.get("references") or []:
                new_index = mapping.get(reference.get("paragraphIndex"))
                if new_index is None:
                    stats["reference_removed"] += 1
                    continue
                reference["paragraphIndex"] = new_index
                new_references.append(reference)
            footnote["references"] = new_references


def main() -> int:
    parser = argparse.ArgumentParser(description="修复存量书库文本")
    parser.add_argument("--source", type=Path, default=Path(".generated/library-full.json"))
    parser.add_argument("--target", type=Path, default=None, help="写盘目标（默认覆盖 source）")
    parser.add_argument("--apply", action="store_true", help="真正写盘（默认只做 dry-run）")
    parser.add_argument("--show", type=int, default=10, help="每类展示的样本条数")
    args = parser.parse_args()

    payload = json.loads(args.source.read_text(encoding="utf-8"))
    stats: collections.Counter = collections.Counter()
    samples: dict[str, list[str]] = {}

    books_before = len(payload.get("books") or [])
    chapters_before = sum(len(b.get("chapters") or []) for b in payload.get("books") or [])
    paragraphs_before = sum(
        len(c.get("content") or []) for b in payload.get("books") or [] for c in b.get("chapters") or []
    )

    for book in payload.get("books") or []:
        if (book.get("language") or "").lower() != "zh":
            continue
        repair_book(book, stats, samples)

    chapters_after = sum(len(b.get("chapters") or []) for b in payload.get("books") or [])
    paragraphs_after = sum(
        len(c.get("content") or []) for b in payload.get("books") or [] for c in b.get("chapters") or []
    )

    print("=== 修复统计 ===")
    print(f"  导航行删除        : {stats['nav_removed']}")
    print(f"  结构标题行删除    : {stats['struct_removed']}")
    print(f"  连带删除 section  : {stats['section_removed']}")
    print(f"  连带删除脚注引用  : {stats['reference_removed']}")
    print(f"  源站损坏字符映射  : {stats['pua_replaced']}")
    print(f"  作品数            : {books_before} -> {len(payload['books'])}")
    print(f"  章节数            : {chapters_before} -> {chapters_after}")
    print(f"  段落数            : {paragraphs_before} -> {paragraphs_after}"
          f"（-{paragraphs_before - paragraphs_after}）")

    for key, label in (("nav", "导航行"), ("struct", "结构标题行")):
        items = samples.get(key) or []
        print(f"\n=== {label} 样本（前 {args.show} 条，共 {len(items)}）===")
        for item in items[: args.show]:
            print("   ", item)

    # 残留的不可判定字符提醒
    remaining: collections.Counter = collections.Counter()
    for book in payload.get("books") or []:
        for chapter in book.get("chapters") or []:
            for text in list(chapter.get("content") or []) + [
                v for fn in (chapter.get("footnotes") or []) for v in (fn.get("content") or [])
            ]:
                if isinstance(text, str):
                    for marker in PUA_UNRESOLVED:
                        if marker in text:
                            remaining[marker] += text.count(marker)
    if remaining:
        print("\n=== 保留未动的源站损坏字符（信息不可逆丢失）===")
        for marker, count in remaining.most_common():
            print(f"   U+{ord(marker):04X} x{count}")

    if not args.apply:
        print("\n[dry-run] 未写盘。加 --apply 生效。")
        return 0

    destination = args.target or args.source
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(
        json.dumps(payload, ensure_ascii=False, separators=(",", ":")), encoding="utf-8"
    )
    print(f"\n[apply] 已写入 {destination}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
