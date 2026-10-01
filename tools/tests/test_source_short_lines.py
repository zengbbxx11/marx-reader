import unittest
from audit_source_short_lines import candidates


class SourceShortLinesTest(unittest.TestCase):
    left = '这里是足够长的正文，作为前文定位的依据。'
    right = '接下来的文字也足够长，用于准确核对后文。'

    def test_missing_single_character_has_unique_surrounding_context(self):
        rows = candidates(f'<body><p>{self.left}</p><p>一</p><p>{self.right}</p></body>', [self.left + self.right])
        self.assertEqual(1, len(rows))
        self.assertEqual('MISSING_CONTEXT_CANDIDATE', rows[0]['status'])
        self.assertEqual('一', rows[0]['text'])

    def test_restored_neighboring_short_lines_are_recognized_as_present(self):
        html=f'<body><p>{self.left}</p><p>一</p><p>小段文字</p><p>二</p><p>{self.right}</p></body>'
        rows=candidates(html,[self.left+'一小段文字二'+self.right])
        self.assertEqual(['PRESENT_IN_LOCAL_CONTEXT','PRESENT_IN_LOCAL_CONTEXT'],[r['status'] for r in rows])

    def test_present_heading_is_not_reported_missing(self):
        rows = candidates(f'<body><p>{self.left}</p><p>一</p><p>{self.right}</p></body>', [self.left, '一', self.right])
        self.assertEqual('PRESENT_IN_LOCAL_CONTEXT', rows[0]['status'])

    def test_duplicate_surrounding_context_requires_manual_review(self):
        rows = candidates(f'<body><p>{self.left}</p><p>一</p><p>{self.right}</p></body>', [(self.left + self.right) * 2])
        self.assertEqual('UNRESOLVED_CONTEXT', rows[0]['status'])

    def test_consecutive_single_digit_source_lines_are_preserved_as_one_candidate(self):
        html = f'<body><p>{self.left}</p><table><tr><td>4\n3</td></tr></table><p>{self.right}</p></body>'
        rows = candidates(html, [self.left + self.right])
        self.assertEqual('43', rows[0]['text'])
        self.assertEqual(0, rows[0]['tableIndex'])
        self.assertEqual('MISSING_CONTEXT_CANDIDATE', rows[0]['status'])

    def test_digits_in_a_long_line_and_script_text_do_not_create_candidates(self):
        rows = candidates('<body><script>1</script><p>这是行内数字4和3。</p></body>', ['这是行内数字4和3。'])
        self.assertEqual([], rows)

    def test_formatting_reference_markers_do_not_hide_missing_source_line(self):
        html = f'<body><p>{self.left}</p><p>8</p><p>{self.right}</p></body>'
        rows = candidates(html, [self.left + '〔原表1〕' + self.right])
        self.assertEqual('MISSING_CONTEXT_CANDIDATE', rows[0]['status'])
