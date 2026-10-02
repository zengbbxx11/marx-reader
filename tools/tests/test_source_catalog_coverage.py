import copy
import unittest
from build_full_chinese_library import acceptable_text_link, deduplicate_books, extract_chapter


class CatalogCoverageTest(unittest.TestCase):
    def test_traditional_version_is_outside_simplified_library(self):
        self.assertFalse(acceptable_text_link(
            'https://www.marxists.org/chinese/marx/mia-chinese-marx-184002f-cwd.htm',
            '繁体版《共產黨宣言》', ('/chinese/marx/',)))

    def book(self, content, url='https://www.marxists.org/one.htm'):
        return {'titleZh': '马克思致库格曼', 'authorIds': ['marx'], 'sourceUrl': url,
                'chapters': [{'content': content, 'footnotes': []}]}

    def test_different_dated_letters_with_same_title_are_retained(self):
        first = self.book(['1871年4月12日', '关于巴黎公社的第一封信。'])
        second = self.book(['1871年4月17日', '关于巴黎公社的第二封信。'], 'https://www.marxists.org/two.htm')
        self.assertEqual([first, second], deduplicate_books([first, second]))

    def test_equal_text_can_be_deduplicated_but_different_notes_are_preserved(self):
        first = self.book(['相同正文。'])
        duplicate = copy.deepcopy(first)
        duplicate['sourceUrl'] = 'https://www.marxists.org/duplicate.htm'
        self.assertEqual([first], deduplicate_books([first, duplicate]))
        duplicate['chapters'][0]['footnotes'] = [{'marker': '[1]', 'content': ['不同版本原注。']}]
        self.assertEqual([first, duplicate], deduplicate_books([first, duplicate]))

    def test_short_primary_instruction_is_not_discarded(self):
        text = '在打倒地主阶级和官僚资产阶级以后，中国内部的主要矛盾即是工人阶级与民族资产阶级的矛盾，故不应再将民族资产阶级称为中间阶级。'
        chapter = extract_chapter('https://www.marxists.org/short.htm', '批语',
                                  '<body><h1>批语</h1><p>' + text + '</p></body>', 1)
        self.assertIsNotNone(chapter)
        self.assertEqual([text], chapter['content'])
