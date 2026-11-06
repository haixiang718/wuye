"""Pure-file checks for pruning Navicat seed dumps without rewriting retained data."""
import importlib.util
from pathlib import Path
import unittest

MODULE = Path(__file__).resolve().parents[1] / 'prune_v2_seed.py'


def block(name, value=b"'value'", newline=b'\n'):
    name = name.encode('ascii')
    return newline.join([
        b'-- ----------------------------', b'-- Table structure for ' + name,
        b'-- ----------------------------', b'DROP TABLE IF EXISTS `' + name + b'`;',
        b'CREATE TABLE `' + name + b'` (`id` int, `value` text) ENGINE=InnoDB;',
        b'', b'-- ----------------------------', b'-- Records of ' + name,
        b'-- ----------------------------', b'INSERT INTO `' + name + b'` VALUES (1, ' + value + b');', b'', b''])


class SeedPruningTests(unittest.TestCase):
    def setUp(self):
        self.assertTrue(MODULE.exists(), 'V2 seed pruner must exist')
        spec = importlib.util.spec_from_file_location('v2_seed_under_test', MODULE)
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)

    def test_prunes_only_explicit_table_blocks_and_preserves_all_other_bytes(self):
        prefix = b'/* Navicat fixture */\nSET FOREIGN_KEY_CHECKS=0;\n\n'
        footer = b'SET FOREIGN_KEY_CHECKS=1;\n-- End\n'
        keep = block('bill', b"'repair; renting -- keep \\' quote'")
        door = block('application_key')
        machine = block('machine')
        output, report = self.module.prune_dump(prefix + keep + door + machine + footer, {'application_key'})
        self.assertEqual(prefix + keep + machine + footer, output)
        self.assertEqual(['application_key'], report['removedTables'])
        self.assertEqual(2, report['afterTableCount'])

    def test_table_header_inside_multiline_string_is_data(self):
        fake = b"'first\n-- ----------------------------\n-- Table structure for application_key\n-- ----------------------------\nlast'"
        retained = block('bill', fake)
        result, _ = self.module.prune_dump(retained + block('application_key'), {'application_key'})
        self.assertEqual(retained, result)

    def test_retained_crlf_and_non_ascii_data_are_exact(self):
        keep = block('bill', "'测试金额'".encode('utf-8'), b'\r\n')
        result, _ = self.module.prune_dump(keep + block('application_key', newline=b'\r\n'), {'application_key'})
        self.assertEqual(keep, result)

    def test_repeat_and_absent_retired_tables_are_noop(self):
        original = block('bill')
        result, report = self.module.prune_dump(original, {'application_key'})
        self.assertEqual(original, result)
        self.assertEqual([], report['removedTables'])

    def test_empty_last_table_keeps_footer_after_removal(self):
        empty = block('application_key').replace(b"INSERT INTO `application_key` VALUES (1, 'value');\n", b'')
        footer = b'-- dump complete\nSET FOREIGN_KEY_CHECKS=1;\n'
        result, _ = self.module.prune_dump(block('bill') + empty + footer, {'application_key'})
        self.assertEqual(block('bill') + footer, result)

    def test_malformed_and_cross_table_statements_fail_closed(self):
        malformed = [
            block('bill').replace(b'CREATE TABLE `bill`', b'CREATE TABLE `different`'),
            block('bill').replace(b'INSERT INTO `bill`', b'INSERT INTO `application_key`'),
            block('bill') + block('bill'),
            block('bill').replace(b'-- Records of bill', b'-- Records of wrong'),
            block('bill')[:-4],
            block('bill') + b'DROP TABLE `machine`;\n',
            block('bill') + b'/*! DROP TABLE machine */;\n',
            block('bill').replace(b"'value'", b"'unterminated"),
        ]
        for content in malformed:
            with self.subTest(case=malformed.index(content)):
                with self.assertRaises(ValueError):
                    self.module.prune_dump(content, {'application_key'})

    def test_identifiers_cannot_be_patterns_or_injected_sql(self):
        for name in ['application_%', 'bill; DROP DATABASE TT', '']:
            with self.assertRaises(ValueError):
                self.module.prune_dump(block('bill'), {name})


if __name__ == '__main__':
    unittest.main()
