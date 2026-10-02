#!/usr/bin/env python3
"""Build the five-author Chinese offline library from MIA author indexes.

Network access is build-time only. The Android application consumes the split
JSON assets and deliberately has no INTERNET permission.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from pathlib import Path

from bs4 import BeautifulSoup, NavigableString

from audit_library_quality import infer_local_year, is_credible_inferred_heading
from text_encoding import decode_html
from library_source_images import image_tokens
from library_source_headings import restore_numbered_headings
from library_source_tables import table_tokens
from library_source_short_lines import preserve_short_lines, materialize_short_lines
from library_source_notes import note_tokens


HOST = "www.marxists.org"
USER_AGENT = "MarxismLibrary/0.2 (offline content builder; respectful crawler)"
DELAY_SECONDS = 0.35


@dataclass(frozen=True)
class AuthorIndex:
    id: str
    url: str
    prefixes: tuple[str, ...]


AUTHOR_INDEXES = (
    AuthorIndex("marx", "https://www.marxists.org/chinese/marx/index.htm", ("/chinese/marx/", "/chinese/marx-engels/")),
    AuthorIndex("engels", "https://www.marxists.org/chinese/engels/index.htm", ("/chinese/engels/", "/chinese/marx-engels/")),
    AuthorIndex("lenin", "https://www.marxists.org/chinese/lenin/index.htm", ("/chinese/lenin/",)),
    AuthorIndex("stalin", "https://www.marxists.org/chinese/stalin/index.htm", ("/chinese/stalin/",)),
    AuthorIndex("mao", "https://www.marxists.org/chinese/maozedong/index.htm", ("/chinese/maozedong/",)),
)

SKIP_LABEL_PARTS = (
    "pdf", "chm", "下载", "图像版", "图片", "幻灯片", "有声", "传记", "回忆", "评论",
    "参考", "专题", "辞典", "搜索", "全集（pdf", "联系我们",
    "文字网页版",
)

# Author indexes also contain site guides, modern research pieces, alternate
# formats, and anthology landing pages. They are not primary texts by the five
# authors and would otherwise create misleading or duplicated library entries.
EXCLUDE_TITLE_PARTS = (
    "传入年表",
    "生活记述",
    "编译马克思",
    "繁体版",
    "毛泽东思想万岁",
)

NOISE_SELECTORS = (
    "script", "style", "noscript", "nav", "header", "footer", "form", "iframe",
    ".navbar", ".nav", ".footer", ".header", ".breadcrumbs", ".menu", "#menu",
)


def fetch(url: str, cache: Path) -> str:
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme != "https" or parsed.netloc != HOST:
        raise ValueError(f"URL outside allow-list: {url}")
    target = cache / f"{hashlib.sha256(url.encode()).hexdigest()}.html"
    if target.exists():
        return target.read_text("utf-8")
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(request, timeout=45) as response:
                raw = response.read()
                charset = response.headers.get_content_charset()
            break
        except Exception:
            if attempt == 2:
                raise
            time.sleep(1.0 + attempt)
    text = decode_html(raw, charset)
    target.write_text(text, "utf-8")
    time.sleep(DELAY_SECONDS)
    return text




def clean_space(value: str) -> str:
    return re.sub(r"\s+", " ", value.replace("\u00a0", " ")).strip()


FOOTNOTE_REF_RE = re.compile(r"^_(?:ftn|edn|etn)ref\d+$", re.I)
FOOTNOTE_DEF_RE = re.compile(r"^_(?:ftn|edn|etn)\d+$", re.I)
FOOTNOTE_TOKEN_RE = re.compile(r"@@MIA_FOOTNOTE_(?:REF|DEF)_\d{5}@@")


def anchor_key(node) -> str:
    return str(node.get("name") or node.get("id") or "").strip().lstrip("#").lower()


def materialize_footnote_tokens(
    value: str,
    reference_tokens: dict[str, tuple[str, str]],
    definition_tokens: dict[str, tuple[str, str]],
) -> tuple[str, list[dict], list[dict]]:
    """Replace private extraction tokens and retain their final text offsets."""
    pieces: list[str] = []
    references: list[dict] = []
    definitions: list[dict] = []
    cursor = 0
    output_length = 0
    for match in FOOTNOTE_TOKEN_RE.finditer(value):
        prefix = value[cursor:match.start()]
        pieces.append(prefix)
        output_length += len(prefix)
        token = match.group(0)
        target = reference_tokens.get(token)
        is_reference = target is not None
        if target is None:
            target = definition_tokens.get(token)
        if target is None:
            replacement = ""
        else:
            footnote_id, replacement = target
            start = output_length
            output_length += len(replacement)
            record = {
                "id": footnote_id,
                "marker": replacement,
                "start": start,
                "end": output_length,
            }
            (references if is_reference else definitions).append(record)
        pieces.append(replacement)
        cursor = match.end()
    suffix = value[cursor:]
    pieces.append(suffix)
    return "".join(pieces), references, definitions


def normalize_url(base: str, href: str) -> str:
    absolute = urllib.parse.urljoin(base, href).split("#", 1)[0]
    parsed = urllib.parse.urlparse(absolute)
    return urllib.parse.urlunparse((parsed.scheme, parsed.netloc, parsed.path, "", parsed.query, ""))


def acceptable_text_link(url: str, label: str, prefixes: tuple[str, ...]) -> bool:
    parsed = urllib.parse.urlparse(url)
    lower_label = label.lower()
    lower_path = parsed.path.lower()
    if parsed.netloc != HOST or not any(parsed.path.startswith(prefix) for prefix in prefixes):
        return False
    if not lower_path.endswith((".htm", ".html", "/")):
        return False
    if any(part in lower_label for part in SKIP_LABEL_PARTS):
        return False
    if any(part.lower() in lower_label for part in EXCLUDE_TITLE_PARTS):
        return False
    if re.search(r"卡尔[・·]?马克思和弗里德里希[・·]?恩格斯.*(?:保罗|paul le blanc)", label, re.I):
        return False
    if any(lower_path.endswith(ext) for ext in (".pdf", ".zip", ".chm", ".jpg", ".png")):
        return False
    if label in {"主页", "首页", "返回", "上一页", "下一页", "目录", "中文版"}:
        return False
    return len(label) >= 2


def infer_year(anchor) -> str:
    """Infer a date only from evidence local to a work link.

    Author indexes frequently wrap an entire period or volume in one parent
    element.  Reading the first year from that large parent assigned the same
    unrelated date to dozens of works.  A title year is good edition-level
    evidence; otherwise accept one nearby year only when the local context is
    compact and unambiguous.  Unknown is safer than a fabricated precise date.
    """
    label = clean_space(anchor.get_text(" ", strip=True))
    parent = anchor.parent
    context = clean_space(parent.get_text(" ", strip=True)) if parent is not None else ""
    return infer_local_year(label, context)


def infer_category(title: str) -> str:
    if re.search(r"(?:致|给).{0,25}(?:信|复信|书)|书信|通告信", title):
        return "书信"
    if any(token in title for token in ("论", "批判", "宣言", "原理", "纲领", "手稿", "战争", "革命和反革命", "起源", "怎么办")):
        return "著作"
    return "文章"


def inventory(cache: Path) -> list[dict]:
    found: dict[str, dict] = {}
    for author in AUTHOR_INDEXES:
        html = fetch(author.url, cache)
        soup = BeautifulSoup(html, "lxml")
        for anchor in soup.select("a[href]"):
            label = clean_space(anchor.get_text(" ", strip=True))
            url = normalize_url(author.url, anchor.get("href", ""))
            if url == author.url or not acceptable_text_link(url, label, author.prefixes):
                continue
            item = found.setdefault(url, {
                "url": url,
                "title": label,
                "authorIds": set(),
                "year": infer_year(anchor),
                "category": infer_category(label),
            })
            item["authorIds"].add(author.id)
            if not item["year"]:
                item["year"] = infer_year(anchor)
        print(f"inventory {author.id}: {sum(author.id in item['authorIds'] for item in found.values())} links")
    return sorted(found.values(), key=lambda item: (sorted(item["authorIds"]), item["year"], item["title"]))


def copyright_restricted(html: str) -> bool:
    text = clean_space(BeautifulSoup(html, "lxml").get_text(" ", strip=True)).lower()
    return bool(re.search(r"(?:copyright|版权所有|©)\s*(?:19|20)\d{2}", text))


def is_navigation(value: str) -> bool:
    return value.lower() in {
        "home", "index", "next", "previous", "up", "contents", "返回", "主页", "首页",
        "下一页", "上一页", "目录", "打印",
    }


def keep_text(value: str, title: str) -> bool:
    if len(value) < 2 or value == title or is_navigation(value):
        return False
    # Older pages put the site breadcrumb on its own lines, for example
    # "中文马克思主义文库" / "->" / "马克思". Those are navigation, not text.
    stripped = value.strip()
    if stripped in {"->", "- >", "→", ">>", "»"}:
        return False
    if stripped.startswith(("-> ", "→ ", ">> ")) and len(stripped) <= 40:
        return False
    lowered = value.lower()
    # The archive name also appears in inline editorial notes (e.g. Mao's
    # paragraph 88 in On Protracted War). Only standalone breadcrumbs are noise.
    if re.match(r"^中文马克思主义文库(?:\s*(?:->|→|>>|»).*)?$", stripped):
        return False
    if re.fullmatch(r"中文马克思主义文库\s*\d{4}年\d{1,2}月(?:\d{1,2}日)?更新[。.]?", stripped):
        return False
    return not any(noise in lowered for noise in (
        "marxists internet archive", "google site search", "contact us", "last updated",
        "download:", "jump to", "privacy policy", "文库管理员",
    ))


def inferred_heading_level(value: str) -> int | None:
    """Recognize standalone headings used by older BR-based MIA pages."""
    if (
        not 1 < len(value) <= 72
        or value.endswith(("。", "！", "？", "；", ";"))
        or not is_credible_inferred_heading(value)
    ):
        return None
    if re.match(r"^第[一二三四五六七八九十百0-9]+[篇章节部卷](?:\s|　|$)", value):
        return 2
    if re.match(r"^[一二三四五六七八九十]{1,4}[、．.]\s*", value):
        return 2
    if re.match(r"^[（(][一二三四五六七八九十0-9]{1,4}[）)]\s*", value):
        return 3
    if re.match(r"^\d{1,2}[．.、]\s*(?=.*[^\W\d_])\S", value):
        return 3
    return None


def add_inferred_sections(chapter: dict, source_url: str) -> None:
    if chapter.get("sections"):
        return
    sections = []
    for paragraph_index, value in enumerate(chapter.get("content", [])):
        level = inferred_heading_level(clean_space(value))
        if level is None:
            continue
        sections.append({
            "id": f"section-{len(sections) + 1:03d}-{hashlib.sha1((source_url + value).encode()).hexdigest()[:8]}",
            "title": clean_space(value),
            "level": level,
            "paragraphIndex": paragraph_index,
        })
    chapter["sections"] = sections


def split_long(value: str, target: int = 1400) -> list[str]:
    if len(value) <= target * 2:
        return [value]
    sentences = re.split(r"(?<=[。！？.!?])\s+|(?<=[。！？])", value)
    result: list[str] = []
    current = ""
    for sentence in map(str.strip, sentences):
        if not sentence:
            continue
        if current and len(current) + len(sentence) > target:
            result.append(current)
            current = sentence
        else:
            current += (" " if current and current[-1].isascii() else "") + sentence
    if current:
        result.append(current)
    return result or [value]


def extract_chapter(url: str, label: str, html: str, ordinal: int) -> dict | None:
    from library_source_reviewed_blocks import repair_document_boundaries
    soup = BeautifulSoup(repair_document_boundaries(url, html), "lxml")
    for table in soup.find_all("table"):
        table._original_source_sha256 = hashlib.sha256(str(table).encode()).hexdigest()
    short_tokens = preserve_short_lines(soup, url, html)
    from library_source_reviewed_blocks import restore_blocks, preserved_repeated_paragraphs, reviewed_plain_lines
    repeated_paragraphs = preserved_repeated_paragraphs(url, html)
    plain_lines = reviewed_plain_lines(url, html)
    restore_blocks(soup, url, html)
    source_boundaries = set()
    for index, node in enumerate(soup.select('[data-source-reviewed-boundary]')):
        token = f'@@MIA_SOURCE_BOUNDARY_{index:05d}@@'
        source_boundaries.add(token)
        node.insert_before(NavigableString('\n' + token + '\n'))
        node.insert_after(NavigableString('\n' + token + '\n'))
    for selector in NOISE_SELECTORS:
        for node in soup.select(selector):
            node.decompose()
    root = soup.select_one("main, article, #content, .content, .article") or soup.body or soup
    for node in list(root.find_all(["table", "ul", "ol"])):
        internal_links = [
            anchor for anchor in node.select("a[href]")
            if str(anchor.get("href", "")).startswith("#")
            and not FOOTNOTE_REF_RE.match(anchor_key(anchor))
            and not FOOTNOTE_DEF_RE.match(anchor_key(anchor))
        ]
        if len(internal_links) >= 3 and len(clean_space(node.get_text(" ", strip=True))) < 3000:
            node.decompose()
    for node in list(root.find_all(["div", "p"])):
        value = clean_space(node.get_text(" ", strip=True))
        if (
            value.startswith(("〔来源〕", "[来源]", "来源："))
            and len(value) < 600
            and not re.search(r"(?:说明|编者注|译者注)[:：]", value)
            and not any(
                parent.name in {'span', 'font'}
                and any(FOOTNOTE_DEF_RE.match(anchor_key(a)) for a in parent.find_all('a'))
                for parent in node.parents
            )
        ):
            node.decompose()

    reference_tokens: dict[str, tuple[str, str]] = {}
    definition_tokens: dict[str, tuple[str, str]] = {}
    source_images = table_tokens(root, url, reference_tokens)
    source_images.update(image_tokens(root, url, reference_tokens))
    note_tokens(root, url, reference_tokens, definition_tokens, short_tokens)
    # Preserve page credits in the body, but do not let the final note absorb
    # a separate credit block outside its original SPAN/FONT container.
    note_containers = []
    for anchor in root.find_all('a'):
        if FOOTNOTE_DEF_RE.match(anchor_key(anchor)):
            note_containers.extend(p for p in anchor.parents if p.name in {'span', 'font'})
    note_end_tokens = set()
    from library_source_note_boundaries import reviewed_note_end_nodes
    for node in reviewed_note_end_nodes(root, url, html):
        token = f'@@MIA_NOTE_END_{len(note_end_tokens):05d}@@'
        note_end_tokens.add(token)
        node.insert_after(NavigableString('\n' + token + '\n'))
    for node in root.find_all(['p', 'div']):
        value = clean_space(node.get_text(' ', strip=True))
        if not re.match(r'^(?:感谢.+(?:录入|校对)|来源\s*[:：])', value):
            continue
        if any(p is container for p in node.parents for container in note_containers):
            continue
        token = f'@@MIA_NOTE_END_{len(note_end_tokens):05d}@@'
        note_end_tokens.add(token)
        node.insert_before(NavigableString('\n' + token + '\n'))
    for anchor in list(root.select("a[href]")):
        key = anchor_key(anchor)
        href = str(anchor.get("href", "")).strip()
        target = href.lstrip("#").lower() if href.startswith("#") else ""
        if not FOOTNOTE_REF_RE.match(key) or not FOOTNOTE_DEF_RE.match(target):
            continue
        marker = clean_space(anchor.get_text(" ", strip=True)) or "[注]"
        token = f"@@MIA_FOOTNOTE_REF_{len(reference_tokens):05d}@@"
        reference_tokens[token] = (target, marker)
        anchor.replace_with(NavigableString(token))
    for anchor in list(root.find_all("a")):
        key = anchor_key(anchor)
        if not FOOTNOTE_DEF_RE.match(key):
            continue
        marker = clean_space(anchor.get_text(" ", strip=True)) or "[注]"
        token = f"@@MIA_FOOTNOTE_DEF_{len(definition_tokens):05d}@@"
        definition_tokens[token] = (key, marker)
        anchor.replace_with(NavigableString(token))

    restore_numbered_headings(root, url)
    heading = root.find(["h1", "h2", "h3"])
    title = clean_space(label or (heading.get_text(" ", strip=True) if heading else ""))
    markers: dict[str, tuple[str, int, str]] = {}
    for index, node in enumerate(list(root.find_all(["h2", "h3", "h4", "h5", "h6"]))):
        source_value = clean_space(node.get_text(" ", strip=True))
        value, _, _ = materialize_footnote_tokens(
            source_value, reference_tokens, definition_tokens
        )
        value = clean_space(value)
        if (
            not value
            or (value == title and not node.get('data-source-reviewed-heading'))
            or is_navigation(value)
            or (len(value) == 1 and value.isascii())
            or (not node.get('data-source-reviewed-heading') and not is_credible_inferred_heading(value))
        ):
            node.decompose()
            continue
        marker = f"@@MIA_SECTION_{index}@@"
        markers[marker] = (value, max(2, min(4, int(node.name[1]) - 1)), source_value)
        node.replace_with(NavigableString(f"\n{marker}\n"))

    # A large part of the Chinese archive predates semantic HTML and uses BR
    # tags directly inside BODY.  Turning visual breaks and block boundaries
    # into newlines captures the complete article instead of only its preface.
    for node in root.find_all("br"):
        node.replace_with(NavigableString("\n"))
    for node in root.find_all(["p", "blockquote", "li", "div", "table", "tr"]):
        node.insert_before(NavigableString("\n"))
        node.insert_after(NavigableString("\n"))

    paragraphs: list[str] = []
    paragraph_boundaries: list[bool] = []
    sections: list[dict] = []
    footnote_references: list[dict] = []
    footnote_definitions: list[dict] = []
    note_end_positions: list[int] = []
    for raw_line in root.get_text("", strip=False).splitlines():
        line = clean_space(raw_line)
        if line in source_boundaries:
            if paragraph_boundaries: paragraph_boundaries[-1] = True
            continue
        if not line:
            continue
        if line in note_end_tokens:
            note_end_positions.append(len(paragraphs))
            if paragraph_boundaries:
                paragraph_boundaries[-1] = True
            continue
        if line in markers:
            section_title, level, source_value = markers[line]
            paragraph_value, references, definitions = materialize_footnote_tokens(
                source_value, reference_tokens, definition_tokens
            )
            paragraph_value = clean_space(paragraph_value)
            paragraph_index = len(paragraphs)
            footnote_references.extend({**reference, "paragraphIndex": paragraph_index} for reference in references)
            footnote_definitions.extend({**definition, "paragraphIndex": paragraph_index} for definition in definitions)
            sections.append({
                "id": f"section-{len(sections) + 1:03d}-{hashlib.sha1((url + section_title).encode()).hexdigest()[:8]}",
                "title": section_title,
                "level": level,
                "paragraphIndex": len(paragraphs),
            })
            paragraphs.append(paragraph_value)
            paragraph_boundaries.append(True)
            continue
        if not keep_text(line, title):
            continue
        values = split_long(line)
        for split_index, value in enumerate(values):
            value, references, definitions = materialize_footnote_tokens(
                value, reference_tokens, definition_tokens
            )
            reviewed_heading = short_tokens.get(value)
            value = clean_space(materialize_short_lines(value, short_tokens))
            if not value:
                continue
            paragraph_index = len(paragraphs)
            footnote_references.extend({**reference, "paragraphIndex": paragraph_index} for reference in references)
            footnote_definitions.extend({**definition, "paragraphIndex": paragraph_index} for definition in definitions)
            inferred_level = reviewed_heading["level"] if reviewed_heading and reviewed_heading["heading"] else inferred_heading_level(value)
            if value in plain_lines:
                inferred_level = None
            if inferred_level is not None and not any(section["paragraphIndex"] == len(paragraphs) for section in sections):
                sections.append({
                    "id": f"section-{len(sections) + 1:03d}-{hashlib.sha1((url + value).encode()).hexdigest()[:8]}",
                    "title": value,
                    "level": inferred_level,
                    "paragraphIndex": len(paragraphs),
                })
            paragraphs.append(value)
            paragraph_boundaries.append(
                split_index > 0
                or raw_line.startswith(("　　", "\u3000\u3000"))
                or inferred_level is not None
                or bool(definitions)
                or bool(re.match(r"^(?:[①②③④⑤⑥⑦⑧⑨⑩]|[一二三四五六七八九十]+[、．.]|\d+[、．.)])", value))
            )

    # Old archive pages often used BR as visual word wrapping. Repair only the
    # clearest false boundaries: a short, unfinished prose line followed by
    # another non-structural line. All offsets are remapped so footnotes remain
    # clickable after the merge.
    repaired: list[str] = []
    repaired_boundaries: list[bool] = []
    repair_map: list[tuple[int, int]] = []
    terminal = ("。", "！", "？", "；", "：", ";", "!", "?", "…", "）", ")", "】", "》", "”", "’")
    for index, value in enumerate(paragraphs):
        structural = paragraph_boundaries[index]
        can_merge = (
            bool(repaired)
            and not structural
            and not repaired_boundaries[-1]
            and len(repaired[-1]) <= 180
            and len(value) <= 500
            and not repaired[-1].endswith(terminal)
        )
        if can_merge:
            separator = " " if repaired[-1][-1].isascii() and value[0].isascii() else ""
            shift = len(repaired[-1]) + len(separator)
            repaired[-1] += separator + value
            repair_map.append((len(repaired) - 1, shift))
        else:
            repaired.append(value)
            repaired_boundaries.append(structural)
            repair_map.append((len(repaired) - 1, 0))
    paragraphs = repaired
    for record in footnote_references:
        new_index, shift = repair_map[record["paragraphIndex"]]
        record["paragraphIndex"] = new_index
        record["start"] += shift
        record["end"] += shift
    for record in footnote_definitions:
        new_index, shift = repair_map[record["paragraphIndex"]]
        record["paragraphIndex"] = new_index
        record["start"] += shift
        record["end"] += shift
    for section in sections:
        section["paragraphIndex"] = repair_map[section["paragraphIndex"]][0]
    deduped: list[str] = []
    paragraph_index_map: list[int] = []
    for value in paragraphs:
        if not deduped or value != deduped[-1] or value in repeated_paragraphs:
            deduped.append(value)
        paragraph_index_map.append(len(deduped) - 1)
    # Primary texts also include short inscriptions, instructions and letters.
    # The author inventory and navigation cleanup determine scope; length is
    # only a guard against an empty extracted page.
    if len("".join(deduped)) < 40:
        return None

    mapped_references = [
        {**reference, "paragraphIndex": paragraph_index_map[reference["paragraphIndex"]]}
        for reference in footnote_references
        if reference["paragraphIndex"] < len(paragraph_index_map)
    ]
    mapped_definitions = [
        {**definition, "paragraphIndex": paragraph_index_map[definition["paragraphIndex"]]}
        for definition in footnote_definitions
        if definition["paragraphIndex"] < len(paragraph_index_map)
    ]
    definition_by_id = {}
    for definition in mapped_definitions:
        definition_by_id.setdefault(definition["id"], definition)
    ordered_definitions = sorted(definition_by_id.values(), key=lambda item: item["paragraphIndex"])
    definition_starts = sorted({d["paragraphIndex"] for d in ordered_definitions})
    next_definition_start = {
        start: definition_starts[index + 1] if index + 1 < len(definition_starts) else len(deduped)
        for index, start in enumerate(definition_starts)
    }
    definition_end_by_id = {d["id"]: next_definition_start[d["paragraphIndex"]] for d in ordered_definitions}
    references_by_id: dict[str, list[dict]] = {}
    for reference in mapped_references:
        references_by_id.setdefault(reference["id"], []).append(reference)
    footnotes = []
    for footnote_id, references in references_by_id.items():
        if footnote_id in source_images:
            footnotes.append({**source_images[footnote_id], "references": [
                {"paragraphIndex": r["paragraphIndex"], "start": r["start"], "end": r["end"]}
                for r in references
            ]})
            continue
        definition = definition_by_id.get(footnote_id)
        if definition is None:
            continue
        start_index = definition["paragraphIndex"]
        end_index = definition_end_by_id[footnote_id]
        # A final note must not absorb a separately headed index that follows it.
        index_boundaries = [
            paragraph_index_map[section['paragraphIndex']]
            for section in sections
            if section['title'] in {'人名索引', '名词索引', '书刊索引', '索引'}
            and start_index < paragraph_index_map[section['paragraphIndex']] < end_index
        ]
        if index_boundaries:
            end_index = min(index_boundaries)
        credit_boundaries = [
            paragraph_index_map[repair_map[index][0]]
            for index in note_end_positions
            if index < len(repair_map)
            and start_index < paragraph_index_map[repair_map[index][0]] < end_index
        ]
        if credit_boundaries:
            end_index = min(credit_boundaries)
        first = deduped[start_index]
        # Adjacent labels can share one definition, e.g. [43] [43a]. Remove
        # every defining label from the payload, while retaining both IDs.
        for label in sorted((d for d in ordered_definitions if d['paragraphIndex'] == start_index),
                            key=lambda d: d['start'], reverse=True):
            first = first[:label['start']] + first[label['end']:]
        content = [
            first.strip(),
            *deduped[start_index + 1:end_index],
        ]
        content = [value for value in content if value]
        if not content:
            continue
        footnotes.append({
            "id": footnote_id,
            "marker": references[0]["marker"],
            "content": content,
            "references": [
                {
                    "paragraphIndex": reference["paragraphIndex"],
                    "start": reference["start"],
                    "end": reference["end"],
                }
                for reference in references
            ],
        })
    return {
        "id": f"chapter-{ordinal:03d}-{hashlib.sha1(url.encode()).hexdigest()[:10]}",
        "title": title,
        "level": 1,
        "content": deduped,
        "footnotes": footnotes,
        "sections": [
            {**section, "paragraphIndex": paragraph_index_map[section["paragraphIndex"]]}
            for section in sections
            if section["paragraphIndex"] < len(paragraph_index_map)
        ],
    }


def chapter_links(url: str, html: str, prefixes: tuple[str, ...]) -> list[tuple[str, str]]:
    path = urllib.parse.urlparse(url).path.lower()
    if "index" not in path.rsplit("/", 1)[-1] and not path.endswith("/"):
        return []
    directory = path.rsplit("/", 1)[0] + "/"
    soup = BeautifulSoup(html, "lxml")
    result: list[tuple[str, str]] = []
    seen: set[str] = set()
    for anchor in soup.select("a[href]"):
        label = clean_space(anchor.get_text(" ", strip=True))
        child = normalize_url(url, anchor.get("href", ""))
        child_path = urllib.parse.urlparse(child).path.lower()
        if child == url or child in seen or not child_path.startswith(directory):
            continue
        if acceptable_text_link(child, label, prefixes):
            seen.add(child)
            result.append((child, label))
    return result[:120] if len(result) >= 2 else []


def deduplicate_books(books: list[dict]) -> list[dict]:
    """Deduplicate equal texts, preserving distinct works with the same title."""
    found = {}
    for book in books:
        compact = lambda text: re.sub(r"\s+", "", text)
        text = compact("".join(p for c in book["chapters"] for p in c["content"]))
        notes = sorted((n["marker"], compact("".join(n["content"])))
                       for c in book["chapters"] for n in c.get("footnotes", []))
        identity = hashlib.sha256(json.dumps([text, notes], ensure_ascii=False).encode()).hexdigest()
        key = (tuple(sorted(book["authorIds"])), clean_space(book["titleZh"]).lower(), identity)
        previous = found.get(key)
        if previous is None or len(book["chapters"]) > len(previous["chapters"]):
            found[key] = book
    return list(found.values())


def build_work(item: dict, cache: Path) -> dict | None:
    url = item["url"]
    html = fetch(url, cache)
    if copyright_restricted(html):
        print(f"skip copyright: {item['title']} ({url})")
        return None
    prefixes = tuple(prefix for author in AUTHOR_INDEXES if author.id in item["authorIds"] for prefix in author.prefixes)
    links = chapter_links(url, html, prefixes) or [(url, item["title"])]
    chapters = []
    for ordinal, (chapter_url, label) in enumerate(links, 1):
        chapter_html = html if chapter_url == url else fetch(chapter_url, cache)
        chapter = extract_chapter(chapter_url, label, chapter_html, ordinal)
        if chapter:
            chapters.append(chapter)
    if not chapters:
        print(f"skip empty: {item['title']} ({url})")
        return None
    primary = sorted(item["authorIds"])[0]
    return {
        "id": f"{primary}-work-{hashlib.sha1(url.encode()).hexdigest()[:12]}",
        "authorIds": sorted(item["authorIds"]),
        "seriesId": None,
        "titleZh": item["title"],
        "titleEn": item["title"],
        "language": "zh",
        "category": "著作" if len(chapters) > 1 else item["category"],
        "year": item["year"],
        "sourceUrl": url,
        "sourceCredit": "Marxists Internet Archive",
        "translator": "",
        "rights": "PUBLIC_DOMAIN",
        "description": "",
        "chapters": chapters,
    }


def load_existing(library_dir: Path) -> tuple[list[dict], list[dict]]:
    catalog = json.loads((library_dir / "catalog.json").read_text("utf-8"))
    books = [
        json.loads(path.read_text("utf-8"))["books"][0]
        for path in sorted((library_dir / "books").glob("*.json"))
    ]
    for book in books:
        book.setdefault("category", "著作")
        book.pop("toc", None)
        for chapter in book.get("chapters", []):
            add_inferred_sections(chapter, book["sourceUrl"])
    return catalog["authors"], books


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--library", type=Path, default=Path("app/src/main/assets/library"))
    parser.add_argument("--cache", type=Path, default=Path(".content-cache"))
    parser.add_argument("--output", type=Path, default=Path(".generated/library-full.json"))
    parser.add_argument("--limit", type=int, default=0, help="Diagnostic limit; 0 builds all inventory")
    parser.add_argument("--workers", type=int, default=4, help="Polite parallel fetch workers")
    args = parser.parse_args()
    args.cache.mkdir(parents=True, exist_ok=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)

    authors, existing = load_existing(args.library)
    items = inventory(args.cache)
    if args.limit:
        items = items[: args.limit]
    print(f"candidate works: {len(items)}")

    built = []
    with ThreadPoolExecutor(max_workers=max(1, args.workers)) as executor:
        futures = {executor.submit(build_work, item, args.cache): (index, item) for index, item in enumerate(items, 1)}
        for future in as_completed(futures):
            index, item = futures[future]
            try:
                work = future.result()
            except Exception as exc:  # keep a long archival build resumable
                print(f"ERROR {item['url']}: {exc}", flush=True)
                continue
            if work:
                built.append(work)
                print(f"[{index}/{len(items)}] {work['titleZh']}: {len(work['chapters'])} chapter(s)", flush=True)

    built = sorted(deduplicate_books(built), key=lambda book: (book["authorIds"], book["year"], book["titleZh"]))

    # Refresh the original core titles too. Preserve stable book/chapter ids so
    # existing progress, bookmarks and notes keep working after fuller bodies
    # and section-level navigation are added.
    by_url = {book["sourceUrl"]: book for book in built}
    refreshed_existing = []
    for old in existing:
        fresh = by_url.pop(old["sourceUrl"], None)
        if fresh is None or (
            len(old.get("chapters", [])) > 1
            and len(fresh.get("chapters", [])) < len(old["chapters"])
        ):
            refreshed_existing.append(old)
            continue
        old_chapters = old.get("chapters", [])
        fresh_chapters = fresh.get("chapters", [])
        for index, chapter in enumerate(fresh_chapters):
            if index < len(old_chapters):
                chapter["id"] = old_chapters[index]["id"]
        merged = dict(old)
        merged["chapters"] = fresh_chapters
        refreshed_existing.append(merged)
    built = list(by_url.values())
    books = refreshed_existing + built
    root = {
        "schemaVersion": 1,
        "packId": "marxism-library-five-authors",
        "packTitle": "马列原典",
        "authors": authors,
        "books": books,
    }
    args.output.write_text(json.dumps(root, ensure_ascii=False, indent=2), "utf-8")
    print(
        f"Wrote {args.output}: {len(books)} works, "
        f"{sum(len(book['chapters']) for book in books)} chapters"
    )


if __name__ == "__main__":
    main()
