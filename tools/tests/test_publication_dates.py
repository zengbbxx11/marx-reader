import copy
import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from library_publication_dates import infer_publication, parse_source_date, publication_credit


class PublicationDatesTest(unittest.TestCase):
    def test_publication_is_distinct_from_writing_and_translation(self):
        credit = '弗・恩格斯写于1848年2月25―26日载于1848年2月27日“德意志―布鲁塞尔报”第17号原文是德文'
        self.assertEqual('1848-02-27', publication_credit(credit)[0])
        self.assertIsNone(publication_credit('俄译文第一次发表于1955年“历史问题”杂志'))
        self.assertIsNone(publication_credit('写于1894年6月'))
        self.assertIsNone(publication_credit('1872年春，彼得堡出版了《资本论》的优秀的俄译本。'))

    def test_old_and_new_style_dates_use_source_parentheses(self):
        self.assertEqual('1905-10-24', parse_source_date('1905年10月11日（24日）')[0])
        self.assertEqual('1905-04-12', parse_source_date('1905年3月30日（4月12日）')[0])
        self.assertEqual('1918-01-09', parse_source_date('1917年12月27日（1月9日）')[0])

    def test_partial_dates_ranges_and_invalid_dates(self):
        self.assertEqual('1918-03-16', parse_source_date('1918年3月3日和4日（16日和17日）')[0])
        self.assertEqual('1914-12-04', parse_source_date('1914年12月4、11日')[0])
        self.assertEqual(('1901', '1902', '源站标注的发表年份范围'), parse_source_date('1901―1902年“新时代”'))
        self.assertEqual('1848-02', parse_source_date('1848年2月')[0])
        self.assertEqual('1848', parse_source_date('1848年')[0])
        self.assertIsNone(parse_source_date('1900年2月29日'))
        self.assertIsNone(parse_source_date('1848年13月'))

    def test_run_together_writing_and_first_publication_dates(self):
        self.assertEqual('1925', publication_credit('写于1913年6月1925年第一次载于“列宁文集”第3卷')[0])
        self.assertEqual('1892-07-03', publication_credit('1892年7月1日于伦敦载于1892年7月3日“前进报”')[0])

    def test_notes_and_collection_constituents_do_not_date_the_work(self):
        book = {'sourceUrl': 'https://www.marxists.org/chinese/test.htm', 'chapters': [{'content': ['正文', '注释', '载于1980年某杂志']}]}
        self.assertEqual({}, infer_publication(book))
        book['chapters'].append({'content': ['载于1894年杂志']})
        self.assertEqual({}, infer_publication(book))

    def test_source_credit_is_retained_and_body_is_unchanged(self):
        credit = '写于1848年载于1850年3月“民主评论”'
        book = {'sourceUrl': 'https://www.marxists.org/chinese/test.htm', 'year': '1848', 'chapters': [{'content': ['正文', credit]}]}
        original = copy.deepcopy(book)
        value = infer_publication(book)
        self.assertEqual('1850-03', value['publicationDate'])
        self.assertEqual(credit, value['publicationDateBasis'])
        self.assertEqual(original, book)


if __name__ == '__main__':
    unittest.main()
