"""Recover explicit printed notes without changing a single source character.

HTML anchors are not the only note convention in the archive. Older editions
print numbered notes at the end of a chapter (or in a separate notes chapter).
Definitions, scopes and references are kept separate so repeated numbers never
silently borrow a definition from another work or another local note section.
Offsets here use Python character indexes, like the other library builders.
"""
from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
import hashlib
import json
from functools import lru_cache
from pathlib import Path
import re


NUMBER = r"(?:\d{1,4}[a-z]?|[①-⑳])"
MARKER_RE = re.compile(
    rf"\[\s*{NUMBER}\s*\]|［\s*{NUMBER}\s*］|〔\s*{NUMBER}\s*〕|【\s*{NUMBER}\s*】|[①-⑳]"
)
HEADING_RE = re.compile(r"(?:注\s*释|原尾注|尾注|译者注)[：:]?$")
FOOTER_RE = re.compile(r"^(?:感谢.*(?:录入|校对)|责任编辑|译自|载于|选自|来源[：:]|录入[：:]|校对[：:])")
BODY_HEADING_RE = re.compile(r"^(?:附\s*录|附\s*件|后\s*记|第[一二三四五六七八九十\d]+[章篇部])")
INLINE_RE = re.compile(r"(?P<mark>[①-⑳])\s*[（(]\s*(?P=mark)\s*[（(]?\s*注[：:]")


def marker_key(marker: str) -> str:
    value = marker.strip("[]［］〔〕【】 \t")
    # Circle numbers are a different numbering system, not aliases for [1].
    if value in "①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮⑯⑰⑱⑲⑳":
        return value
    number = re.match(r"\d+", value)
    # Capital I uses [80] for an author's note and 【80】 for a different
    # editorial note in the very same chapter. Keep those namespaces separate.
    family = marker[0].translate(str.maketrans({"［": "["}))
    return family + str(int(number[0])) + value[number.end():]


@dataclass(frozen=True)
class Definition:
    chapter: int
    paragraph: int
    start: int
    end: int
    marker: str
    content: tuple[str, ...]
    scope_start: int
    scope_end: int
    local: bool = False


def _note_heading(value: str) -> bool:
    return (len(value) <= 60 and MARKER_RE.search(value) is None
            and HEADING_RE.search(value.strip()) is not None)


def is_printed_reference(text: str, match: re.Match) -> bool:
    """Exclude dates, page ranges and explicit citations of another note."""
    key = marker_key(match[0])
    before, after = text[:match.start()], text[match.end():]
    numeric_key = key.lstrip("[〔【")
    if numeric_key.isdecimal() and 1000 <= int(numeric_key) <= 2099:
        return False
    if re.search(r"(?:注|注释)\s*$", before) or re.match(r"\s*(?:页|[－—―–-]\s*\d+页)", after):
        return False
    # Page-number locators printed at the end of an editorial note.
    if re.search(r"[―—-]{2}\s*$", before) and re.match(r"[。．.]?\s*$", after):
        return False
    return True


def definitions(chapter: dict, chapter_index: int) -> list[Definition]:
    paragraphs = chapter.get("content", [])
    starts: list[tuple[int, re.Match]] = []
    headings = [i for i, text in enumerate(paragraphs) if _note_heading(text)]
    notes_chapter = _note_heading(chapter.get("title", ""))
    for i, text in enumerate(paragraphs):
        match = MARKER_RE.match(text, len(text)-len(text.lstrip()))
        if match is None:
            # Some pages put the notes heading and first definition on one line.
            match = next((m for m in MARKER_RE.finditer(text) if _note_heading(text[:m.start()])), None)
        if match is not None:
            starts.append((i, match))
        for other in MARKER_RE.finditer(text):
            if match is not None and other.start() == match.start():
                continue
            before = text[:other.start()]
            # Lost BR boundaries: an explicit definition colon, a print-page
            # locator terminating the previous note, or a source-credit line.
            if (re.match(r"[：:]", text[other.end():])
                    or re.search(r"--\d+[。.]?(?:\(节选\))?\s+$", before)
                    or ("来源：" in before and before.endswith("　"))):
                starts.append((i, other))
    starts.sort(key=lambda item: (item[0], item[1].start()))

    # A trailing numbered block can omit its heading. Require an actual earlier
    # inline reference, not merely a numbered list or a numeric equation.
    numeric = [(i, m) for i, m in starts if len(m[0]) > 1]
    implicit_start = None
    if not notes_chapter:
        for i, match in numeric:
            key = marker_key(match[0])
            if any(marker_key(m[0]) == key and m.start() > 0
                   for text in paragraphs[:i] for m in MARKER_RE.finditer(text)):
                implicit_start = i
                break

    tail_starts = [(i, m) for i, m in starts if len(m[0]) > 1 and (
                   notes_chapter or any(h < i for h in headings)
                   or (implicit_start is not None and i >= implicit_start)
                   or _note_heading(paragraphs[i][:m.start()]))]
    # Consecutive copies of a heading (date line + heading) are one section.
    headings = [h for h in headings if not any(h < h2 and not any(h < i < h2 for i, _ in tail_starts)
                                              for h2 in headings)]
    result = []
    for pos, (i, match) in enumerate(tail_starts):
        next_start = tail_starts[pos + 1] if pos + 1 < len(tail_starts) else None
        end = next_start[0] if next_start else len(paragraphs)
        next_heading = next((h for h in headings if h > i), len(paragraphs))
        end = min(end, next_heading)
        # Multiple labels may share one definition, e.g. [34]、[34a].
        aliases = [match]
        content_start = match.end()
        while True:
            separator = re.match(r"(?:\s*[、,，]\s*|\s+)", paragraphs[i][content_start:])
            alias = MARKER_RE.match(paragraphs[i], content_start + separator.end()) if separator else None
            if not alias:
                break
            aliases.append(alias)
            content_start = alias.end()
        first_end = next_start[1].start() if next_start and end == i else len(paragraphs[i])
        values = [paragraphs[i][content_start:first_end].lstrip("：: ")]
        for value in paragraphs[i + 1:end]:
            if FOOTER_RE.match(value) or _note_heading(value) or BODY_HEADING_RE.match(value):
                break
            values.append(value)
        if next_start and end == next_start[0] and end > i and next_start[1].start() > 0:
            values.append(paragraphs[end][:next_start[1].start()].strip())
        scope_start = max((h + 1 for h in headings if h < i), default=0)
        # First notes section belongs to the preceding body as well. Subsequent
        # sections begin after the preceding definition block, handled by caller.
        previous_heading = [h for h in headings if h < i]
        if len(previous_heading) <= 1:
            scope_start = 0
        for alias in aliases:
            result.append(Definition(chapter_index, i, alias.start(), alias.end(), alias[0],
                                     tuple(v for v in values if v), scope_start, next_heading))

    tail_positions = {d.paragraph for d in result}
    # Reused circle numbers occur as short local translator notes immediately
    # below the paragraph they annotate. Never turn them into chapter-wide IDs.
    for i, match in starts:
        if (i in tail_positions or len(match[0]) != 1 or i == 0
                or not paragraphs[i][match.end():].strip()):
            continue
        candidates = [j for j in range(max(0, i - 8), i)
                      if match[0] in paragraphs[j] and not MARKER_RE.match(paragraphs[j])]
        if candidates:
            result.append(Definition(chapter_index, i, match.start(), match.end(), match[0],
                                     (paragraphs[i][match.end():].strip(),), candidates[-1], i, True))
        elif paragraphs[i][match.end():].strip():
            # A unique circle-number endnote may be far from its reference.
            same_definitions = [(j,m) for j,m in starts if m[0] == match[0]
                                and paragraphs[j][m.end():].strip()]
            if len(same_definitions) == 1:
                result.append(Definition(chapter_index, i, match.start(), match.end(), match[0],
                                         (paragraphs[i][match.end():].strip(),), 0, i, True))
    return result


def _balanced_end(text: str, start: int) -> int | None:
    depth = 0
    for i in range(start, len(text)):
        if text[i] in "(（":
            depth += 1
        elif text[i] in ")）":
            depth -= 1
            if depth == 0:
                return i
    return None


def _content_key(definition: Definition) -> tuple[str, ...]:
    return tuple(value.replace("―", "—") for value in definition.content)


@lru_cache(maxsize=1)
def _supplement_index() -> dict:
    manifest = json.loads(Path(__file__).with_name("library_footnote_supplements.json").read_text(encoding="utf-8"))
    result = defaultdict(list)
    for kind in ("records", "exclusions"):
        for record in manifest[kind]:
            result[record["bookId"]].append((kind, record))
    return result


def verified_supplements(book: dict) -> tuple[dict, set]:
    """Fail closed if reimported text no longer matches an editorial correction.

    Repeated circle numbers and cross-edition numbering cannot be resolved by
    regex. The reviewed source manifest binds each correction to a complete
    paragraph hash, chapter ID and exact marker range.
    """
    chapters = {c["id"]: c for c in book.get("chapters", [])}
    supplements, exclusions = {}, set()
    for kind, record in _supplement_index().get(book.get("id"), []):
        chapter = chapters.get(record["chapterId"])
        pi, start, marker = record["paragraphIndex"], record["start"], record["marker"]
        text = chapter["content"][pi] if chapter and pi < len(chapter["content"]) else ""
        if (hashlib.sha256(text.encode()).hexdigest() != record["paragraphSha256"]
                or text[start:start + len(marker)] != marker):
            raise ValueError(f"Stale footnote supplement: {book['id']}/{record['chapterId']} paragraph {pi}")
        position = (record["chapterId"], pi, start, start + len(marker))
        if position in supplements or position in exclusions:
            raise ValueError(f"Duplicate footnote supplement: {position}")
        if kind == "exclusions":
            exclusions.add(position)
        else:
            supplements[position] = record
    return supplements, exclusions


def recover_book(book: dict) -> dict:
    """Add only missing links; return additions and unresolved marker locations.

    The function is deterministic and idempotent. Existing anchored notes take
    precedence. Ambiguous definitions are reported instead of guessed.
    """
    chapters = book.get("chapters", [])
    supplements, exclusions = verified_supplements(book)
    all_defs = [definitions(c, ci) for ci, c in enumerate(chapters)]
    global_defs = defaultdict(list)
    for ds in all_defs:
        for definition in ds:
            if not definition.local:
                global_defs[marker_key(definition.marker)].append(definition)
    # A book may restart author-note numbering in each chapter. When that
    # happens, a unique number elsewhere is not evidence of a shared volume
    # note. Explicit notes chapters remain valid cross-chapter sources.
    chapter_local_families = {
        key[0] for key, ds in global_defs.items()
        if len({d.chapter for d in ds}) > 1 and len({_content_key(d) for d in ds}) > 1
    }
    # The bundled MEW 24/25 editions explicitly number editorial notes across
    # the whole volume; the final appendix's notes also cover its preceding
    # chapter. Volume I has two competing numbering systems and is excluded.
    if book.get("seriesId") in {"capital-v2", "capital-v3"}:
        chapter_local_families.discard("[")
    added = 0
    unresolved = []
    for ci, chapter in enumerate(chapters):
        paragraphs = chapter.get("content", [])
        notes = chapter.get("footnotes", [])
        occupied = {(r["paragraphIndex"], r["start"], r["end"])
                    for note in notes for r in note.get("references", [])}
        definition_positions = {(d.paragraph, d.start, d.end) for d in all_defs[ci]}
        local_defs = defaultdict(list)
        for d in all_defs[ci]:
            local_defs[marker_key(d.marker)].append(d)
        additions = {}
        for pi, text in enumerate(paragraphs):
            inline = {}
            inline_labels = set()
            for match in INLINE_RE.finditer(text):
                opening = next(i for i in range(match.start() + 1, match.end()) if text[i] in "(（")
                note_text = text
                end = _balanced_end(note_text, opening)
                # Legacy visual wrapping can split an explicit inline note
                # across paragraphs. Its closing parenthesis is the boundary.
                for continuation in paragraphs[pi + 1:pi + 5]:
                    if end is not None or BODY_HEADING_RE.match(continuation) or _note_heading(continuation):
                        break
                    note_text += "\n" + continuation
                    end = _balanced_end(note_text, opening)
                if end is not None:
                    inline[match.start()] = tuple(v.strip() for v in note_text[match.end():end].splitlines() if v.strip())
                    label = MARKER_RE.search(text, opening + 1)
                    inline_labels.add(label.start())
            for match in MARKER_RE.finditer(text):
                position = (pi, match.start(), match.end())
                source_position = (chapter["id"], *position)
                if (position in occupied or position in definition_positions or match.start() in inline_labels
                        or source_position in exclusions
                        or not is_printed_reference(text, match)):
                    continue
                key = marker_key(match[0])
                supplement = supplements.get(source_position)
                content = tuple(supplement["content"]) if supplement else inline.get(match.start())
                definition = None
                if content is None:
                    candidates = [d for d in local_defs[key] if d.scope_start <= pi < d.scope_end]
                    nearby = [d for d in candidates if d.local]
                    if nearby:
                        candidates = nearby
                    elif local_defs[key]:
                        # Repeated local blocks with differing content require
                        # source anchors. A heading alone cannot delimit where
                        # the preceding body ended after HTML was flattened.
                        candidates = [d for d in local_defs[key] if not d.local]
                    if not candidates and not local_defs[key]:
                        # Cross-chapter references must resolve uniquely within
                        # this book; never borrow notes from another edition.
                        candidates = [d for d in global_defs[key] if key[0] not in chapter_local_families
                                      or _note_heading(chapters[d.chapter].get("title", ""))]
                    # Repeated copies of an identical volume note are safe to
                    # share; conflicting definitions must remain unresolved.
                    if candidates and len({_content_key(d) for d in candidates}) == 1:
                        candidates = candidates[:1]
                    if len(candidates) == 1:
                        definition = candidates[0]
                        content = definition.content
                    else:
                        unresolved.append({"chapterId": chapter["id"], "paragraphIndex": pi,
                                           "start": match.start(), "marker": match[0],
                                           "reason": "ambiguous" if candidates else "missing-definition",
                                           "context": text[max(0, match.start()-25):match.end()+45]})
                        continue
                if not content or not any(content):
                    continue
                identity = (f"{definition.chapter}:{definition.paragraph}:{definition.start}"
                            if definition else f"inline:{pi}:{match.start()}")
                note_id = "printed-" + hashlib.sha1((identity + ':' + match[0]).encode()).hexdigest()[:16]
                # Reuse a pre-existing note when it already has this exact body
                # and marker. Otherwise use a definition-specific stable ID.
                note = next((n for n in notes if n["marker"] == match[0]
                             and n["content"] == list(content)), None)
                if note is None:
                    note = additions.setdefault(note_id, {"id": note_id, "marker": match[0],
                                                         "content": list(content), "references": []})
                if supplement and supplement.get("status") == "SOURCE_MISSING":
                    note["status"] = "SOURCE_MISSING"
                note["references"].append({"paragraphIndex": pi, "start": match.start(), "end": match.end()})
                occupied.add(position)
                added += 1
        if additions:
            chapter["footnotes"] = notes + list(additions.values())
    for position, record in supplements.items():
        if record.get("status") == "SOURCE_MISSING":
            unresolved.append({"chapterId": position[0], "paragraphIndex": position[1],
                               "start": position[2], "marker": record["marker"],
                               "reason": "source-missing", "clickableNotice": True,
                               "context": record["context"]})
    return {"addedReferences": added, "unresolved": unresolved}
