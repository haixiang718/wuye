"""Real additive migration tests in empty disposable schemas; no TT writes."""
from pathlib import Path
import re
import sys
import tempfile
import unittest
from unittest.mock import patch
import uuid
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import migrate_payment_order as migration


class PaymentMigrationTest(unittest.TestCase):
    def setUp(self):
        self.database = 'audit_schema_' + uuid.uuid4().hex[:12]
        migration.base.local.mysql_run('CREATE DATABASE `' + self.database + '` CHARACTER SET utf8mb4')
        self.addCleanup(self.drop)
        self.query("CREATE TABLE fee_receipt (receipt_id varchar(30) PRIMARY KEY,amount decimal(9,2)) ENGINE=InnoDB")
        self.query("INSERT INTO fee_receipt VALUES ('legacy',12.34)")
        self.temp = tempfile.TemporaryDirectory(dir=migration.base.local.RUN)
        self.addCleanup(self.temp.cleanup)

    def drop(self):
        if not re.fullmatch(r'audit_schema_[a-f0-9]{12}', self.database):
            raise AssertionError('Unsafe cleanup')
        migration.base.local.mysql_run('DROP DATABASE `' + self.database + '`')

    def query(self, sql):
        return migration.base.query(self.database, sql)

    def apply(self):
        return migration.apply(self.database, Path(self.temp.name))

    def test_backup_preservation_and_repeat(self):
        result = self.apply()
        self.assertEqual(5, result['applied'])
        self.assertTrue(Path(result['backup']['file']).is_file())
        self.assertEqual('legacy\t12.34', self.query('SELECT receipt_id,amount FROM fee_receipt'))
        self.assertEqual([], migration.plan(self.database))
        self.assertEqual(0, self.apply()['applied'])

    def test_non_unique_transaction_index_rejected(self):
        self.apply()
        self.query('ALTER TABLE payment_order DROP INDEX uk_payment_transaction,ADD INDEX uk_payment_transaction(transaction_id)')
        with self.assertRaises(migration.base.MigrationError):
            migration.plan(self.database)

    def test_incompatible_receipt_column_blocks_all_mutation(self):
        self.query('ALTER TABLE fee_receipt ADD COLUMN pay_obj_id varchar(5)')
        with self.assertRaises(migration.base.MigrationError):
            self.apply()
        self.assertFalse(migration.base.inspect(self.database, 'payment_order')[0])

    def test_failed_backup_blocks_mutation(self):
        with patch.object(migration.base, 'create_backup', side_effect=migration.base.MigrationError('synthetic failure')):
            with self.assertRaises(migration.base.MigrationError):
                self.apply()
        self.assertFalse(migration.base.inspect(self.database, 'payment_order')[0])

    def test_remote_schema_rejected(self):
        with self.assertRaises(migration.base.MigrationError):
            migration.plan('production')


if __name__ == '__main__':
    unittest.main()
