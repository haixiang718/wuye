"""Real isolated MySQL migration tests; every database has an exact random audit name."""
import importlib.util
from pathlib import Path
import re
import sys
import tempfile
import unittest
from unittest.mock import patch
import uuid

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
import local


class BusinessSchemaMigrationTest(unittest.TestCase):
    def setUp(self):
        source = ROOT / 'scripts/migrate_business_schema.py'
        self.assertTrue(source.exists(), 'The backed-up business schema migration has not been implemented')
        spec = importlib.util.spec_from_file_location('migration_under_test', source)
        self.migration = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.migration)
        self.database = 'audit_schema_' + uuid.uuid4().hex[:12]
        local.mysql_run('CREATE DATABASE `' + self.database + '` CHARACTER SET utf8mb4')
        self.addCleanup(self.drop, self.database)
        self.query("CREATE TABLE contract (contract_id varchar(30) PRIMARY KEY, contract_name varchar(200), "
                   "amount decimal(10,2),store_id varchar(30),status_cd varchar(2) DEFAULT '0') ENGINE=InnoDB; "
                   "INSERT INTO contract VALUES ('legacy','Synthetic legacy contract',123.45,'test-store','0')")
        self.temp = tempfile.TemporaryDirectory(dir=ROOT / '.runtime')
        self.addCleanup(self.temp.cleanup)

    def drop(self, database):
        if not re.fullmatch(r'audit_schema_[a-f0-9]{12}', database):
            raise AssertionError('Unsafe test cleanup')
        local.mysql_run('DROP DATABASE `' + database + '`')

    def query(self, sql):
        return local.mysql_run(sql, database=self.database, capture=True)

    def apply(self):
        return self.migration.apply(self.database, backup_root=Path(self.temp.name))

    def test_apply_backs_up_preserves_contract_and_is_repeatable(self):
        before = self.query('SELECT * FROM contract')
        report = self.apply()
        self.assertEqual(11, report['applied'])
        self.assertTrue(Path(report['backup']['file']).is_file())
        self.assertEqual(64, len(report['backup']['sha256']))
        self.assertEqual(report['contractBefore'], report['contractAfter'])
        self.assertEqual(before, self.query('SELECT contract_id,contract_name,amount,store_id,status_cd FROM contract'))
        self.assertEqual('0', self.query('SELECT count(*) FROM account'))
        again = self.apply()
        self.assertEqual(0, again['applied'])
        self.assertIsNone(again['backup'])

    def test_plan_is_read_only(self):
        self.assertEqual(11, len(self.migration.plan(self.database)))
        self.assertEqual('1', self.query("SELECT count(*) FROM information_schema.tables WHERE table_schema=DATABASE()"))

    def test_backup_failure_prevents_all_ddl(self):
        with patch.object(self.migration, 'create_backup', side_effect=self.migration.MigrationError('Injected backup failure')):
            with self.assertRaises(self.migration.MigrationError):
                self.apply()
        self.assertEqual(11, len(self.migration.plan(self.database)))

    def test_incompatible_existing_account_is_rejected(self):
        self.query('CREATE TABLE account (acct_id varchar(30) PRIMARY KEY,amount double)')
        with self.assertRaisesRegex(self.migration.MigrationError, 'account'):
            self.apply()
        self.assertEqual('0', self.query("SELECT count(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='contract' AND column_name='b_id'"))
        self.assertEqual([], list(Path(self.temp.name).iterdir()))

    def test_incompatible_existing_contract_column_is_rejected(self):
        self.query('ALTER TABLE contract ADD obj_name varchar(10)')
        with self.assertRaisesRegex(self.migration.MigrationError, 'obj_name'):
            self.apply()

    def test_wrong_primary_key_is_rejected(self):
        self.apply()
        self.query('ALTER TABLE business_account DROP PRIMARY KEY, ADD PRIMARY KEY(acct_id)')
        with self.assertRaisesRegex(self.migration.MigrationError, 'PRIMARY'):
            self.apply()

    def test_wrong_active_status_default_is_rejected(self):
        self.apply()
        self.query("ALTER TABLE account ALTER status_cd SET DEFAULT '1'")
        with self.assertRaisesRegex(self.migration.MigrationError, 'status_cd'):
            self.apply()

    def test_mutating_creation_timestamp_is_rejected(self):
        self.apply()
        self.query('ALTER TABLE account MODIFY create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP')
        with self.assertRaisesRegex(self.migration.MigrationError, 'create_time'):
            self.apply()

    def test_extra_mandatory_column_is_rejected(self):
        self.apply()
        self.query('ALTER TABLE account ADD mandatory_extension varchar(10) NOT NULL')
        with self.assertRaisesRegex(self.migration.MigrationError, 'mandatory_extension'):
            self.apply()

    def test_extra_optional_column_remains_compatible(self):
        self.apply()
        self.query('ALTER TABLE account ADD optional_extension varchar(10) DEFAULT NULL')
        self.assertEqual([], self.migration.plan(self.database))

    def test_backup_can_restore_original_contract(self):
        report = self.apply()
        restored = 'audit_schema_' + uuid.uuid4().hex[:12]
        local.mysql_run('CREATE DATABASE `' + restored + '` CHARACTER SET utf8mb4')
        self.addCleanup(self.drop, restored)
        local.mysql_run(database=restored, source=Path(report['backup']['file']))
        count = local.mysql_run('SELECT count(*) FROM contract', database=restored, capture=True)
        self.assertEqual('1', count)
        self.assertEqual('0', local.mysql_run("SELECT count(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='account'", database=restored, capture=True))

    def test_other_database_names_are_rejected(self):
        for database in ['mysql','hc_community','production','TT; DROP DATABASE TT']:
            with self.assertRaises(self.migration.MigrationError):
                self.migration.plan(database)


if __name__ == '__main__':
    unittest.main()
