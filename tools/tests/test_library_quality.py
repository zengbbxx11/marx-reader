import json
import sys
import tempfile
import unittest
from pathlib import Path


TOOLS_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS_DIR))

from audit_library_quality import (  # noqa: E402
    LibraryPayload,
    audit,
    infer_local_year,
    is_credible_inferred_heading,
    suspicious_section_reason,
)
from package_library_v2 import package  # noqa: E402
from remediate_library_metadata import automatic_year_patch  # noqa: E402


def sample_book(**overrides):
    book = {
        "id": "sample-book",
        "authorIds": ["marx"],
        "seriesId": None,
        "titleZh": "示例作品（1900年）",
        "titleEn": "Sample",
        "language": "zh",
        "category": "著作",
        "year": "1900",
        "sourceUrl": "https://example.test/sample",
        "sourceCredit": "Example Archive",
        "translator": "测试译者",
        "rights": "PUBLIC_DOMAIN",
        "rightsBasis": "Test fixture",
        "description": "测试作品。",
        "chapters": [{
            "id": "chapter-1",
            "title": "第一章",
            "level": 1,
            "content": ["这是一段用于质量审计测试的正文。" * 10],
            "sections": [{
                "id": "section-1",
                "title": "1. 商品",
                "level": 2,
                "paragraphIndex": 0,
            }],
        }],
        "toc": [],
    }
    book.update(overrides)
    return book


def sample_payload(book=None):
    return LibraryPayload(2, [{"id": "marx"}], [book or sample_book()], [])


class HeadingQualityTest(unittest.TestCase):
    def test_numeric_and_table_rows_are_not_headings(self):
        self.assertEqual("numeric-only title", suspicious_section_reason("8.01"))
        self.assertEqual("probable table row", suspicious_section_reason("14.9598威根64418.0棉业"))
        self.assertFalse(is_credible_inferred_heading("45.7"))

    def test_numbered_heading_with_text_is_retained(self):
        self.assertIsNone(suspicious_section_reason("1. 商品的两个因素"))
        self.assertTrue(is_credible_inferred_heading("1. 商品的两个因素"))
        self.assertIsNone(suspicious_section_reason("10．1916年的爱尔兰起义"))
        self.assertIsNone(suspicious_section_reason("1922年12月24日"))
        self.assertIsNone(suspicious_section_reason("12月23日"))
        self.assertIsNone(suspicious_section_reason("（1）"))


class YearInferenceTest(unittest.TestCase):
    def test_title_year_is_local_evidence(self):
        self.assertEqual("1917", infer_local_year("1917年4月提纲", "列宁 1870—1924 1917年4月提纲"))

    def test_large_parent_year_is_not_borrowed(self):
        label = "关于国家问题的演说"
        context = "1895 " + ("同一索引分组中的其他作品 " * 10) + label
        self.assertEqual("", infer_local_year(label, context))

    def test_ambiguous_title_year_is_left_blank(self):
        self.assertEqual("", infer_local_year("1848年至1850年的法兰西阶级斗争", "1895年再版"))

    def test_page_date_replaces_inherited_index_year(self):
        book = sample_book(
            titleZh="一九一七年的一篇文章（1917年）",
            year="1870",
            sourceUrl="https://example.test/work.htm",
        )
        patch, reason = automatic_year_patch(book, "作品标题 （1917年4月）")
        self.assertEqual("1917", patch["year"])
        self.assertEqual("title-page-date-conflict", reason)

    def test_missing_year_requires_url_and_dated_header_to_agree(self):
        book = sample_book(
            titleZh="无日期作品",
            year="",
            sourceUrl="https://example.test/1917/work.htm",
        )
        patch, reason = automatic_year_patch(book, "页面中只提到了1918年")
        self.assertEqual({}, patch)
        self.assertIsNone(reason)


class AuditSeverityTest(unittest.TestCase):
    def test_title_year_conflict_is_review_not_an_automatic_fix(self):
        book = sample_book(year="1899")
        findings = audit(sample_payload(book))
        conflicts = [item for item in findings if item.code == "TITLE_YEAR_CONFLICT"]
        self.assertEqual(1, len(conflicts))
        self.assertEqual("REVIEW", conflicts[0].severity)

    def test_explained_title_year_difference_is_not_a_conflict(self):
        book = sample_book(year="1899", yearNote="题名年份是历史事件年份")
        findings = audit(sample_payload(book))
        self.assertFalse(any(item.code == "TITLE_YEAR_CONFLICT" for item in findings))

    def test_rights_review_is_opt_in(self):
        book = sample_book(rightsBasis="")
        self.assertFalse(any(item.code == "RIGHTS_BASIS_UNRECORDED" for item in audit(sample_payload(book))))
        self.assertTrue(any(
            item.code == "RIGHTS_BASIS_UNRECORDED"
            for item in audit(sample_payload(book), include_rights_review=True)
        ))

    def test_missing_translator_is_review(self):
        findings = audit(sample_payload(sample_book(translator="")))
        item = next(item for item in findings if item.code == "TRANSLATOR_UNRECORDED")
        self.assertEqual("REVIEW", item.severity)

    def test_restricted_rights_with_body_is_error(self):
        findings = audit(sample_payload(sample_book(rights="UNKNOWN", rightsBasis="")))
        item = next(item for item in findings if item.code == "RESTRICTED_RIGHTS_BODY")
        self.assertEqual("ERROR", item.severity)

    def test_duplicate_and_out_of_range_targets_are_errors(self):
        book = sample_book()
        book["chapters"].append(dict(book["chapters"][0]))
        book["chapters"][0]["sections"][0]["paragraphIndex"] = 99
        codes = {item.code for item in audit(sample_payload(book)) if item.severity == "ERROR"}
        self.assertIn("DUPLICATE_CHAPTER_ID_IN_BOOK", codes)
        self.assertIn("SECTION_TARGET_OUT_OF_RANGE", codes)

    def test_broken_footnote_offsets_are_errors(self):
        book = sample_book()
        book["chapters"][0]["footnotes"] = [{
            "id": "note-1",
            "marker": "[1]",
            "content": ["注释正文"],
            "references": [{"paragraphIndex": 0, "start": 0, "end": 3}],
        }]
        codes = {item.code for item in audit(sample_payload(book)) if item.severity == "ERROR"}
        self.assertIn("FOOTNOTE_MARKER_MISMATCH", codes)

    def test_toc_parent_cycles_are_errors(self):
        book = sample_book()
        book["toc"] = [
            {"id": "a", "parentId": "b", "type": "PART", "chapterId": None, "paragraphIndex": 0},
            {"id": "b", "parentId": "a", "type": "PART", "chapterId": None, "paragraphIndex": 0},
        ]
        codes = {item.code for item in audit(sample_payload(book)) if item.severity == "ERROR"}
        self.assertIn("TOC_PARENT_CYCLE", codes)


class PackagingQualityTest(unittest.TestCase):
    def test_packaging_drops_only_suspicious_section_metadata(self):
        book = sample_book()
        book["chapters"][0]["sections"].append({
            "id": "section-table",
            "title": "8.01",
            "level": 3,
            "paragraphIndex": 0,
        })
        source_payload = {"schemaVersion": 2, "authors": [{"id": "marx"}], "books": [book]}
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source.json"
            target = root / "library"
            source.write_text(json.dumps(source_payload, ensure_ascii=False), encoding="utf-8")
            package(source, target)
            result = json.loads((target / "books" / "sample-book.json").read_text(encoding="utf-8"))
        packaged = result["books"][0]
        self.assertEqual(["section-1"], [item["id"] for item in packaged["chapters"][0]["sections"]])
        self.assertIn("这是一段用于质量审计测试的正文", packaged["chapters"][0]["content"][0])
        self.assertNotIn("section-table", [node["id"] for node in packaged["toc"]])

    def test_packaging_writes_character_counts_to_full_and_summary_assets(self):
        book = sample_book()
        book["chapters"][0]["content"] = ["甲乙丙", "一二三四五"]
        source_payload = {"schemaVersion": 2, "authors": [{"id": "marx"}], "books": [book]}
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source.json"
            target = root / "library"
            source.write_text(json.dumps(source_payload, ensure_ascii=False), encoding="utf-8")
            package(source, target)
            full = json.loads((target / "books" / "sample-book.json").read_text(encoding="utf-8"))
            catalog = json.loads((target / "catalog.json").read_text(encoding="utf-8"))

        full_book = full["books"][0]
        summary_book = catalog["books"][0]
        self.assertNotIn("characterCount", full_book)
        self.assertEqual(8, summary_book["characterCount"])
        self.assertEqual([3, 5], summary_book["chapters"][0]["paragraphCharacterCounts"])
        self.assertNotIn("content", summary_book["chapters"][0])


if __name__ == "__main__":
    unittest.main()
