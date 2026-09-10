# -*- coding: utf-8 -*-
"""修复《资本论》第三卷源页的「字节帧错位」损坏。

## 问题本质（与常见的字符集乱码完全不同）

源站 marxists.org 中文页在历史编码转换中，把**某些汉字的首字节或次字节**
写成了 ASCII '?'(0x3F)。如果被顶掉的是**首字节**，其后紧邻的那一个字节就
变成"孤儿"，导致之后所有 GB 双字节两两错位 —— 于是整段文字解出「合法但
语义错误的汉字」或落进 Unicode 私有区（PUA），看起来像"不可逆乱码"。

**但信息并没有丢**：把错位重新对齐（丢弃孤儿字节）后，正文可完整还原。
每处错位真正丢失的，只有被 '?' 顶掉的那**一个汉字**。

## 取证方法

1. 下载源页**原始字节**（缓存 HTML 是解码后文本，无法用于字节判断）；
2. 用「书库自身干净正文」建立的**字频模型**判别 0x3F 是源站合法的半角
   问号，还是顶掉了汉字（重对齐后平均字频显著更高者判为后者）；
3. 逐个错位点确认**存活的另一半字节**，据此锁定丢失字：
   例如 `3F D8` → 丢失字以 D8 结尾 → 「特」(CC **D8**)；
   `CA 3F` → 丢失字以 CA 开头 → 「是」(**CA** C7)。
4. 用权威外版（本卷德文原著之英文译本）核对语义。

## 本次取证结果（全部 55 个源页、550 页范围全扫，仅 3 处错位）

| 源页 | 字节偏移 | 丢失字 | 幸存字节 | 英文版佐证 |
| --- | --- | --- | --- | --- |
| 043.htm | 19185 | 特 | 次字节 D8 | "increased from 20 to 30 qrs" |
| 043.htm | 19210 | 是 | 首字节 CA | "only half as large, or £18 instead of £36" |
| 044.htm | 57147 | 来 | 次字节 B4 | "they had thereby turned the land-owning aristocracy into paupers" |
| 044.htm | 57254 | 呢 | 首字节 C4 | "How did this occur? Very simply." |
| 044.htm | 72241 | 与 | 次字节 EB | "…agree with the general price of production regulated by A" |

注：英文 Progress 版此处印作 "£48"，与"only half as large"（36 的一半为 18）
自相矛盾，中文译本作 18 镑，以中文源页字节为准。

## 用法

    python tools/repair_source_shift.py            # dry-run（默认）
    python tools/repair_source_shift.py --apply    # 写入源数据
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

DEFAULT_SOURCE = Path(".generated/library-full.json")

# (book_id, chapter_id, paragraph_index, 受损片段, 订正片段, 证据)
CORRECTIONS = [
    (
        "capital-v3-zh",
        "chapter-043-8e88226134",
        52,
        "30夸?亍；醣业刈馊粗挥幸话耄\ue0e5?18镑",
        "30夸特。货币地租却只有一半，是18镑",
        "043.htm@19185 丢「特」(CC D8)、@19210 丢「是」(CA C7)；"
        "英文版：\"increased from 20 to 30 qrs. The money-rent would be only half as large\"",
    ),
    (
        "capital-v3-zh",
        "chapter-044-054339ee3a",
        161,
        "这样一?矗\ue0e6\ue370腔岚淹恋毓笞灞湮\ue02f枰\ue01d燃玫钠睹瘛５\ue087导什皇钦庋\ue294\ue0e8"
        "恋毓笞宸炊\ue244纫郧叭魏问焙蚨几\ue3c3挥辛恕Ｕ馐窃趺椿厥履??",
        "这样一来，他们会把土地贵族变为需要救济的贫民。但实际不是这样，土地贵族反而比以前任何时候都更富有了。"
        "这是怎么回事呢？",
        "044.htm@57147 丢「来」(C0 B4)、@57254 丢「呢」(C4 D8)；"
        "英文版：\"…believed that they had thereby turned the land-owning aristocracy into paupers. "
        "Instead, they became richer than ever. How did this occur? Very simply.\"",
    ),
    (
        "capital-v3-zh",
        "chapter-044-054339ee3a",
        218,
        "而?胗葾调节的一般的生产价格",
        "而与由A调节的一般的生产价格",
        "044.htm@72241 丢「与」(D3 EB)；"
        "英文版：\"…agree with the general price of production regulated by A\"",
    ),
]


def repair(payload: dict, stats: dict) -> None:
    books = {b["id"]: b for b in payload.get("books", [])}
    for book_id, chapter_id, para_index, damaged, corrected, evidence in CORRECTIONS:
        book = books.get(book_id)
        if book is None:
            stats["missing_book"].append(book_id)
            continue
        chapter = next((c for c in book.get("chapters", []) if c["id"] == chapter_id), None)
        if chapter is None:
            stats["missing_chapter"].append(chapter_id)
            continue
        content = chapter.get("content", [])
        if para_index >= len(content):
            stats["missing_para"].append(f"{chapter_id}#{para_index}")
            continue
        para = content[para_index]
        if damaged in para:
            content[para_index] = para.replace(damaged, corrected)
            stats["fixed"].append(f"{chapter_id}#{para_index}")
        elif corrected in para:
            stats["already"].append(f"{chapter_id}#{para_index}")
        else:
            stats["unmatched"].append(f"{chapter_id}#{para_index} 证据：{evidence}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    ap.add_argument("--apply", action="store_true", help="写入源数据（缺省为 dry-run）")
    args = ap.parse_args()

    payload = json.loads(args.source.read_text(encoding="utf-8"))
    stats = {"fixed": [], "already": [], "unmatched": [], "missing_book": [], "missing_chapter": [], "missing_para": []}
    repair(payload, stats)

    print(f"源数据：{args.source}")
    print(f"  已修复 {len(stats['fixed'])}：{stats['fixed']}")
    print(f"  已是最新 {len(stats['already'])}：{stats['already']}")
    if stats["unmatched"]:
        print(f"  未匹配 {len(stats['unmatched'])}：")
        for item in stats["unmatched"]:
            print(f"    - {item}")
    for key in ("missing_book", "missing_chapter", "missing_para"):
        if stats[key]:
            print(f"  {key}：{stats[key]}")

    if stats["unmatched"] or stats["missing_book"] or stats["missing_chapter"] or stats["missing_para"]:
        print("\n存在未处理项，未写入。请先核对受损片段是否已被其他改动覆盖。")
        return 1

    if args.apply:
        args.source.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        print("\n已写入。请重跑 tools/repair_library_text.py 与 package_library_v2.py 以同步资源。")
    else:
        print("\n（dry-run，未写入。确认无误后加 --apply）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
