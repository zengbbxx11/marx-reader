#!/usr/bin/env python3
"""Build the reviewed, offline Android catalog from MIA HTML pages.

This utility is intentionally developer-side only. The Android app has no network
permission and consumes only the generated JSON.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import time
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path

try:
    from bs4 import BeautifulSoup
except ImportError as exc:
    raise SystemExit("Install tools/requirements.txt before running this utility") from exc

from text_encoding import decode_html


USER_AGENT = "MarxismLibrary/0.2 (offline content builder; respectful crawler)"
ALLOWED_HOST = "www.marxists.org"
REQUEST_DELAY_SECONDS = 0.7


AUTHORS = [
    {"id": "marx", "nameZh": "马克思", "nameEn": "Karl Marx", "years": "1818–1883", "description": "哲学家、政治经济学家，《资本论》作者。", "color": "7A2024"},
    {"id": "engels", "nameZh": "恩格斯", "nameEn": "Friedrich Engels", "years": "1820–1895", "description": "思想家，马克思的长期合作者，并整理出版《资本论》第二、三卷。", "color": "315C4C"},
    {"id": "lenin", "nameZh": "列宁", "nameEn": "Vladimir Lenin", "years": "1870–1924", "description": "马克思主义理论家和革命家。", "color": "8B4B22"},
    {"id": "stalin", "nameZh": "斯大林", "nameEn": "Joseph Stalin", "years": "1878–1953", "description": "苏联政治人物及著述者。", "color": "4F4F63"},
    {"id": "mao", "nameZh": "毛泽东", "nameEn": "Mao Zedong", "years": "1893–1976", "description": "中国革命家和著述者。", "color": "9A2E22"},
]


@dataclass(frozen=True)
class Source:
    id: str
    author_ids: tuple[str, ...]
    series_id: str | None
    title_zh: str
    title_en: str
    language: str
    year: str
    url: str
    mode: str = "single"
    link_pattern: str = ""
    translator: str = ""
    rights: str = "PUBLIC_DOMAIN"
    description: str = ""
    max_pages: int = 100


SOURCES = [
    Source("capital-v1-zh", ("marx",), "capital-v1", "资本论·第一卷", "Capital, Volume I", "zh", "1867", "https://www.marxists.org/chinese/marx/capital/index.htm", "index", r"/chinese/marx/capital/(?!index)[^/#?]+\.htm$", description="资本的生产过程。", max_pages=40),
    Source("capital-v1-en", ("marx",), "capital-v1", "资本论·第一卷", "Capital, Volume I", "en", "1867", "https://www.marxists.org/archive/marx/works/1867-c1/index.htm", "index", r"/archive/marx/works/1867-c1/(?!index)[^/#?]+\.htm$", "Samuel Moore and Edward Aveling", "CC_BY_SA", "The Process of Production of Capital.", 60),
    Source("capital-v2-zh", ("marx", "engels"), "capital-v2", "资本论·第二卷", "Capital, Volume II", "zh", "1885", "https://www.marxists.org/chinese/marx/capital/marxist.org-chinese-marx-capital-vol2-01.htm", "index", r"/chinese/marx-engels/24/[^/#?]+\.htm$", description="资本的流通过程，由恩格斯整理出版。", max_pages=35),
    Source("capital-v2-en", ("marx", "engels"), "capital-v2", "资本论·第二卷", "Capital, Volume II", "en", "1885", "https://www.marxists.org/archive/marx/works/1885-c2/index.htm", "index", r"/archive/marx/works/1885-c2/(?!index)[^/#?]+\.htm$", "Ernest Untermann", "PUBLIC_DOMAIN", "The Process of Circulation of Capital.", 40),
    Source("capital-v3-zh", ("marx", "engels"), "capital-v3", "资本论·第三卷", "Capital, Volume III", "zh", "1894", "https://www.marxists.org/chinese/marx/capital/marxist.org-chinese-marx-capital-vol3-01.htm", "index", r"/chinese/marx-engels/25/[^/#?]+\.htm$", description="资本主义生产的总过程，由恩格斯整理出版。", max_pages=70),
    Source("capital-v3-en", ("marx", "engels"), "capital-v3", "资本论·第三卷", "Capital, Volume III", "en", "1894", "https://www.marxists.org/archive/marx/works/1894-c3/index.htm", "index", r"/archive/marx/works/1894-c3/(?!index)[^/#?]+\.htm$", rights="PUBLIC_DOMAIN", description="The Process of Capitalist Production as a Whole.", max_pages=70),
    Source("manifesto-zh-1920", ("marx", "engels"), "communist-manifesto", "共产党宣言（1920年陈望道译本）", "Manifesto of the Communist Party", "zh", "1848", "https://www.marxists.org/chinese/marx/mia-chinese-marx-184002-cwd.htm", translator="陈望道", description="1920年8月第一个中文全译本。"),
    Source("manifesto-en", ("marx", "engels"), "communist-manifesto", "共产党宣言", "Manifesto of the Communist Party", "en", "1848", "https://www.marxists.org/archive/marx/works/1848/communist-manifesto/index.htm", "index", r"/archive/marx/works/1848/communist-manifesto/(?!index)[^/#?]+\.htm$", "Samuel Moore", "CC_BY_SA", max_pages=16),
    Source("principles-zh", ("engels",), "principles-communism", "共产主义原理", "The Principles of Communism", "zh", "1847", "https://www.marxists.org/chinese/marx-engels/04/025.htm"),
    Source("principles-en", ("engels",), "principles-communism", "共产主义原理", "The Principles of Communism", "en", "1847", "https://www.marxists.org/archive/marx/works/1847/11/prin-com.htm", translator="Paul Sweezy"),
    Source("state-revolution-zh", ("lenin",), "state-revolution", "国家与革命", "The State and Revolution", "zh", "1917", "https://www.marxists.org/chinese/lenin/191708-09/index.htm", "index", r"/chinese/lenin/191708-09/[0-9]+\.htm$", description="马克思主义关于国家的学说与无产阶级在革命中的任务。", max_pages=15),
    Source("state-revolution-en", ("lenin",), "state-revolution", "国家与革命", "The State and Revolution", "en", "1917", "https://www.marxists.org/archive/lenin/works/1917/staterev/index.htm", "index", r"/archive/lenin/works/1917/staterev/(?!index)[^/#?]+\.htm$", rights="PUBLIC_DOMAIN", max_pages=12),
    Source("foundations-leninism-zh", ("stalin",), "foundations-leninism", "论列宁主义基础", "The Foundations of Leninism", "zh", "1924", "https://www.marxists.org/chinese/stalin/mia-chinese-stalin-192404.htm"),
    Source("foundations-leninism-en", ("stalin",), "foundations-leninism", "论列宁主义基础", "The Foundations of Leninism", "en", "1924", "https://www.marxists.org/reference/archive/stalin/works/1924/foundations-leninism/index.htm", "index", r"/reference/archive/stalin/works/1924/foundations-leninism/(?!index)[^/#?]+\.htm$", max_pages=12),
    Source("on-practice-zh", ("mao",), "on-practice", "实践论", "On Practice", "zh", "1937", "https://www.marxists.org/chinese/maozedong/marxist.org-chinese-mao-193707.htm"),
    Source("on-practice-en", ("mao",), "on-practice", "实践论", "On Practice", "en", "1937", "https://www.marxists.org/reference/archive/mao/selected-works/volume-1/mswv1_16.htm"),
]


def fetch(url: str, cache: Path) -> str:
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme != "https" or parsed.netloc != ALLOWED_HOST:
        raise ValueError(f"URL outside allow-list: {url}")
    key = hashlib.sha256(url.encode()).hexdigest() + ".html"
    target = cache / key
    if target.exists():
        return target.read_text("utf-8")
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=45) as response:
        raw = response.read()
        content_type = response.headers.get_content_charset()
    text = decode_html(raw, content_type)
    target.write_text(text, "utf-8")
    time.sleep(REQUEST_DELAY_SECONDS)
    return text


def page_links(source: Source, html: str) -> list[tuple[str, str]]:
    if source.mode == "single":
        return [(source.url, source.title_zh if source.language == "zh" else source.title_en)]
    soup = BeautifulSoup(html, "lxml")
    pattern = re.compile(source.link_pattern, flags=re.I)
    links: list[tuple[str, str]] = []
    seen: set[str] = set()
    for anchor in soup.select("a[href]"):
        absolute = urllib.parse.urljoin(source.url, anchor.get("href", "")).split("#", 1)[0]
        parsed = urllib.parse.urlparse(absolute)
        if parsed.netloc != ALLOWED_HOST or not pattern.search(parsed.path) or absolute in seen:
            continue
        label = clean_space(anchor.get_text(" ", strip=True))
        if not label or is_navigation(label):
            continue
        seen.add(absolute)
        links.append((absolute, label))
    if not links:
        links.append((source.url, source.title_zh if source.language == "zh" else source.title_en))
    return links[: source.max_pages]


def extract_chapter(url: str, label: str, html: str, ordinal: int) -> dict:
    soup = BeautifulSoup(html, "lxml")
    for selector in (
        "script", "style", "noscript", "nav", "header", "footer", "form", "iframe",
        ".navbar", ".nav", ".footer", ".header", ".breadcrumbs", ".menu", "#menu",
    ):
        for node in soup.select(selector):
            node.decompose()
    root = soup.select_one("main, article, #content, .content, .article") or soup.body or soup
    title_node = root.find(["h1", "h2", "h3"])
    title = clean_space(label or (title_node.get_text(" ", strip=True) if title_node else ""))
    paragraphs: list[str] = []
    for node in root.find_all(["h1", "h2", "h3", "h4", "p", "blockquote", "li"]):
        if node.find_parent(["p", "blockquote", "li"]):
            continue
        text = clean_space(node.get_text(" ", strip=True))
        if keep_text(text, title):
            paragraphs.append(text)
    if len("".join(paragraphs)) < 500:
        paragraphs = [clean_space(line) for line in root.get_text("\n").splitlines()]
        paragraphs = [line for line in paragraphs if keep_text(line, title)]
    paragraphs = deduplicate(part for paragraph in paragraphs for part in split_long_paragraph(paragraph))
    return {
        "id": f"chapter-{ordinal:03d}-{hashlib.sha1(url.encode()).hexdigest()[:10]}",
        "title": title or f"Chapter {ordinal}",
        "level": 1,
        "content": paragraphs,
    }


def clean_space(text: str) -> str:
    return re.sub(r"\s+", " ", text.replace("\u00a0", " ")).strip()


def is_navigation(text: str) -> bool:
    lowered = text.lower()
    return lowered in {"home", "index", "next", "previous", "up", "contents", "返回", "主页", "下一页", "上一页"}


def keep_text(text: str, title: str) -> bool:
    if len(text) < 2 or text == title or is_navigation(text):
        return False
    lowered = text.lower()
    noise = (
        "marxists internet archive", "google site search", "contact us", "last updated",
        "中文马克思主义文库 ->", "download:", "jump to", "privacy policy",
    )
    if any(item in lowered for item in noise):
        return False
    return True


def split_long_paragraph(value: str, target: int = 1400) -> list[str]:
    """Split malformed legacy HTML paragraphs without losing any source text."""
    if len(value) <= target * 2:
        return [value]
    sentences = re.split(r"(?<=[。！？.!?])\s+|(?<=[。！？])", value)
    chunks: list[str] = []
    current = ""
    for sentence in sentences:
        sentence = sentence.strip()
        if not sentence:
            continue
        if current and len(current) + len(sentence) > target:
            chunks.append(current)
            current = sentence
        else:
            current += (" " if current and current[-1].isascii() else "") + sentence
    if current:
        chunks.append(current)
    return chunks or [value]


def deduplicate(values) -> list[str]:
    result: list[str] = []
    for value in values:
        if result and value == result[-1]:
            continue
        result.append(value)
    return result


def build(source: Source, cache: Path) -> dict:
    index_html = fetch(source.url, cache)
    links = page_links(source, index_html)
    chapters = []
    for ordinal, (url, label) in enumerate(links, 1):
        html = index_html if url == source.url else fetch(url, cache)
        chapter = extract_chapter(url, label, html, ordinal)
        if chapter["content"]:
            chapters.append(chapter)
    print(f"{source.id}: {len(chapters)} chapters, {sum(len(c['content']) for c in chapters)} paragraphs")
    return {
        "id": source.id,
        "authorIds": list(source.author_ids),
        "seriesId": source.series_id,
        "titleZh": source.title_zh,
        "titleEn": source.title_en,
        "language": source.language,
        "year": source.year,
        "sourceUrl": source.url,
        "sourceCredit": "Marxists Internet Archive",
        "translator": source.translator,
        "rights": source.rights,
        "description": source.description,
        "chapters": chapters,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=Path("app/src/main/assets/library.json"))
    parser.add_argument("--cache", type=Path, default=Path(".content-cache"))
    parser.add_argument("--only", nargs="*", help="Build only the given source ids")
    args = parser.parse_args()
    args.cache.mkdir(parents=True, exist_ok=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    selected = [source for source in SOURCES if not args.only or source.id in args.only]
    catalog = {
        "schemaVersion": 1,
        "packId": "builtin-core-v1",
        "packTitle": "马列原典",
        "authors": AUTHORS,
        "books": [build(source, args.cache) for source in selected],
    }
    args.output.write_text(json.dumps(catalog, ensure_ascii=False, indent=2), "utf-8")
    print(f"Wrote {args.output} ({args.output.stat().st_size / 1024 / 1024:.2f} MiB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
