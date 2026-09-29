import copy
import json
import sys
import tempfile
import unittest
import hashlib
from unittest.mock import patch
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from library_footnotes import recover_book, verified_supplements
from repair_library_footnotes import repair


def book(*paragraph_lists):
    return {"id": "sample", "titleZh": "测试", "chapters": [
        {"id": f"chapter-{i}", "title": "正文", "content": paragraphs}
        for i, paragraphs in enumerate(paragraph_lists)
    ]}


def note_at(value, chapter, paragraph, marker):
    text = value["chapters"][chapter]["content"][paragraph]
    offset = text.index(marker)
    return next(n for n in value["chapters"][chapter]["footnotes"]
                if {"paragraphIndex": paragraph, "start": offset, "end": offset + len(marker)} in n["references"])


class PrintedFootnoteTest(unittest.TestCase):
    def supplement(self, value, **overrides):
        record = {"bookId": "sample", "chapterId": "chapter-0", "paragraphIndex": 0,
                  "start": 2, "marker": "①", "content": ["从完整版本核对的注释"],
                  "context": value["chapters"][0]["content"][0],
                  "paragraphSha256": hashlib.sha256(value["chapters"][0]["content"][0].encode()).hexdigest()}
        record.update(overrides)
        return record

    def test_verified_source_note_does_not_leak_to_repeated_circle(self):
        value = book(["甲文①。乙文①。"])
        record = self.supplement(value)
        with patch("library_footnotes._supplement_index", return_value={"sample": [("records", record)]}):
            result = recover_book(value)
            self.assertEqual(1, result["addedReferences"])
            self.assertEqual(6, result["unresolved"][0]["start"])
            self.assertEqual(0, recover_book(value)["addedReferences"])

    def test_changed_source_text_rejects_stale_editorial_offsets(self):
        value = book(["正文①。"])
        record = self.supplement(value)
        value["chapters"][0]["content"][0] = "新正文①。"
        with patch("library_footnotes._supplement_index", return_value={"sample": [("records", record)]}):
            with self.assertRaisesRegex(ValueError, "Stale footnote supplement"):
                recover_book(value)
        self.assertNotIn("footnotes", value["chapters"][0])

    def test_existing_anchor_wins_over_editorial_supplement(self):
        value = book(["正文①。"])
        value["chapters"][0]["footnotes"] = [{"id": "source", "marker": "①", "content": ["原有注释"],
            "references": [{"paragraphIndex": 0, "start": 2, "end": 3}]}]
        original = copy.deepcopy(value)
        with patch("library_footnotes._supplement_index", return_value={"sample": [("records", self.supplement(value))]}):
            self.assertEqual(0, recover_book(value)["addedReferences"])
        self.assertEqual(original, value)

    def test_missing_source_notice_is_clickable_but_remains_reported(self):
        value = book(["正文①。"])
        record = self.supplement(value, status="SOURCE_MISSING", content=["【源站注释缺文】"])
        with patch("library_footnotes._supplement_index", return_value={"sample": [("records", record)]}):
            result = recover_book(value)
            self.assertEqual(1, result["addedReferences"])
            self.assertEqual("SOURCE_MISSING", note_at(value, 0, 0, "①")["status"])
            self.assertEqual("source-missing", result["unresolved"][0]["reason"])
            self.assertTrue(result["unresolved"][0]["clickableNotice"])
            self.assertEqual(0, recover_book(value)["addedReferences"])

    def test_all_editorial_records_match_shipped_paragraphs_and_distinct_notes(self):
        root = Path(__file__).resolve().parents[2]
        manifest = json.loads((root / "tools/library_footnote_supplements.json").read_text(encoding="utf-8"))
        for bid in {r["bookId"] for k in ("records", "exclusions") for r in manifest[k]}:
            value = json.loads((root / "app/src/main/assets/library/books" / f"{bid}.json").read_text(encoding="utf-8"))["books"][0]
            verified_supplements(value)
        value = json.loads((root / "app/src/main/assets/library/books/marx-work-bcd6bcd970b2.json").read_text(encoding="utf-8"))["books"][0]
        self.assertIn('而是“劳动”。', note_at(value, 0, 74, "②")["content"][0])
        self.assertIn('而是“劳动本身”。', note_at(value, 0, 75, "②")["content"][0])

    def test_multiple_paragraph_notes_repeated_references_and_stable_text(self):
        value = book(["正文[12]，另一个引用[13]。", "又见[12]。", "注释：",
                      "[12]第一段", "续段。", "[13]第二条。"])
        original = copy.deepcopy(value)
        result = recover_book(value)
        self.assertEqual(3, result["addedReferences"])
        self.assertEqual(["第一段", "续段。"], note_at(value, 0, 0, "[12]")["content"])
        self.assertEqual(2, len(note_at(value, 0, 0, "[12]")["references"]))
        self.assertEqual(original["chapters"][0]["content"], value["chapters"][0]["content"])
        repaired = copy.deepcopy(value)
        self.assertEqual(0, recover_book(value)["addedReferences"])
        self.assertEqual(repaired, value)

    def test_local_definitions_win_over_other_chapters(self):
        value = book(["甲[1]", "注释", "[1]甲注"], ["乙[1]", "注释", "[1]乙注"])
        recover_book(value)
        self.assertEqual(["甲注"], note_at(value, 0, 0, "[1]")["content"])
        self.assertEqual(["乙注"], note_at(value, 1, 0, "[1]")["content"])

    def test_separate_notes_chapter_and_glued_first_heading(self):
        value = book(["正文[1]及[2]"], ["第一卷注释[1]第一条", "续段", "[2]第二条"])
        value["chapters"][1]["title"] = "第一卷注释"
        recover_book(value)
        self.assertEqual(["第一条", "续段"], note_at(value, 0, 0, "[1]")["content"])
        self.assertEqual(["第二条"], note_at(value, 0, 0, "[2]")["content"])

    def test_date_line_and_repeated_heading_are_one_section(self):
        value = book(["正文[1]", "恩格斯1894年于伦敦注释：", "注释：", "[1]内容"])
        self.assertEqual(1, recover_book(value)["addedReferences"])

    def test_translator_note_ending_is_not_a_heading(self):
        value = book(["正文[1]和[2]", "[1]说明。——译者注", "[2]解释"])
        self.assertEqual(2, recover_book(value)["addedReferences"])

    def test_author_and_editor_numbering_must_not_be_conflated(self):
        value = book(["正文[80]和【80】", "[80]作者注", "【80】编者注"])
        recover_book(value)
        self.assertEqual(["作者注"], note_at(value, 0, 0, "[80]")["content"])
        self.assertEqual(["编者注"], note_at(value, 0, 0, "【80】")["content"])

    def test_conflicting_cross_chapter_numbers_are_not_guessed(self):
        value = book(["正文[1]"], ["注释", "[1]甲"], ["注释", "[1]乙"])
        result = recover_book(value)
        self.assertNotIn("footnotes", value["chapters"][0])
        self.assertTrue(result["unresolved"])

    def test_chapter_numbering_is_not_borrowed_from_another_chapter(self):
        value = book(["正文[2]", "注释", "[1]甲"],
                     ["注释", "[1]乙", "[2]另一章的注释"])
        result = recover_book(value)
        self.assertEqual(0, result["addedReferences"])
        self.assertNotIn("footnotes", value["chapters"][0])

    def test_repeated_note_blocks_without_source_anchors_are_ambiguous(self):
        value = book(["第一部分[1]", "注释", "[1]甲", "第二部分[1]", "注释", "[1]乙"])
        result = recover_book(value)
        self.assertEqual(0, result["addedReferences"])
        self.assertTrue(all(r['reason'] == 'ambiguous' for r in result['unresolved']))

    def test_reused_circle_numbers_are_local(self):
        value = book(["第一段①", "① 第一条译注", "中间正文", "第二段①", "① 第二条译注"])
        recover_book(value)
        self.assertEqual(["第一条译注"], note_at(value, 0, 0, "①")["content"])
        self.assertEqual(["第二条译注"], note_at(value, 0, 3, "①")["content"])

    def test_explicit_inline_circle_note_with_nested_parentheses(self):
        value = book(["正文①( ①注：解释(包含说明)。--编者注)正文继续。"])
        result = recover_book(value)
        self.assertEqual(1, result["addedReferences"])
        self.assertEqual([], result["unresolved"])
        self.assertEqual(["解释(包含说明)。--编者注"], note_at(value, 0, 0, "①")["content"])

    def test_inline_note_split_across_paragraphs(self):
        value = book(["正文①( ①注：注释首段", "续段(说明)。)正文继续"])
        recover_book(value)
        self.assertEqual(["注释首段", "续段(说明)。"], note_at(value, 0, 0, "①")["content"])

    def test_circle_reference_can_be_a_standalone_paragraph(self):
        value = book(["正文", "②", "其他正文", "注释", "② 解释内容"])
        recover_book(value)
        self.assertEqual(["解释内容"], note_at(value, 0, 1, "②")["content"])

    def test_aliases_and_whitespace_definition_markers(self):
        value = book(["正文[34]及[34a]", "　[34]、[34a]共用内容"])
        self.assertEqual(2, recover_book(value)["addedReferences"])
        self.assertEqual(["共用内容"], note_at(value, 0, 0, "[34a]")["content"])

    def test_marker_on_its_own_line_and_footer_exclusion(self):
        value = book(["正文[15]", "注释", "[15]", "内容", "感谢某人录入及校对"])
        recover_book(value)
        self.assertEqual(["内容"], note_at(value, 0, 0, "[15]")["content"])

    def test_last_note_does_not_swallow_an_appendix(self):
        value = book(["正文[2]", "注释", "[2]注释内容", "附 录", "一", "附录正文"])
        recover_book(value)
        self.assertEqual(["注释内容"], note_at(value, 0, 0, "[2]")["content"])

    def test_word_endnote_anchor_is_extracted(self):
        from build_full_chinese_library import extract_chapter
        html = ('<html><body><p>' + '正文内容。' * 50
                + '<a name="_ednref1" href="#_edn1">[1]</a></p>'
                + '<p><a name="_edn1" href="#_ednref1">[1]</a>尾注内容。</p></body></html>')
        chapter = extract_chapter('https://www.marxists.org/chinese/test.htm', '测试', html, 1)
        self.assertEqual('_edn1', chapter['footnotes'][0]['id'])
        self.assertEqual(['尾注内容。'], chapter['footnotes'][0]['content'])

    def test_lost_line_break_between_definitions(self):
        value = book(["正文[1]及[2]", "署名[1]：第一条[2]：第二条"])
        recover_book(value)
        self.assertEqual(["第一条"], note_at(value, 0, 0, "[1]")["content"])
        self.assertEqual(["第二条"], note_at(value, 0, 0, "[2]")["content"])

    def test_dates_page_numbers_foreign_note_citations_and_lists_are_plain_text(self):
        value = book(["某书［1881］年版，第〔68〕－69页，参见《另一篇文章》注〔1〕。",
                      "（1）第一点", "（2）第二点", "公式x²+y²=z²。"])
        self.assertEqual({"addedReferences": 0, "unresolved": []}, recover_book(value))

    def test_existing_anchored_notes_are_preserved_and_reused(self):
        value = book(["正文[1]", "再次[1]", "注释", "[1]解释"])
        note = {"id": "_ftn1", "marker": "[1]", "content": ["解释"],
                "references": [{"paragraphIndex": 0, "start": 2, "end": 5}]}
        value["chapters"][0]["footnotes"] = [copy.deepcopy(note)]
        self.assertEqual(1, recover_book(value)["addedReferences"])
        self.assertEqual(1, len(value["chapters"][0]["footnotes"]))
        self.assertEqual("_ftn1", value["chapters"][0]["footnotes"][0]["id"])

    def test_missing_content_is_reported_without_fabricating_a_note(self):
        value = book(["正文[93]。"])
        result = recover_book(value)
        self.assertEqual("missing-definition", result["unresolved"][0]["reason"])
        self.assertNotIn("footnotes", value["chapters"][0])

    def test_repair_cli_scans_all_books_and_changes_only_footnotes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "books").mkdir()
            for name in ("a", "b"):
                value = book(["正文[1]", "注释", "[1]解释"])
                (root / "books" / f"{name}.json").write_text(json.dumps({"books": [value]}), encoding="utf-8")
            result = repair(root, apply=True)
            self.assertEqual(2, result["booksChanged"])
            self.assertEqual(2, result["addedReferences"])
            self.assertEqual(0, repair(root)["addedReferences"])


if __name__ == "__main__":
    unittest.main()
