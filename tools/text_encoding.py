#!/usr/bin/env python3
"""Charset handling for fetched MIA pages.

MIA publishes a mixture of UTF-8 and GB18030 pages, and part of the older
Chinese pages carry no charset declaration in either the response header or the
document head. Decoding those pages as UTF-8 with ``errors="replace"`` rewrites
every undecodable byte as U+FFFD, which destroys the original bytes and is how
replacement characters ended up inside the packaged offline library.
"""

from __future__ import annotations

import re

#: Character produced when a byte sequence cannot be decoded.
REPLACEMENT_CHARACTER = "\ufffd"

#: Encodings tried when the page declares nothing that actually decodes.
#: GB18030 is a superset of GBK and GB2312, so one entry covers all of them.
FALLBACK_ENCODINGS = ("utf-8", "gb18030", "big5")


def detect_encoding(raw: bytes) -> str | None:
    """Return the charset declared in the document head, if any."""
    head = raw[:4096].decode("ascii", errors="ignore")
    match = re.search(r"charset\s*=\s*['\"]?([\w-]+)", head, flags=re.I)
    return match.group(1) if match else None


def decode_html(raw: bytes, declared: str | None = None) -> str:
    """Decode a fetched page without silently corrupting CJK text.

    A declared or detected charset is trusted only when it decodes strictly.
    Real UTF-8 always decodes strictly, while GB18030 text almost never does,
    so the fallback engages exactly for pages that need it. Only when nothing
    decodes do we fall back to ``errors="replace"``; any replacement character
    produced there is reported as an ERROR by the library quality audit.
    """
    for encoding in (declared, detect_encoding(raw)):
        if not encoding:
            continue
        try:
            return raw.decode(encoding)
        except (UnicodeDecodeError, LookupError):
            continue
    for encoding in FALLBACK_ENCODINGS:
        try:
            return raw.decode(encoding)
        except (UnicodeDecodeError, LookupError):
            continue
    return raw.decode("utf-8", errors="replace")


def replacement_count(value: str) -> int:
    """Number of undecodable-source markers inside already decoded text."""
    return value.count(REPLACEMENT_CHARACTER)
