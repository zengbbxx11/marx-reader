import sys
import unittest
from pathlib import Path

TOOLS_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS_DIR))

from text_encoding import (  # noqa: E402
    REPLACEMENT_CHARACTER,
    decode_html,
    detect_encoding,
    replacement_count,
)

SAMPLE = "马克思在伦敦继续进行这项研究，古物陈列馆与纺车陈列在一起。"


class DecodeHtmlTest(unittest.TestCase):
    def test_utf8_without_declaration(self):
        self.assertEqual(decode_html(SAMPLE.encode("utf-8")), SAMPLE)

    def test_utf8_with_declared_charset(self):
        self.assertEqual(decode_html(SAMPLE.encode("utf-8"), "utf-8"), SAMPLE)

    def test_gb18030_without_declaration(self):
        """The exact case that corrupted the packaged library."""
        text = decode_html(SAMPLE.encode("gb18030"))
        self.assertEqual(text, SAMPLE)
        self.assertEqual(replacement_count(text), 0)

    def test_gbk_with_gb2312_declaration(self):
        self.assertEqual(decode_html(SAMPLE.encode("gbk"), "gb2312"), SAMPLE)

    def test_gb18030_with_wrong_utf8_declaration(self):
        """A wrong declaration must not win over what actually decodes."""
        self.assertEqual(decode_html(SAMPLE.encode("gb18030"), "utf-8"), SAMPLE)

    def test_meta_charset_is_detected(self):
        html = '<meta http-equiv="Content-Type" content="text/html; charset=gb2312">' + SAMPLE
        self.assertEqual(detect_encoding(html.encode("gb18030")), "gb2312")
        self.assertEqual(decode_html(html.encode("gb18030")), html)

    def test_ascii(self):
        raw = b"<html><body>Hello</body></html>"
        self.assertEqual(decode_html(raw), raw.decode("ascii"))

    def test_fullwidth_space_and_circle_digits(self):
        """Full-width characters decode as two replacement bytes when misread."""
        value = "　　" + "二〇一〇年"
        self.assertEqual(decode_html(value.encode("gb18030")), value)

    def test_undecodable_bytes_are_reported_not_hidden(self):
        """Only genuinely broken input may produce replacement characters."""
        raw = b"abc\xff\xfe\x80"
        text = decode_html(raw)
        self.assertGreater(replacement_count(text), 0)

    def test_replacement_count(self):
        self.assertEqual(replacement_count("a" + REPLACEMENT_CHARACTER + "b"), 1)


if __name__ == "__main__":
    unittest.main()
