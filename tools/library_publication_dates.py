"""Publication metadata from explicit MIA source credits, never title years.

Writing dates, translation editions, navigation years and historical events
are not publication evidence. Retain the exact credit for editorial review.
"""
from __future__ import annotations

import datetime
import json
from functools import lru_cache
from pathlib import Path
import re

DATE = re.compile(r"(?P<year>(?:18|19|20)\d{2})年(?:\s*(?P<month>\d{1,2})月(?:\s*(?P<day>\d{1,2})(?:日|(?=[、和至―—–－-]\d{1,2})))?)?")
YEAR_RANGE = re.compile(r"((?:18|19|20)\d{2})[―—–－-]((?:18|19|20)\d{2})年")
PUBLICATION = re.compile(r"(?:第一次|首次|最初)?(?:发表(?:于|在)?|刊载于|载于|出版于)")
FIELDS = ("publicationDate", "publicationDateEnd", "publicationDateBasis", "publicationDateSourceUrl", "publicationDateNote")


def parse_source_date(value: str) -> tuple[str, str, str] | None:
    value = value.strip()
    span = YEAR_RANGE.match(value)
    if span:
        start, end = span.groups()
        return (start, end, "源站标注的发表年份范围") if start <= end else None
    match = DATE.match(value)
    if not match:
        return None
    y, m, d = (int(v) if v else None for v in match.groups())
    note = ""
    # MIA prints Russian old/new style dates together. Use the parenthesized
    # Gregorian date, including month/year rollover, while retaining evidence.
    new_style = re.match(r"(?:[、和及至―—–－-]\d{1,2}日?)*[（(](?:(\d{1,2})月)?(\d{1,2})日?(?:[、和及至―—–－-]\d{1,2}日?)*[）)]", value[match.end():])
    if new_style and m and d:
        month = int(new_style[1]) if new_style[1] else m
        y += int(month < m)
        m, d = month, int(new_style[2])
        note = "采用源站括注的新历日期"
    try:
        datetime.date(y, m or 1, d or 1)
    except ValueError:
        return None
    date = f"{y:04d}" + (f"-{m:02d}" if m else "") + (f"-{d:02d}" if d else "")
    if re.match(r"[、和及至―—–－-]", value[match.end():]):
        note = note or "采用源站所列分期发表的起始日期"
    return date, "", note


def publication_credit(text: str) -> tuple[str, str, str] | None:
    text = text.strip()
    text = re.sub(r"^(?:(?:卡(?:尔)?|弗(?:里德里希)?)[·・．.]?)?(?:马克思|恩格斯|列宁|斯大林|毛泽东)\s*", "", text)
    # Source-credit paragraphs are short, standalone bibliographic statements.
    # An isolated year in prose or a dated title is deliberately insufficient.
    if len(text) > 500 or not re.match(r"^(?:写于|作于|(?:第一次|首次|最初)?(?:载于|发表于|发表在|刊载于)|\d{4}年)", text):
        return None
    if re.match(r"\d{4}年", text):
        if re.match(r"\d{4}年(?:\d{1,2}月(?:\d{1,2}日)?)?(?:第一次|首次|最初)(?:发表|载于|刊载)", text):
            return parse_source_date(text)
        if not re.match(r"\d{4}年\d{1,2}月\d{1,2}日于[^，。]{1,15}载于", text):
            return None
    for match in PUBLICATION.finditer(text):
        if match.start() > 90:
            continue
        prefix = text[:match.start()]
        # Avoid a later translation when the only publication credit is for it.
        if re.search(r"(?:译成|译文|俄文全文|中译本|译本).{0,20}$", prefix):
            continue
        tail = text[match.end():].lstrip(" ：:")
        result = parse_source_date(tail)
        if result:
            return result
        # “写于1913年6月1925年第一次载于…”: the publication year is
        # immediately before 第一次, rather than attached to the writing date.
        previous = re.search(r"((?:18|19|20)\d{2}年)$", prefix)
        if previous and match[0].startswith(("第一次", "首次")):
            return parse_source_date(previous[1])
    return None


def infer_publication(book: dict) -> dict:
    url = book.get("sourceUrl", "")
    if not url.startswith("https://www.marxists.org/chinese/"):
        return {}
    # A collection's constituent articles do not date the collection itself.
    # Multi-chapter volumes require a work-level source credit reviewed below.
    if len(book.get("chapters", [])) != 1:
        return {}
    paragraphs = book["chapters"][0].get("content", [])
    candidates = []
    for index, text in enumerate(paragraphs):
        if re.match(r"^(?:注\s*释|尾注|原脚注)[：:]?$", text.strip()):
            break
        if re.match(r"^\s*[\[［〔【]\d+[\]］〕】]", text):
            continue
        credit = publication_credit(text)
        if credit:
            candidates.append((credit, text))
        if re.search(r"(?:注\s*释|原脚注)[：:]?$", text.strip()):
            break
    if not candidates:
        return {}
    (date, end, note), basis = min(candidates, key=lambda item: item[0][0])
    return dict(zip(FIELDS, (date, end, basis, url, note)))


@lru_cache(maxsize=1)
def reviewed_publications() -> dict:
    return json.loads(Path(__file__).with_name('library_publication_overrides.json').read_text(encoding='utf-8'))['books']


def apply_publication(book: dict) -> None:
    # An explicitly reviewed field takes precedence over automatic extraction.
    reviewed = reviewed_publications().get(book.get('id'))
    if reviewed:
        book.update(reviewed)
        return
    basis = book.get("publicationDateBasis")
    if not book.get("publicationDate") or (basis and any(basis in c.get("content", []) for c in book.get("chapters", []))):
        book.update(infer_publication(book))
