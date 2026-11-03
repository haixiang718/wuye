"""Exercise additive payment-method repair on isolated temporary MySQL schemas."""
from pathlib import Path
import re
import sys
import tempfile
import unittest
from unittest.mock import patch
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import local
import migrate_payment_methods as migration


class PaymentMethodsMigrationTest(unittest.TestCase):
    def setUp(self):
        self.database = 'audit_schema_' + uuid.uuid4().hex[:12]
        local.mysql_run('CREATE DATABASE `' + self.database + '` CHARACTER SET utf8mb4')
        self.addCleanup(self.drop)
        self.folder = tempfile.TemporaryDirectory(dir=local.RUN)
        self.addCleanup(self.folder.cleanup)
        self.query('CREATE TABLE t_dict LIKE TT.t_dict')

    def query(self, sql):
        return local.mysql_run(sql, database=self.database, capture=True)

    def drop(self):
        assert re.fullmatch(r'audit_schema_[a-f0-9]{12}', self.database)
        local.mysql_run('DROP DATABASE `' + self.database + '`')

    def apply(self):
        return migration.apply(self.database, backup_root=Path(self.folder.name))

    def test_plan_does_not_write(self):
        self.assertEqual(6, len(migration.plan(self.database)))
        self.assertEqual('0', self.query('SELECT count(*) FROM t_dict'))

    def test_apply_has_backup_and_is_repeatable(self):
        result = self.apply()
        self.assertEqual(6, result['applied'])
        self.assertTrue(Path(result['backup']['file']).is_file())
        self.assertEqual('6', self.query('SELECT count(*) FROM t_dict'))
        self.assertEqual(0, self.apply()['applied'])
        self.assertEqual('6', self.query('SELECT count(*) FROM t_dict'))

    def test_preserves_existing_labels_and_unrelated_dictionary(self):
        self.query("INSERT INTO t_dict(status_cd,name,description,table_name,table_columns) VALUES "
                   "('1','Custom cash label','Keep custom wording','pay_fee_detail','prime_rate'),"
                   "('1','Unrelated','Keep unrelated dictionary','other_table','other_column')")
        before = self.query('SELECT * FROM t_dict ORDER BY id')
        result = self.apply()
        self.assertEqual(5, result['applied'])
        self.assertEqual(before, self.query('SELECT * FROM t_dict WHERE id<=2 ORDER BY id'))
        self.assertEqual('7', self.query('SELECT count(*) FROM t_dict'))

    def test_backup_failure_prevents_inserts(self):
        with patch.object(migration.base, 'create_backup', side_effect=RuntimeError('Injected backup failure')):
            with self.assertRaises(RuntimeError):
                self.apply()
        self.assertEqual('0', self.query('SELECT count(*) FROM t_dict'))

    def test_ambiguous_duplicate_code_is_rejected(self):
        self.query("INSERT INTO t_dict(status_cd,name,table_name,table_columns) VALUES "
                   "('1','Cash A','pay_fee_detail','prime_rate'),('1','Cash B','pay_fee_detail','prime_rate')")
        with self.assertRaises(migration.base.MigrationError):
            self.apply()
        self.assertEqual('2', self.query('SELECT count(*) FROM t_dict'))
        self.assertEqual([], list(Path(self.folder.name).iterdir()))

    def test_other_databases_are_rejected(self):
        for database in ['mysql', 'production', 'hc_community', 'TT; DROP DATABASE TT']:
            with self.assertRaises(migration.base.MigrationError):
                migration.plan(database)


if __name__ == '__main__':
    unittest.main()
