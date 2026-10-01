"""Known source formula images, bundled offline and exposed as read-only notes."""
from __future__ import annotations

import json
import urllib.parse
from pathlib import Path

from bs4 import NavigableString

MANIFEST = Path(__file__).with_name("library_source_images.json")


def image_tokens(root, url: str, reference_tokens: dict) -> dict:
    records = json.loads(MANIFEST.read_text("utf-8"))["images"]
    by_url = {record["sourceUrl"]: record for record in records}
    notes = {}
    for node in list(root.find_all("img", src=True)):
        source_url = urllib.parse.urljoin(url, node["src"])
        record = by_url.get(source_url)
        if record is None:
            if url.startswith("https://www.marxists.org/chinese/marx-engels/24/"):
                raise ValueError(f"Unreviewed Capital II image: {source_url}")
            continue
        correction = record.get("referenceCorrections", {}).get(url)
        resolved = {**record, **correction} if correction else record
        attachment = record.get("kind") == "source_attachment"
        prefix = "source-attachment-" if attachment else "source-image-"
        ordinal = sum(key.startswith(prefix) for key in notes) + 1
        note_id = f"{prefix}{ordinal:03d}"
        marker = f"〔附件{ordinal}〕" if attachment else f"〔图式{ordinal}〕"
        token = f"@@MIA_FOOTNOTE_REF_{len(reference_tokens):05d}@@"
        reference_tokens[token] = (note_id, marker)
        notes[note_id] = {"id": note_id, "marker": marker,
                          "content": [resolved.get("referenceDescriptions", {}).get(url, resolved["description"])],
                          "imageAsset": resolved["assetPath"],
                          "imageSha256": resolved["sha256"], "sourceUrl": resolved["sourceUrl"]}
        if correction:
            notes[note_id]["originalSourceUrl"] = source_url
            notes[note_id]["correctionEvidence"] = correction["evidence"]
        node.replace_with(NavigableString(token))
    return notes
